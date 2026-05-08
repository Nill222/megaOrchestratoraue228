from __future__ import annotations

import base64
import json
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Optional, Tuple

import cv2
import numpy as np


@dataclass
class InspectionResult:
    product_type: str
    status: str
    anomaly_score: float
    threshold: float
    aligned_image_b64: str = ""
    diff_map_b64: str = ""
    heatmap_b64: str = ""
    segmentation_mask_b64: str = ""
    raw_anomaly_score: float = 0.0
    rechecked_zones_count: int = 0
    recheck_adjustment: float = 0.0
    rechecked_zone_ids: list[str] | None = None
    stage_ms_align: float = 0.0
    stage_ms_diff: float = 0.0
    stage_ms_anomaly: float = 0.0
    stage_ms_fp_recheck: float = 0.0
    stage_ms_encode: float = 0.0
    stage_ms_total: float = 0.0


@dataclass
class FPZone:
    id: str
    product_type: str
    points_norm_heatmap: list[Tuple[float, float]]
    points_norm_ref: list[Tuple[float, float]]
    heatmap_w: int
    heatmap_h: int
    created_at: str
    baseline_diff_q90: float = 0.0
    baseline_diff_max: float = 0.0
    baseline_active_ratio: float = 0.0
    baseline_score: float = 0.0
    note: str = ""


class InspectionService:
    def __init__(self) -> None:
        self.references: Dict[str, np.ndarray] = {}
        self.rois: Dict[str, Tuple[float, float, float, float]] = {}
        self.roi_polygons: Dict[str, list[Tuple[float, float]]] = {}
        self._orb = cv2.ORB_create(nfeatures=1800)
        self._matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
        self._fallback_threshold = 0.25
        self._fp_zones_file = Path(__file__).resolve().parent.parent.parent / "data" / "fp_zones.json"
        self.fp_zones: Dict[str, list[FPZone]] = {}
        self._last_diff_maps: Dict[str, np.ndarray] = {}
        self._last_segmentation_masks: Dict[str, np.ndarray] = {}
        self._prepared_reference: Dict[str, dict[str, object]] = {}
        self._polygon_mask_cache: Dict[tuple[int, int, tuple[Tuple[float, float], ...]], np.ndarray] = {}
        self._kernel_3 = np.ones((3, 3), dtype=np.uint8)
        self._kernel_5 = np.ones((5, 5), dtype=np.uint8)
        self._kernel_9 = np.ones((9, 9), dtype=np.uint8)
        self._kernel_15 = np.ones((15, 15), dtype=np.uint8)
        self._kernel_long = cv2.getStructuringElement(cv2.MORPH_RECT, (20, 1))
        self._clahe = cv2.createCLAHE(clipLimit=1.2, tileGridSize=(8, 8))
        self._anomaly_max_dim = 1024

        self._anomaly_engine = None
        self._load_anomalib_engine()
        self._load_fp_zones()

    def _load_anomalib_engine(self) -> None:
        try:
            from anomalib.deploy import OpenVINOInferencer  # type: ignore

            self._anomaly_engine = OpenVINOInferencer(
                path="models/patchcore/openvino/model.xml"
            )
        except Exception:
            self._anomaly_engine = None

    def set_reference(self, product_type: str, image_bytes: bytes) -> None:
        image = self._decode_image(image_bytes)
        self.references[product_type] = image
        self._prepared_reference.pop(product_type, None)

    def set_reference_frame(self, product_type: str, frame: np.ndarray) -> None:
        self.references[product_type] = frame.copy()
        self._prepared_reference.pop(product_type, None)

    def get_reference(self, product_type: str) -> Optional[np.ndarray]:
        return self.references.get(product_type)

    def set_roi(self, product_type: str, x: float, y: float, w: float, h: float) -> None:
        for name, value in {"x": x, "y": y, "w": w, "h": h}.items():
            if value < 0 or value > 1:
                raise ValueError(f"ROI field '{name}' must be in [0, 1]")
        if w <= 0 or h <= 0:
            raise ValueError("ROI width/height must be > 0")
        if x + w > 1 or y + h > 1:
            raise ValueError("ROI rectangle must be fully inside image bounds")
        self.rois[product_type] = (x, y, w, h)

    def get_roi(self, product_type: str) -> Optional[Tuple[float, float, float, float]]:
        return self.rois.get(product_type)

    def set_roi_polygon(self, product_type: str, points: list[Tuple[float, float]]) -> None:
        if len(points) < 3:
            raise ValueError("ROI polygon must contain at least 3 points")
        normalized_points: list[Tuple[float, float]] = []
        for idx, (x, y) in enumerate(points):
            if x < 0 or x > 1 or y < 0 or y > 1:
                raise ValueError(f"ROI polygon point #{idx + 1} must be inside [0, 1]")
            normalized_points.append((float(x), float(y)))
        self.roi_polygons[product_type] = normalized_points

    def get_roi_polygon(self, product_type: str) -> Optional[list[Tuple[float, float]]]:
        return self.roi_polygons.get(product_type)

    def add_fp_zone(
        self,
        product_type: str,
        points_norm_heatmap: list[Tuple[float, float]],
        heatmap_w: int,
        heatmap_h: int,
        note: str = "",
    ) -> FPZone:
        normalized = self._validate_polygon_points(points_norm_heatmap, "FP polygon")
        if self._polygon_area(normalized) < 0.0001:
            raise ValueError("FP polygon area is too small")
        if heatmap_w <= 0 or heatmap_h <= 0:
            raise ValueError("heatmap size must be positive")
        baseline = self._measure_fp_zone_activity(product_type, normalized)
        zone = FPZone(
            id=str(uuid.uuid4()),
            product_type=product_type,
            points_norm_heatmap=normalized,
            points_norm_ref=list(normalized),
            heatmap_w=int(heatmap_w),
            heatmap_h=int(heatmap_h),
            created_at=datetime.now(timezone.utc).isoformat(),
            baseline_diff_q90=baseline["diff_q90"],
            baseline_diff_max=baseline["diff_max"],
            baseline_active_ratio=baseline["active_ratio"],
            baseline_score=baseline["score"],
            note=note.strip(),
        )
        self.fp_zones.setdefault(product_type, []).append(zone)
        self._save_fp_zones()
        return zone

    def get_fp_zones(self, product_type: str) -> list[FPZone]:
        return list(self.fp_zones.get(product_type, []))

    def delete_fp_zone(self, zone_id: str) -> bool:
        for product_type, zones in self.fp_zones.items():
            retained = [zone for zone in zones if zone.id != zone_id]
            if len(retained) != len(zones):
                self.fp_zones[product_type] = retained
                self._save_fp_zones()
                return True
        return False

    def inspect(
        self,
        product_type: str,
        image_bytes: bytes,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
    ) -> InspectionResult:
        reference = self.get_reference(product_type)
        if reference is None:
            raise ValueError(f"Reference for product_type '{product_type}' is not set")
        current = self._decode_image(image_bytes)
        return self._inspect_frames(product_type, reference, current, threshold, include_visuals)

    def inspect_frame(
        self,
        product_type: str,
        frame: np.ndarray,
        threshold: Optional[float] = None,
        include_visuals: bool = True,
        alignment_h_ref_to_cur: list[float] | None = None,
    ) -> InspectionResult:
        reference = self.get_reference(product_type)
        if reference is None:
            raise ValueError(f"Reference for product_type '{product_type}' is not set")
        return self._inspect_frames(product_type, reference, frame, threshold, include_visuals, alignment_h_ref_to_cur)

    def _inspect_frames(
        self,
        product_type: str,
        reference: np.ndarray,
        current: np.ndarray,
        threshold: Optional[float],
        include_visuals: bool,
        alignment_h_ref_to_cur: list[float] | None = None,
    ) -> InspectionResult:
        t_total_0 = time.perf_counter_ns()
        t0 = time.perf_counter_ns()
        aligned = self._align_to_reference(product_type, current, reference, alignment_h_ref_to_cur)
        stage_ms_align = (time.perf_counter_ns() - t0) / 1_000_000.0

        polygon = self.get_roi_polygon(product_type)
        if polygon is not None:
            aligned, reference = self._mask_to_polygon(aligned, reference, polygon)

        roi = self.get_roi(product_type)
        if roi is not None:
            aligned, reference = self._crop_to_roi(aligned, reference, roi)

        t0 = time.perf_counter_ns()
        diff_map = self._compute_advanced_difference(aligned, reference)
        stage_ms_diff = (time.perf_counter_ns() - t0) / 1_000_000.0

        t0 = time.perf_counter_ns()
        anomaly_score, segmentation_mask = self._run_anomaly_model(diff_map)
        stage_ms_anomaly = (time.perf_counter_ns() - t0) / 1_000_000.0
        self._last_diff_maps[product_type] = diff_map.copy()
        self._last_segmentation_masks[product_type] = segmentation_mask.copy()
        raw_score = anomaly_score
        t0 = time.perf_counter_ns()
        fp_recheck = self._recheck_fp_zones(product_type, diff_map, segmentation_mask, raw_score)
        stage_ms_fp_recheck = (time.perf_counter_ns() - t0) / 1_000_000.0
        anomaly_score = fp_recheck["final_score"]
        segmentation_mask = fp_recheck["filtered_mask"]

        inspection_threshold = threshold if threshold is not None else self._fallback_threshold
        status = "БРАК" if anomaly_score >= inspection_threshold else "ГОДЕН"

        heatmap = self._build_heatmap(segmentation_mask, diff_map) if include_visuals else None
        if include_visuals and heatmap is not None:
            heatmap = self._draw_fp_zone_overlay(
                heatmap,
                self.get_fp_zones(product_type),
                fp_recheck["rechecked_zone_ids"],
            )

        t0 = time.perf_counter_ns()
        aligned_image_b64 = self._encode_image(aligned) if include_visuals else ""
        diff_map_b64 = self._encode_image(diff_map) if include_visuals else ""
        heatmap_b64 = self._encode_image(heatmap) if include_visuals and heatmap is not None else ""
        segmentation_mask_b64 = self._encode_image(segmentation_mask) if include_visuals else ""
        stage_ms_encode = (time.perf_counter_ns() - t0) / 1_000_000.0
        stage_ms_total = (time.perf_counter_ns() - t_total_0) / 1_000_000.0

        return InspectionResult(
            product_type=product_type,
            status=status,
            anomaly_score=anomaly_score,
            threshold=inspection_threshold,
            aligned_image_b64=aligned_image_b64,
            diff_map_b64=diff_map_b64,
            heatmap_b64=heatmap_b64,
            segmentation_mask_b64=segmentation_mask_b64,
            raw_anomaly_score=raw_score,
            rechecked_zones_count=len(fp_recheck["rechecked_zone_ids"]),
            recheck_adjustment=raw_score - anomaly_score,
            rechecked_zone_ids=fp_recheck["rechecked_zone_ids"],
            stage_ms_align=stage_ms_align,
            stage_ms_diff=stage_ms_diff,
            stage_ms_anomaly=stage_ms_anomaly,
            stage_ms_fp_recheck=stage_ms_fp_recheck,
            stage_ms_encode=stage_ms_encode,
            stage_ms_total=stage_ms_total,
        )

    def _validate_polygon_points(self, points: list[Tuple[float, float]], label: str) -> list[Tuple[float, float]]:
        if len(points) < 3:
            raise ValueError(f"{label} must contain at least 3 points")
        normalized_points: list[Tuple[float, float]] = []
        for idx, (x, y) in enumerate(points):
            if x < 0 or x > 1 or y < 0 or y > 1:
                raise ValueError(f"{label} point #{idx + 1} must be inside [0, 1]")
            normalized_points.append((float(x), float(y)))
        return normalized_points

    def _polygon_area(self, points: list[Tuple[float, float]]) -> float:
        if len(points) < 3:
            return 0.0
        area = 0.0
        for idx, point in enumerate(points):
            nx, ny = points[(idx + 1) % len(points)]
            area += point[0] * ny - nx * point[1]
        return abs(area) * 0.5

    def _load_fp_zones(self) -> None:
        self.fp_zones = {}
        if not self._fp_zones_file.exists():
            return
        try:
            raw_payload = json.loads(self._fp_zones_file.read_text(encoding="utf-8"))
            entries = raw_payload if isinstance(raw_payload, list) else []
            for entry in entries:
                product_type = str(entry.get("product_type", "")).strip()
                if not product_type:
                    continue
                zone = FPZone(
                    id=str(entry.get("id", str(uuid.uuid4()))),
                    product_type=product_type,
                    points_norm_heatmap=[(float(p[0]), float(p[1])) for p in entry.get("points_norm_heatmap", [])],
                    points_norm_ref=[
                        (float(p[0]), float(p[1]))
                        for p in entry.get("points_norm_ref", entry.get("points_norm_heatmap", []))
                    ],
                    heatmap_w=int(entry.get("heatmap_w", 1)),
                    heatmap_h=int(entry.get("heatmap_h", 1)),
                    created_at=str(entry.get("created_at", datetime.now(timezone.utc).isoformat())),
                    baseline_diff_q90=float(entry.get("baseline_diff_q90", 0.0)),
                    baseline_diff_max=float(entry.get("baseline_diff_max", 0.0)),
                    baseline_active_ratio=float(entry.get("baseline_active_ratio", 0.0)),
                    baseline_score=float(entry.get("baseline_score", 0.0)),
                    note=str(entry.get("note", "")),
                )
                if len(zone.points_norm_ref) >= 3:
                    self.fp_zones.setdefault(product_type, []).append(zone)
        except Exception:
            self.fp_zones = {}

    def _save_fp_zones(self) -> None:
        self._fp_zones_file.parent.mkdir(parents=True, exist_ok=True)
        entries = []
        for zones in self.fp_zones.values():
            for zone in zones:
                entries.append(
                    {
                        "id": zone.id,
                        "product_type": zone.product_type,
                        "points_norm_heatmap": zone.points_norm_heatmap,
                        "points_norm_ref": zone.points_norm_ref,
                        "heatmap_w": zone.heatmap_w,
                        "heatmap_h": zone.heatmap_h,
                        "created_at": zone.created_at,
                        "baseline_diff_q90": zone.baseline_diff_q90,
                        "baseline_diff_max": zone.baseline_diff_max,
                        "baseline_active_ratio": zone.baseline_active_ratio,
                        "baseline_score": zone.baseline_score,
                        "note": zone.note,
                    }
                )
        self._fp_zones_file.write_text(json.dumps(entries, ensure_ascii=True, indent=2), encoding="utf-8")

    def _polygon_mask_from_norm_points(self, width: int, height: int, points: list[Tuple[float, float]]) -> np.ndarray:
        key = (width, height, tuple(points))
        cached = self._polygon_mask_cache.get(key)
        if cached is not None:
            return cached
        pts = np.array(
            [[int(round(x * (width - 1))), int(round(y * (height - 1)))] for x, y in points],
            dtype=np.int32,
        )
        mask = np.zeros((height, width), dtype=np.uint8)
        cv2.fillPoly(mask, [pts], 255)
        self._polygon_mask_cache[key] = mask
        return mask

    def _measure_fp_zone_activity(self, product_type: str, points: list[Tuple[float, float]]) -> dict[str, float]:
        diff_map = self._last_diff_maps.get(product_type)
        segmentation_mask = self._last_segmentation_masks.get(product_type)
        if diff_map is None or segmentation_mask is None:
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}
        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        seg_gray = cv2.cvtColor(segmentation_mask, cv2.COLOR_BGR2GRAY)
        return self._measure_zone_activity(diff_gray, seg_gray, points)

    def _measure_zone_activity(
        self,
        diff_gray: np.ndarray,
        seg_gray: np.ndarray,
        points: list[Tuple[float, float]],
    ) -> dict[str, float]:
        h, w = diff_gray.shape[:2]
        zone_mask = self._polygon_mask_from_norm_points(w, h, points) > 0
        if not np.any(zone_mask):
            return {"diff_q90": 0.0, "diff_max": 0.0, "active_ratio": 0.0, "score": 0.0}

        zone_diff = diff_gray[zone_mask]
        zone_active = seg_gray[zone_mask] > 0
        diff_q90 = float(np.percentile(zone_diff, 90))
        diff_max = float(np.max(zone_diff))
        active_ratio = float(np.mean(zone_active))
        score = float(np.clip((diff_q90 / 255.0) * 0.8 + (diff_max / 255.0) * 0.2 + active_ratio * 1.2, 0.0, 1.0))
        return {"diff_q90": diff_q90, "diff_max": diff_max, "active_ratio": active_ratio, "score": score}

    def _should_suppress_fp_zone(self, zone: FPZone, activity: dict[str, float]) -> bool:
        has_baseline = any(
            value > 0.0
            for value in (
                zone.baseline_diff_q90,
                zone.baseline_diff_max,
                zone.baseline_active_ratio,
                zone.baseline_score,
            )
        )
        if not has_baseline:
            return activity["score"] <= 0.35 and activity["active_ratio"] <= 0.08 and activity["diff_q90"] <= 45.0

        q90_limit = max(zone.baseline_diff_q90 + 18.0, zone.baseline_diff_q90 * 1.45)
        max_limit = max(zone.baseline_diff_max + 25.0, zone.baseline_diff_max * 1.35)
        active_limit = max(zone.baseline_active_ratio + 0.04, zone.baseline_active_ratio * 2.0)
        score_limit = max(zone.baseline_score + 0.20, zone.baseline_score * 1.6)
        return (
            activity["diff_q90"] <= q90_limit
            and activity["diff_max"] <= max_limit
            and activity["active_ratio"] <= active_limit
            and activity["score"] <= score_limit
        )

    def _recheck_fp_zones(
        self,
        product_type: str,
        diff_map: np.ndarray,
        segmentation_mask: np.ndarray,
        raw_score: float,
    ) -> dict:
        zones = self.get_fp_zones(product_type)
        if not zones:
            return {"final_score": raw_score, "rechecked_zone_ids": [], "filtered_mask": segmentation_mask}

        h, w = diff_map.shape[:2]
        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        seg_gray = cv2.cvtColor(segmentation_mask, cv2.COLOR_BGR2GRAY)
        seg_active = (seg_gray > 0).astype(np.uint8) * 255
        seg_active_dilated = cv2.dilate(seg_active, self._kernel_9, iterations=1)
        rechecked_zone_ids: list[str] = []
        combined_suppress_mask = np.zeros((h, w), dtype=bool)
        for zone in zones:
            zone_mask = self._polygon_mask_from_norm_points(w, h, zone.points_norm_ref)
            zone_pixels = zone_mask > 0
            if not np.any(zone_pixels):
                continue
            zone_overlap_pixels = float(np.count_nonzero((seg_active_dilated > 0) & zone_pixels))
            activity = self._measure_zone_activity(diff_gray, seg_gray, zone.points_norm_ref)
            has_zone_activation = zone_overlap_pixels > 0 or activity["diff_q90"] >= 22.0
            if not has_zone_activation:
                continue
            if not self._should_suppress_fp_zone(zone, activity):
                continue
            rechecked_zone_ids.append(zone.id)
            suppress_mask = cv2.dilate(zone_mask, self._kernel_5, iterations=1) > 0
            combined_suppress_mask |= suppress_mask

        if not rechecked_zone_ids:
            return {"final_score": raw_score, "rechecked_zone_ids": [], "filtered_mask": segmentation_mask}

        filtered_diff_map = diff_map.copy()
        filtered_diff_map[combined_suppress_mask] = 0
        remaining_score, filtered_mask = self._run_anomaly_model(filtered_diff_map)
        filtered_mask[combined_suppress_mask] = 0
        final_score = float(min(raw_score, remaining_score))
        return {"final_score": final_score, "rechecked_zone_ids": rechecked_zone_ids, "filtered_mask": filtered_mask}

    def _draw_fp_zone_overlay(self, heatmap: np.ndarray, zones: list[FPZone], rechecked_ids: list[str]) -> np.ndarray:
        if not zones:
            return heatmap
        overlay = heatmap.copy()
        h, w = heatmap.shape[:2]
        for zone in zones:
            pts = np.array(
                [[int(round(x * (w - 1))), int(round(y * (h - 1)))] for x, y in zone.points_norm_ref],
                dtype=np.int32,
            )
            if len(pts) < 3:
                continue
            color = (50, 220, 50) if zone.id in rechecked_ids else (40, 150, 220)
            cv2.fillPoly(overlay, [pts], color)
            cv2.polylines(overlay, [pts], isClosed=True, color=color, thickness=2)
        return cv2.addWeighted(overlay, 0.18, heatmap, 0.82, 0.0)

    def _decode_image(self, image_bytes: bytes) -> np.ndarray:
        data = np.frombuffer(image_bytes, dtype=np.uint8)
        image = cv2.imdecode(data, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Could not decode image")
        return image

    def _encode_image(self, image: np.ndarray) -> str:
        ok, encoded = cv2.imencode(".png", image)
        if not ok:
            return ""
        return base64.b64encode(encoded.tobytes()).decode("utf-8")

    def _align_to_reference(
        self,
        product_type: str,
        current: np.ndarray,
        reference: np.ndarray,
        alignment_h_ref_to_cur: list[float] | None = None,
    ) -> np.ndarray:
        if alignment_h_ref_to_cur is not None and len(alignment_h_ref_to_cur) == 9:
            try:
                h_ref_to_cur = np.asarray(alignment_h_ref_to_cur, dtype=np.float64).reshape(3, 3)
                h_cur_to_ref = np.linalg.inv(h_ref_to_cur)
                return cv2.warpPerspective(current, h_cur_to_ref, (reference.shape[1], reference.shape[0]))
            except Exception:
                pass
        ref_prepared = self._get_or_prepare_reference(product_type, reference)
        ref_gray = ref_prepared["gray"]
        cur_gray = cv2.cvtColor(current, cv2.COLOR_BGR2GRAY)

        kp_ref = ref_prepared["kp"]
        des_ref = ref_prepared["des"]
        kp_cur, des_cur = self._orb.detectAndCompute(cur_gray, None)
        if des_ref is None or des_cur is None or len(kp_ref) < 8 or len(kp_cur) < 8:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        matches = self._matcher.knnMatch(des_cur, des_ref, k=2)
        good_matches = []
        for pair in matches:
            if len(pair) < 2:
                continue
            m, n = pair
            if m.distance < 0.75 * n.distance:
                good_matches.append(m)

        if len(good_matches) < 8:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        src_pts = np.float32([kp_cur[m.queryIdx].pt for m in good_matches]).reshape(-1, 1, 2)
        dst_pts = np.float32([kp_ref[m.trainIdx].pt for m in good_matches]).reshape(-1, 1, 2)

        homography, mask = cv2.findHomography(src_pts, dst_pts, cv2.RANSAC, 1.0)
        if homography is None or mask is None:
            return cv2.resize(current, (reference.shape[1], reference.shape[0]))

        height, width = reference.shape[:2]
        aligned = cv2.warpPerspective(current, homography, (width, height))
        return self._refine_alignment_ecc(aligned, reference, ref_prepared)

    def _get_or_prepare_reference(self, product_type: str, reference: np.ndarray) -> dict[str, object]:
        cached = self._prepared_reference.get(product_type)
        ref_id = id(reference)
        if cached is not None and cached.get("ref_id") == ref_id:
            return cached

        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        kp_ref, des_ref = self._orb.detectAndCompute(ref_gray, None)
        levels = 4
        ref_pyr = [ref_gray]
        for _ in range(1, levels):
            ref_pyr.append(cv2.pyrDown(ref_pyr[-1]))
        prepared = {
            "ref_id": ref_id,
            "gray": ref_gray,
            "kp": kp_ref,
            "des": des_ref,
            "pyr": ref_pyr,
        }
        self._prepared_reference[product_type] = prepared
        return prepared

    def _compute_advanced_difference(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
    ) -> np.ndarray:
        if aligned.shape[:2] != reference.shape[:2]:
            aligned = cv2.resize(aligned, (reference.shape[1], reference.shape[0]))

        ref_gray = cv2.cvtColor(reference, cv2.COLOR_BGR2GRAY)
        cur_gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)

        if float(np.std(cur_gray)) > 5.0:
            ref_gray = self._clahe.apply(ref_gray)
            cur_gray = self._clahe.apply(cur_gray)

        ref_gray = cv2.GaussianBlur(ref_gray, (5, 5), 0)
        cur_gray = cv2.GaussianBlur(cur_gray, (5, 5), 0)

        ref_min = cv2.erode(ref_gray, self._kernel_5, iterations=1)
        ref_max = cv2.dilate(ref_gray, self._kernel_5, iterations=1)

        over = cv2.subtract(cur_gray, ref_max)
        under = cv2.subtract(ref_min, cur_gray)
        robust_gray = cv2.max(over, under)

        robust_gray = cv2.bilateralFilter(robust_gray, d=7, sigmaColor=35, sigmaSpace=35)
        robust_gray = cv2.GaussianBlur(robust_gray, (3, 3), 0)

        blackhat = cv2.morphologyEx(
            robust_gray,
            cv2.MORPH_BLACKHAT,
            self._kernel_15,
        )
        tophat = cv2.morphologyEx(
            robust_gray,
            cv2.MORPH_TOPHAT,
            self._kernel_15,
        )
        robust_gray = cv2.addWeighted(robust_gray, 0.6, blackhat, 0.2, 0.0)
        robust_gray = cv2.addWeighted(robust_gray, 1.0, tophat, 0.2, 0.0)

        edges_ref = cv2.Canny(ref_gray, 80, 160)
        edges_zone = cv2.dilate(edges_ref, self._kernel_3, iterations=2)
        edge_mask = edges_zone > 0
        robust_gray = robust_gray.astype(np.float32)
        robust_gray[edge_mask] *= 0.2
        robust_gray = np.clip(robust_gray, 0, 255).astype(np.uint8)

        structure_mask = cv2.Sobel(ref_gray, cv2.CV_8U, 1, 1, ksize=3)
        text_like_zone = structure_mask > 30
        if np.any(text_like_zone):
            text_vals = robust_gray[text_like_zone]
            robust_gray[text_like_zone] = np.where(text_vals >= 55, text_vals, 0).astype(np.uint8)

        ref_grad_x = cv2.Sobel(ref_gray, cv2.CV_32F, 1, 0, ksize=3)
        ref_grad_y = cv2.Sobel(ref_gray, cv2.CV_32F, 0, 1, ksize=3)
        cur_grad_x = cv2.Sobel(cur_gray, cv2.CV_32F, 1, 0, ksize=3)
        cur_grad_y = cv2.Sobel(cur_gray, cv2.CV_32F, 0, 1, ksize=3)
        ref_grad_mag = cv2.magnitude(ref_grad_x, ref_grad_y)
        cur_grad_mag = cv2.magnitude(cur_grad_x, cur_grad_y)
        contrast_loss_zone = (ref_grad_mag > 40.0) & (cur_grad_mag < 15.0)
        if np.any(contrast_loss_zone):
            robust_float = robust_gray.astype(np.float32)
            robust_float[contrast_loss_zone] *= 2.0
            robust_gray = np.clip(robust_float, 0, 255).astype(np.uint8)

        robust_gray = cv2.medianBlur(robust_gray, 3)
        return cv2.cvtColor(robust_gray, cv2.COLOR_GRAY2BGR)

    def _refine_alignment_ecc(self, aligned: np.ndarray, reference: np.ndarray, ref_prepared: dict[str, object]) -> np.ndarray:
        ref_gray = ref_prepared["gray"]
        aligned_gray = cv2.cvtColor(aligned, cv2.COLOR_BGR2GRAY)
        levels = 4
        ref_pyr = ref_prepared["pyr"]
        aligned_pyr = [aligned_gray]
        for _ in range(1, levels):
            aligned_pyr.append(cv2.pyrDown(aligned_pyr[-1]))

        warp_aff = np.eye(2, 3, dtype=np.float32)
        criteria = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 50, 1e-4)
        try:
            for level in reversed(range(levels)):
                ref_lvl = ref_pyr[level]
                cur_lvl = aligned_pyr[level]

                if level < levels - 1:
                    warp_aff[0, 2] *= 2.0
                    warp_aff[1, 2] *= 2.0

                shift, _ = cv2.phaseCorrelate(
                    np.float32(ref_lvl),
                    np.float32(cur_lvl),
                )
                warp_aff[0, 2] += float(shift[0])
                warp_aff[1, 2] += float(shift[1])

                cv2.findTransformECC(
                    ref_lvl,
                    cur_lvl,
                    warp_aff,
                    cv2.MOTION_AFFINE,
                    criteria,
                    None,
                    5,
                )

            h, w = reference.shape[:2]
            return cv2.warpAffine(
                aligned,
                warp_aff,
                (w, h),
                flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                borderMode=cv2.BORDER_REPLICATE,
            )
        except Exception:
            return aligned

    def _run_anomaly_model(self, diff_map: np.ndarray) -> Tuple[float, np.ndarray]:
        gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        scale = 1.0
        orig_h, orig_w = gray.shape[:2]
        max_side = max(orig_h, orig_w)
        if max_side > self._anomaly_max_dim:
            scale = self._anomaly_max_dim / float(max_side)
            target_w = max(1, int(round(orig_w * scale)))
            target_h = max(1, int(round(orig_h * scale)))
            gray = cv2.resize(gray, (target_w, target_h), interpolation=cv2.INTER_AREA)
        gray_blur = cv2.GaussianBlur(gray, (3, 3), 0)
        if float(np.max(gray_blur)) < 12.0:
            zero = np.zeros_like(gray_blur, dtype=np.uint8)
            if scale < 1.0:
                zero = cv2.resize(zero, (orig_w, orig_h), interpolation=cv2.INTER_NEAREST)
            return 0.0, cv2.cvtColor(zero, cv2.COLOR_GRAY2BGR)
        threshold_value = float(max(10.0, min(np.percentile(gray_blur, 98), 35.0)))
        _, binary = cv2.threshold(gray_blur, threshold_value, 255, cv2.THRESH_BINARY)

        cleaned = cv2.medianBlur(binary, 3)
        cleaned = cv2.morphologyEx(
            cleaned,
            cv2.MORPH_CLOSE,
            self._kernel_3,
            iterations=1,
        )
        cleaned = cv2.morphologyEx(
            cleaned,
            cv2.MORPH_CLOSE,
            self._kernel_long,
            iterations=1,
        )

        num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(cleaned, connectivity=8)
        filtered = np.zeros_like(cleaned)
        min_area = 6
        max_aspect = 0.0
        max_object_score = 0.0
        grad_x = cv2.Sobel(gray_blur, cv2.CV_32F, 1, 0, ksize=3)
        grad_y = cv2.Sobel(gray_blur, cv2.CV_32F, 0, 1, ksize=3)
        grad_mag = cv2.magnitude(grad_x, grad_y)
        text_like_zone = grad_mag > 25.0
        cur_grad_mag = grad_mag
        ref_grad_mag = cv2.GaussianBlur(cur_grad_mag, (9, 9), 0)
        for label_idx in range(1, num_labels):
            area = int(stats[label_idx, cv2.CC_STAT_AREA])
            w = max(1, int(stats[label_idx, cv2.CC_STAT_WIDTH]))
            h = max(1, int(stats[label_idx, cv2.CC_STAT_HEIGHT]))
            aspect = max(w / h, h / w)
            object_min_area = 5 if aspect > 6.0 else min_area

            if area >= object_min_area or (area > 3 and aspect > 3.0):
                component_mask = labels == label_idx
                text_overlap = 0.0
                if np.any(component_mask):
                    text_overlap = float(np.mean(text_like_zone[component_mask]))
                is_text_critical = aspect > 8.0 and text_overlap > 0.2
                if is_text_critical or area >= object_min_area or (area > 3 and aspect > 3.0):
                    filtered[component_mask] = 255
                    max_aspect = max(max_aspect, float(aspect))
                    local_score = float((aspect / 15.0) + (area / 500.0))
                    if text_overlap > 0.2:
                        local_score *= 1.3

                    ref_object_grad = float(np.mean(ref_grad_mag[component_mask])) if np.any(component_mask) else 0.0
                    cur_object_grad = float(np.mean(cur_grad_mag[component_mask])) if np.any(component_mask) else 0.0
                    if ref_object_grad > (cur_object_grad + 12.0):
                        local_score += 0.4

                    max_object_score = max(max_object_score, local_score)

        filtered = cv2.dilate(filtered, self._kernel_3, iterations=1)

        flat = gray_blur.reshape(-1)
        k = max(1, int(flat.size * 0.01))
        top_mean = float(np.mean(np.partition(flat, -k)[-k:])) / 255.0

        heuristic_score = float(np.clip(max_object_score + (top_mean * 1.5), 0.0, 1.0))
        if max_aspect > 4.5:
            heuristic_score = max(heuristic_score, 0.35)
        if scale < 1.0:
            filtered = cv2.resize(filtered, (orig_w, orig_h), interpolation=cv2.INTER_NEAREST)
        heuristic_mask = cv2.cvtColor(filtered, cv2.COLOR_GRAY2BGR)

        if self._anomaly_engine is not None:
            try:
                prediction = self._anomaly_engine.predict(image=diff_map)
                model_score = float(prediction.pred_score)
                mask = prediction.pred_mask.astype(np.uint8) * 255
                if len(mask.shape) == 2:
                    mask = cv2.cvtColor(mask, cv2.COLOR_GRAY2BGR)
                merged_mask = cv2.bitwise_or(mask, heuristic_mask)
                return max(model_score, heuristic_score), merged_mask
            except Exception:
                pass

        return heuristic_score, heuristic_mask

    def _build_heatmap(self, mask: np.ndarray, diff_map: Optional[np.ndarray] = None) -> np.ndarray:
        mask_gray = cv2.cvtColor(mask, cv2.COLOR_BGR2GRAY)
        if diff_map is None:
            return cv2.applyColorMap(mask_gray, cv2.COLORMAP_JET)

        diff_gray = cv2.cvtColor(diff_map, cv2.COLOR_BGR2GRAY)
        diff_norm = cv2.normalize(diff_gray, None, 0, 255, cv2.NORM_MINMAX)
        combined = cv2.max(mask_gray, diff_norm)
        combined = cv2.normalize(combined, None, 0, 255, cv2.NORM_MINMAX)
        combined_gamma = np.power(combined.astype(np.float32) / 255.0, 0.8) * 255.0
        combined = np.clip(combined_gamma, 0, 255).astype(np.uint8)
        heatmap = cv2.applyColorMap(combined, cv2.COLORMAP_JET)

        mask_float = (mask_gray.astype(np.float32) / 255.0)[..., np.newaxis]
        boosted = heatmap.astype(np.float32) * (1.0 + 0.5 * mask_float)
        return np.clip(boosted, 0, 255).astype(np.uint8)

    def _crop_to_roi(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
        roi: Tuple[float, float, float, float],
    ) -> Tuple[np.ndarray, np.ndarray]:
        x_ratio, y_ratio, w_ratio, h_ratio = roi
        height, width = reference.shape[:2]
        x1 = int(round(x_ratio * width))
        y1 = int(round(y_ratio * height))
        x2 = int(round((x_ratio + w_ratio) * width))
        y2 = int(round((y_ratio + h_ratio) * height))

        x1 = max(0, min(x1, width - 1))
        y1 = max(0, min(y1, height - 1))
        x2 = max(x1 + 1, min(x2, width))
        y2 = max(y1 + 1, min(y2, height))

        return aligned[y1:y2, x1:x2], reference[y1:y2, x1:x2]

    def _mask_to_polygon(
        self,
        aligned: np.ndarray,
        reference: np.ndarray,
        polygon: list[Tuple[float, float]],
    ) -> Tuple[np.ndarray, np.ndarray]:
        height, width = reference.shape[:2]
        pts = np.array(
            [[int(round(x * (width - 1))), int(round(y * (height - 1)))] for x, y in polygon],
            dtype=np.int32,
        )
        mask = np.zeros((height, width), dtype=np.uint8)
        cv2.fillPoly(mask, [pts], 255)

        aligned_masked = cv2.bitwise_and(aligned, aligned, mask=mask)
        reference_masked = cv2.bitwise_and(reference, reference, mask=mask)
        return aligned_masked, reference_masked

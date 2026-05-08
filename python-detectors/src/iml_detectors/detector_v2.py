from __future__ import annotations

from typing import Any

import numpy as np


def detect(
    image: np.ndarray,
    rois: list[dict[str, Any]] | None = None,
    product_type: str | None = None,
    threshold: float | None = None,
    include_visuals: bool = False,
) -> dict[str, Any]:
    """Лёгкий baseline-детектор v2 без тяжёлых зависимостей."""
    _ = (rois, include_visuals)
    h, w = image.shape[:2]
    score = float(image.mean()) / 255.0
    applied_threshold = threshold if threshold is not None else 0.75
    status = "ГОДЕН" if score < applied_threshold else "БРАК"
    return {
        "detector_id": "v2",
        "product_type": product_type or "default",
        "status": status,
        "anomaly_score": score,
        "threshold": applied_threshold,
        "raw_anomaly_score": score,
        "rechecked_zones_count": 0,
        "recheck_adjustment": 0.0,
        "rechecked_zone_ids": [],
        "aligned_image_b64": "",
        "diff_map_b64": "",
        "heatmap_b64": "",
        "segmentation_mask_b64": "",
        "details": {"variant": 2, "shape": [h, w]},
    }

from __future__ import annotations

import os
from typing import Any

import numpy as np

from .detector_registry import DetectorRegistry

_DEFAULT_PRODUCT_TYPE = "default"
_DEFAULT_DETECTOR = os.environ.get("IML_DETECTOR_ID", "v1")
_REGISTRY = DetectorRegistry(default_detector_id=_DEFAULT_DETECTOR)
_ACTIVE_DETECTOR = _DEFAULT_DETECTOR


def set_detector(detector_id: str | None = None) -> dict[str, Any]:
    global _ACTIVE_DETECTOR
    if detector_id:
        _ACTIVE_DETECTOR = detector_id.strip().lower()
    return {"status": "ok", "detector_id": _ACTIVE_DETECTOR}


def set_reference(frame: np.ndarray, product_type: str | None = None) -> dict[str, Any]:
    pt = product_type or _DEFAULT_PRODUCT_TYPE
    detector = _REGISTRY.resolve(_ACTIVE_DETECTOR)
    if hasattr(detector, "set_reference"):
        response = detector.set_reference(frame, pt)
        if isinstance(response, dict):
            response["detector_id"] = _ACTIVE_DETECTOR
            return response
    return {"status": "ok", "product_type": pt, "detector_id": _ACTIVE_DETECTOR}


def detect(frame: np.ndarray, frame_id: int | None = None, product_type: str | None = None) -> dict[str, Any]:
    pt = product_type or _DEFAULT_PRODUCT_TYPE
    detector = _REGISTRY.resolve(_ACTIVE_DETECTOR)
    result = detector.detect(frame, product_type=pt, threshold=None, include_visuals=False)
    if isinstance(result, dict):
        result["detector_id"] = _ACTIVE_DETECTOR
        if "ok" not in result:
            status = str(result.get("status", "ERROR"))
            result["ok"] = status not in {"БРАК", "FAIL", "ERROR"}
        result.setdefault("message", f"product_type={pt};frame_id={frame_id}")
        return result
    return {
        "ok": False,
        "status": "ERROR",
        "anomaly_score": 1.0,
        "message": "detector_result_not_dict",
    }

from __future__ import annotations

from typing import Any

import numpy as np
from dataclasses import asdict
from .services.inspection_service import InspectionService

_SERVICE = InspectionService()
_DEFAULT_PRODUCT_TYPE = "default"

def set_reference(image: np.ndarray, product_type: str | None = None) -> dict[str, Any]:
    pt = product_type or _DEFAULT_PRODUCT_TYPE
    _SERVICE.set_reference_frame(pt, image)
    return {"status": "ok", "product_type": pt, "detector_id": "v1"}


def detect(
    image: np.ndarray,
    rois: list[dict[str, Any]] | None = None,
    product_type: str | None = None,
    threshold: float | None = None,
    include_visuals: bool = False,
    alignment_h_ref_to_cur: list[float] | None = None,
) -> dict[str, Any]:
    _ = rois
    pt = product_type or _DEFAULT_PRODUCT_TYPE
    result = _SERVICE.inspect_frame(
        product_type=pt,
        frame=image,
        threshold=threshold,
        include_visuals=include_visuals,
        alignment_h_ref_to_cur=alignment_h_ref_to_cur,
    )
    payload = asdict(result)
    payload["detector_id"] = "v1"
    return payload

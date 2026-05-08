from __future__ import annotations

from importlib import import_module
from typing import Any


class DetectorRegistry:
    def __init__(self, default_detector_id: str = "v1") -> None:
        self.default_detector_id = default_detector_id
        self._cache: dict[str, Any] = {}

    def resolve(self, detector_id: str | None) -> Any:
        det_id = (detector_id or self.default_detector_id or "v1").strip().lower()
        if det_id not in self._cache:
            module = import_module(f"iml_detectors.detector_{det_id}")
            self._cache[det_id] = module
        return self._cache[det_id]


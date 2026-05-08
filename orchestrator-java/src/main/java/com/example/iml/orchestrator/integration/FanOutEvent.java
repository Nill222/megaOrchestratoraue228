package com.example.iml.orchestrator.integration;

public record FanOutEvent(
        int cameraId,
        long frameId,
        boolean overallPass,
        String action,
        double anomalyScore,
        String pythonStatus,
        String geometryStatus,
        long timestampMs
) {
}

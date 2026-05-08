package com.example.iml.dto;

public record InspectionRequest(
        String referenceImageBase64,
        String currentImageBase64,
        RoiRect mainRoi,
        RoiRect jointRoi,
        RoiRect wrinklesRoi,
        double pixelsToMm,
        double maxShiftMm,
        double maxRotationDeg,
        double maxConcentricityMm,
        double maxJointDefectMm,
        double maxWrinklesScore
) {
}

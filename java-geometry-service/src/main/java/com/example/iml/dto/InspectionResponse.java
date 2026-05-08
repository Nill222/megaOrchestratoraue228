package com.example.iml.dto;

public record InspectionResponse(
        double shiftXmm,
        double shiftYmm,
        double rotationDeg,
        double[] homographyRefToCurrent,
        double concentricityMm,
        double jointDefectMm,
        double wrinklesScore,
        boolean alignmentPass,
        boolean concentricityPass,
        boolean jointPass,
        boolean wrinklesPass,
        boolean overallPass,
        String debugImageBase64,
        String status
) {
}

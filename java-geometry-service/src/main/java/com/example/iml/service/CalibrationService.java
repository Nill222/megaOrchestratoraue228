package com.example.iml.service;

public class CalibrationService {

    public double pixelsToMillimeters(double pixels, double pixelsToMm) {
        return pixels * pixelsToMm;
    }

    public double calibratePixelsToMm(double knownDistancePx, double knownDistanceMm) {
        if (knownDistancePx <= 0.0 || knownDistanceMm <= 0.0) {
            throw new IllegalArgumentException("Calibration distances must be positive.");
        }
        return knownDistanceMm / knownDistancePx;
    }
}

package com.example.iml.service;

import com.example.iml.dto.InspectionRequest;
import com.example.iml.dto.InspectionResponse;
import com.example.iml.dto.RoiRect;
import com.example.iml.opencv.OpenCvNativeLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeometryCorrectnessGateMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double EPS = 1e-6;

    private GeometryCorrectnessGateMain() {
    }

    public static void main(String[] args) throws Exception {
        OpenCvNativeLoader.ensureLoaded();

        Path refPath = args.length > 0 ? Path.of(args[0]) : Path.of("testimage/ref.jpg");
        Path curPath = args.length > 1 ? Path.of(args[1]) : Path.of("testimage/cur.jpg");

        Mat ref = Imgcodecs.imread(refPath.toString(), Imgcodecs.IMREAD_COLOR);
        Mat cur = Imgcodecs.imread(curPath.toString(), Imgcodecs.IMREAD_COLOR);
        if (ref.empty() || cur.empty()) {
            throw new IllegalArgumentException("failed to load input images ref=" + refPath + " cur=" + curPath);
        }

        RoiRect mainRoi;
        if (args.length >= 6) {
            int rx = Integer.parseInt(args[2].trim());
            int ry = Integer.parseInt(args[3].trim());
            int rw = Integer.parseInt(args[4].trim());
            int rh = Integer.parseInt(args[5].trim());
            mainRoi = clampRoi(cur.cols(), cur.rows(), rx, ry, rw, rh);
        } else {
            mainRoi = new RoiRect(0, 0, cur.cols(), cur.rows());
        }

        InspectionRequest req = new InspectionRequest(
                "",
                "",
                mainRoi,
                null,
                null,
                0.01,
                0.5,
                1.0,
                0.2,
                0.3,
                0.05
        );

        OpenCvGeometryAnalysisService baseline = new OpenCvGeometryAnalysisService(
                new OpenCvImageCodec(),
                new CalibrationService(),
                false,
                false,
                900,
                null
        );
        OpenCvGeometryAnalysisService candidate = new OpenCvGeometryAnalysisService(
                new OpenCvImageCodec(),
                new CalibrationService(),
                true,
                true,
                560,
                null
        );

        List<String> mismatches = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            InspectionResponse baseRefRef = baseline.inspectMats(ref, ref, req, false);
            InspectionResponse candRefRef = candidate.inspectMats(ref, ref, req, false);
            compare("ref_ref", baseRefRef, candRefRef, mismatches);

            InspectionResponse baseRefCur = baseline.inspectMats(ref, cur, req, false);
            InspectionResponse candRefCur = candidate.inspectMats(ref, cur, req, false);
            compare("ref_cur", baseRefCur, candRefCur, mismatches);

            out.put("ref", refPath.toString());
            out.put("cur", curPath.toString());
            out.put("main_roi", Map.of(
                    "x", mainRoi.x(),
                    "y", mainRoi.y(),
                    "width", mainRoi.width(),
                    "height", mainRoi.height()
            ));
            out.put("strict_compare_passed", mismatches.isEmpty());
            out.put("mismatch_count", mismatches.size());
            if (!mismatches.isEmpty()) {
                out.put("mismatches", mismatches);
            }
        } finally {
            ref.release();
            cur.release();
        }

        System.out.println(MAPPER.writeValueAsString(out));
        if (!mismatches.isEmpty()) {
            throw new IllegalStateException("Stage G correctness failed");
        }
    }

    private static RoiRect clampRoi(int cols, int rows, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("ROI width and height must be positive, got w=" + w + " h=" + h);
        }
        x = Math.max(0, Math.min(x, Math.max(0, cols - 1)));
        y = Math.max(0, Math.min(y, Math.max(0, rows - 1)));
        w = Math.min(w, cols - x);
        h = Math.min(h, rows - y);
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("ROI outside image bounds cols=" + cols + " rows=" + rows);
        }
        return new RoiRect(x, y, w, h);
    }

    private static void compare(String scenario, InspectionResponse a, InspectionResponse b, List<String> mismatches) {
        if (a.overallPass() != b.overallPass()) mismatches.add(scenario + " overallPass");
        if (a.alignmentPass() != b.alignmentPass()) mismatches.add(scenario + " alignmentPass");
        if (a.concentricityPass() != b.concentricityPass()) mismatches.add(scenario + " concentricityPass");
        if (a.jointPass() != b.jointPass()) mismatches.add(scenario + " jointPass");
        if (a.wrinklesPass() != b.wrinklesPass()) mismatches.add(scenario + " wrinklesPass");
        cmp(scenario, "shiftXmm", a.shiftXmm(), b.shiftXmm(), mismatches);
        cmp(scenario, "shiftYmm", a.shiftYmm(), b.shiftYmm(), mismatches);
        cmp(scenario, "rotationDeg", a.rotationDeg(), b.rotationDeg(), mismatches);
        cmp(scenario, "concentricityMm", a.concentricityMm(), b.concentricityMm(), mismatches);
        cmp(scenario, "jointDefectMm", a.jointDefectMm(), b.jointDefectMm(), mismatches);
        cmp(scenario, "wrinklesScore", a.wrinklesScore(), b.wrinklesScore(), mismatches);
    }

    private static void cmp(String scenario, String field, double expected, double actual, List<String> mismatches) {
        if (Math.abs(expected - actual) > EPS) {
            mismatches.add(scenario + " " + field + " expected=" + expected + " actual=" + actual);
        }
    }
}


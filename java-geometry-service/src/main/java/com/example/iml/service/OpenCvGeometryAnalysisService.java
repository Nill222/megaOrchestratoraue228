package com.example.iml.service;

import com.example.iml.dto.InspectionRequest;
import com.example.iml.dto.InspectionResponse;
import com.example.iml.dto.RoiRect;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.core.KeyPoint;
import org.opencv.features2d.BFMatcher;
import org.opencv.features2d.ORB;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class OpenCvGeometryAnalysisService implements GeometryAnalysisService {

    private static final int EXPECTED_WIDTH = 2448;
    private static final int EXPECTED_HEIGHT = 2048;
    private static final int MAX_ALIGNMENT_DIM = 640;
    private static final int MAX_CIRCLE_DIM = 320;
    private static final int ORB_FEATURES = 1500;

    private final OpenCvImageCodec imageCodec;
    private final CalibrationService calibrationService;
    private final boolean parallelPostStages;
    private final boolean referenceCacheEnabled;
    private final int circleMaxDim;
    private final ORB optimizedOrb;
    private final BFMatcher optimizedMatcher;
    private final StageTimingSink stageTimingSink;
    private PreparedReference preparedReferenceCache;

    public OpenCvGeometryAnalysisService(OpenCvImageCodec imageCodec, CalibrationService calibrationService) {
        this(imageCodec, calibrationService, true, true);
    }

    public OpenCvGeometryAnalysisService(
            OpenCvImageCodec imageCodec,
            CalibrationService calibrationService,
            boolean parallelPostStages,
            boolean referenceCacheEnabled
    ) {
        this(imageCodec, calibrationService, parallelPostStages, referenceCacheEnabled, MAX_CIRCLE_DIM, null);
    }

    public OpenCvGeometryAnalysisService(
            OpenCvImageCodec imageCodec,
            CalibrationService calibrationService,
            boolean parallelPostStages,
            boolean referenceCacheEnabled,
            StageTimingSink stageTimingSink
    ) {
        this(imageCodec, calibrationService, parallelPostStages, referenceCacheEnabled, MAX_CIRCLE_DIM, stageTimingSink);
    }

    public OpenCvGeometryAnalysisService(
            OpenCvImageCodec imageCodec,
            CalibrationService calibrationService,
            boolean parallelPostStages,
            boolean referenceCacheEnabled,
            int circleMaxDim,
            StageTimingSink stageTimingSink
    ) {
        this.imageCodec = imageCodec;
        this.calibrationService = calibrationService;
        this.parallelPostStages = parallelPostStages;
        this.referenceCacheEnabled = referenceCacheEnabled;
        this.circleMaxDim = Math.max(240, circleMaxDim);
        this.optimizedOrb = parallelPostStages ? ORB.create(ORB_FEATURES) : null;
        this.optimizedMatcher = parallelPostStages ? BFMatcher.create(Core.NORM_HAMMING, false) : null;
        this.stageTimingSink = stageTimingSink;
    }

    @Override
    public InspectionResponse inspect(InspectionRequest request) {
        return inspect(request, true);
    }

    public InspectionResponse inspect(InspectionRequest request, boolean includeDebugImage) {
        Mat reference = imageCodec.decodeBase64(request.referenceImageBase64());
        Mat current = imageCodec.decodeBase64(request.currentImageBase64());
        try {
            return inspectMats(reference, current, request, includeDebugImage);
        } finally {
            release(reference, current);
        }
    }

    public InspectionResponse inspectMats(Mat reference, Mat current, InspectionRequest request) {
        return inspectMats(reference, current, request, true);
    }

    public InspectionResponse inspectMats(Mat reference, Mat current, InspectionRequest request, boolean includeDebugImage) {
        Mat alignedCurrent = null;
        Mat debug = null;
        Mat referenceRoi = null;
        Mat currentRoi = null;
        Mat alignedCurrentRoi = null;
        AlignmentResult alignment = null;
        try {
            validateInputFrames(reference, current);

            Rect mainRect = toSafeRect(request.mainRoi(), current.cols(), current.rows());
            referenceRoi = new Mat(reference, mainRect);
            currentRoi = new Mat(current, mainRect);

            long tAlign0 = System.nanoTime();
            alignment = alignByHomography(referenceRoi, currentRoi, request.pixelsToMm());
            recordStage("align", tAlign0);
            long tWarp0 = System.nanoTime();
            alignedCurrent = warpCurrentToReference(current, alignment.homographyRefToCurrent, mainRect);
            alignedCurrentRoi = new Mat(alignedCurrent, mainRect);
            recordStage("warp", tWarp0);

            ConcentricityResult concentricity;
            JointResult joint;
            WrinklesResult wrinkles;
            if (parallelPostStages) {
                final Mat alignedCurrentForParallel = alignedCurrent;
                final Mat alignedCurrentRoiForParallel = alignedCurrentRoi;
                long tConcentricity0 = System.nanoTime();
                CompletableFuture<ConcentricityResult> concentricityFuture =
                        CompletableFuture.supplyAsync(() -> {
                            ConcentricityResult r = estimateConcentricity(alignedCurrentRoiForParallel, request.pixelsToMm());
                            recordStage("concentricity", tConcentricity0);
                            return r;
                        });
                long tJoint0 = System.nanoTime();
                CompletableFuture<JointResult> jointFuture =
                        CompletableFuture.supplyAsync(() -> {
                            JointResult r = inspectJoint(alignedCurrentForParallel, request.jointRoi(), request.pixelsToMm());
                            recordStage("joint", tJoint0);
                            return r;
                        });
                long tWrinkles0 = System.nanoTime();
                CompletableFuture<WrinklesResult> wrinklesFuture =
                        CompletableFuture.supplyAsync(() -> {
                            WrinklesResult r = inspectWrinkles(reference, alignedCurrentForParallel, request.wrinklesRoi());
                            recordStage("wrinkles", tWrinkles0);
                            return r;
                        });
                concentricity = joinStage(concentricityFuture, "concentricity");
                joint = joinStage(jointFuture, "joint");
                wrinkles = joinStage(wrinklesFuture, "wrinkles");
            } else {
                long tConcentricity0 = System.nanoTime();
                concentricity = estimateConcentricity(alignedCurrentRoi, request.pixelsToMm());
                recordStage("concentricity", tConcentricity0);
                long tJoint0 = System.nanoTime();
                joint = inspectJoint(alignedCurrent, request.jointRoi(), request.pixelsToMm());
                recordStage("joint", tJoint0);
                long tWrinkles0 = System.nanoTime();
                wrinkles = inspectWrinkles(reference, alignedCurrent, request.wrinklesRoi());
                recordStage("wrinkles", tWrinkles0);
            }

            String debugBase64 = "";
            if (includeDebugImage) {
                long tDebug0 = System.nanoTime();
                debug = alignedCurrent.clone();
                drawDebug(debug, mainRect, alignment, concentricity, request);
                debugBase64 = imageCodec.encodeBase64Png(debug);
                recordStage("debug", tDebug0);
            }

            boolean alignmentPass = Math.abs(alignment.shiftXmm) <= request.maxShiftMm()
                    && Math.abs(alignment.shiftYmm) <= request.maxShiftMm()
                    && Math.abs(alignment.rotationDeg) <= request.maxRotationDeg();
            boolean concentricityPass = concentricity.deviationMm <= request.maxConcentricityMm();
            boolean jointPass = joint.defectMm <= request.maxJointDefectMm();
            boolean wrinklesPass = wrinkles.score <= request.maxWrinklesScore();
            boolean overallPass = alignmentPass && concentricityPass && jointPass && wrinklesPass;

            return new InspectionResponse(
                    alignment.shiftXmm,
                    alignment.shiftYmm,
                    alignment.rotationDeg,
                    homographyToArray(alignment.homographyRefToCurrent),
                    concentricity.deviationMm,
                    joint.defectMm,
                    wrinkles.score,
                    alignmentPass,
                    concentricityPass,
                    jointPass,
                    wrinklesPass,
                    overallPass,
                    debugBase64,
                    overallPass ? "PASS" : "FAIL"
            );
        } finally {
            release(referenceRoi, currentRoi, alignedCurrentRoi, debug, alignedCurrent);
            if (alignment != null && alignment.homographyRefToCurrent != null) {
                alignment.homographyRefToCurrent.release();
            }
        }
    }

    private static <T> T joinStage(CompletableFuture<T> future, String stage) {
        try {
            return future.join();
        } catch (CompletionException e) {
            throw new IllegalStateException("Failed " + stage + " stage", e.getCause() == null ? e : e.getCause());
        }
    }

    private double[] homographyToArray(Mat homography) {
        if (homography == null || homography.empty() || homography.rows() != 3 || homography.cols() != 3) {
            return new double[0];
        }
        double[] flat = new double[9];
        int idx = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                double[] v = homography.get(r, c);
                flat[idx++] = (v == null || v.length == 0) ? 0.0 : v[0];
            }
        }
        return flat;
    }

    private void recordStage(String stage, long startedNs) {
        if (stageTimingSink != null) {
            stageTimingSink.record(stage, System.nanoTime() - startedNs);
        }
    }

    private AlignmentResult alignByHomography(Mat referenceRoi, Mat currentRoi, double pixelsToMm) {
        Mat curGray = new Mat();
        Mat curGrayScaled = null;
        Mat curDescriptors = new Mat();
        MatOfKeyPoint curKeypoints = new MatOfKeyPoint();
        try {
            PreparedReference preparedReference = getOrBuildPreparedReference(referenceRoi);
            Imgproc.cvtColor(currentRoi, curGray, Imgproc.COLOR_BGR2GRAY);
            ResizeResult curResize = resizeForProcessing(curGray, MAX_ALIGNMENT_DIM);
            curGrayScaled = curResize.mat;

            detectAndComputeFeatures(curGrayScaled, curKeypoints, curDescriptors);
            if (preparedReference.descriptors.empty() || curDescriptors.empty()) {
                throw new IllegalStateException("Unable to detect enough keypoints in main ROI.");
            }

            List<DMatch> goodMatches = ratioTestMatches(preparedReference.descriptors, curDescriptors);
            if (goodMatches.size() < 8) {
                throw new IllegalStateException("Not enough valid matches for homography.");
            }

            KeyPoint[] refPoints = preparedReference.keypoints;
            KeyPoint[] curPoints = curKeypoints.toArray();

            List<Point> srcPoints = new ArrayList<>(goodMatches.size());
            List<Point> dstPoints = new ArrayList<>(goodMatches.size());
            for (DMatch match : goodMatches) {
                srcPoints.add(refPoints[match.queryIdx].pt);
                dstPoints.add(curPoints[match.trainIdx].pt);
            }

            MatOfPoint2f src = new MatOfPoint2f();
            MatOfPoint2f dst = new MatOfPoint2f();
            Mat inliersMask = new Mat();
            Mat homography = new Mat();
            try {
                src.fromList(srcPoints);
                dst.fromList(dstPoints);
                homography = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 3.0, inliersMask);
                if (homography.empty()) {
                    throw new IllegalStateException("Homography estimation failed.");
                }

                Mat scaledToOriginalHomography = toOriginalScaleHomography(
                        homography,
                        preparedReference.scaleX,
                        preparedReference.scaleY
                );
                double shiftXPx = scaledToOriginalHomography.get(0, 2)[0];
                double shiftYPx = scaledToOriginalHomography.get(1, 2)[0];
                double rotationDeg = Math.toDegrees(Math.atan2(
                        scaledToOriginalHomography.get(1, 0)[0],
                        scaledToOriginalHomography.get(0, 0)[0]
                ));

                try {
                    return new AlignmentResult(
                            shiftXPx,
                            shiftYPx,
                            calibrationService.pixelsToMillimeters(shiftXPx, pixelsToMm),
                            calibrationService.pixelsToMillimeters(shiftYPx, pixelsToMm),
                            rotationDeg,
                            scaledToOriginalHomography.clone()
                    );
                } finally {
                    scaledToOriginalHomography.release();
                }
            } finally {
                release(src, dst, inliersMask, homography);
            }
        } finally {
            release(curGray, curGrayScaled, curDescriptors);
            release(curKeypoints);
        }
    }

    private PreparedReference getOrBuildPreparedReference(Mat referenceRoi) {
        if (!referenceCacheEnabled) {
            return buildPreparedReference(referenceRoi);
        }
        long dataAddr = referenceRoi.dataAddr();
        int rows = referenceRoi.rows();
        int cols = referenceRoi.cols();
        PreparedReference cached = preparedReferenceCache;
        if (cached != null && cached.dataAddr == dataAddr && cached.rows == rows && cached.cols == cols) {
            return cached;
        }

        PreparedReference next = buildPreparedReference(referenceRoi);
        if (preparedReferenceCache != null) {
            preparedReferenceCache.descriptors.release();
        }
        preparedReferenceCache = next;
        return next;
    }

    private PreparedReference buildPreparedReference(Mat referenceRoi) {
        long dataAddr = referenceRoi.dataAddr();
        int rows = referenceRoi.rows();
        int cols = referenceRoi.cols();
        Mat refGray = new Mat();
        Mat refGrayScaled = null;
        Mat refDescriptors = new Mat();
        MatOfKeyPoint refKeypoints = new MatOfKeyPoint();
        try {
            Imgproc.cvtColor(referenceRoi, refGray, Imgproc.COLOR_BGR2GRAY);
            ResizeResult refResize = resizeForProcessing(refGray, MAX_ALIGNMENT_DIM);
            refGrayScaled = refResize.mat;
            detectAndComputeFeatures(refGrayScaled, refKeypoints, refDescriptors);
            if (refDescriptors.empty()) {
                throw new IllegalStateException("Unable to detect enough keypoints in reference ROI.");
            }

            Mat descriptorsClone = refDescriptors.clone();
            KeyPoint[] keypointsCopy = refKeypoints.toArray();
            PreparedReference next = new PreparedReference(
                    dataAddr,
                    rows,
                    cols,
                    refResize.scaleX,
                    refResize.scaleY,
                    descriptorsClone,
                    keypointsCopy
            );
            return next;
        } finally {
            release(refGray, refGrayScaled, refDescriptors);
            release(refKeypoints);
        }
    }

    private void detectAndComputeFeatures(Mat gray, MatOfKeyPoint keypoints, Mat descriptors) {
        if (optimizedOrb != null) {
            synchronized (optimizedOrb) {
                optimizedOrb.detectAndCompute(gray, new Mat(), keypoints, descriptors);
            }
            return;
        }
        ORB orb = ORB.create(ORB_FEATURES);
        orb.detectAndCompute(gray, new Mat(), keypoints, descriptors);
    }

    private List<DMatch> ratioTestMatches(Mat referenceDescriptors, Mat currentDescriptors) {
        List<MatOfDMatch> knn = new ArrayList<>();
        if (optimizedMatcher != null) {
            synchronized (optimizedMatcher) {
                optimizedMatcher.knnMatch(referenceDescriptors, currentDescriptors, knn, 2);
            }
        } else {
            BFMatcher matcher = BFMatcher.create(Core.NORM_HAMMING, false);
            matcher.knnMatch(referenceDescriptors, currentDescriptors, knn, 2);
        }

        List<DMatch> good = new ArrayList<>();
        for (MatOfDMatch candidates : knn) {
            DMatch[] values = candidates.toArray();
            if (values.length == 2 && values[0].distance < 0.75f * values[1].distance) {
                good.add(values[0]);
            }
            candidates.release();
        }
        return good;
    }

    private ConcentricityResult estimateConcentricity(Mat currentRoi, double pixelsToMm) {
        List<CircleCandidate> circles = detectCircles(currentRoi);
        double maxReliableDeviationPx = Math.max(6.0, 0.12 * Math.min(currentRoi.cols(), currentRoi.rows()));
        if (circles.isEmpty()) {
            Point roiCenter = new Point(currentRoi.cols() / 2.0, currentRoi.rows() / 2.0);
            return new ConcentricityResult(roiCenter, roiCenter, 0.0);
        }
        if (circles.size() == 1) {
            Point singleCenter = new Point(circles.get(0).x, circles.get(0).y);
            return new ConcentricityResult(singleCenter, singleCenter, 0.0);
        }

        CircleCandidate outerCircle = selectReferenceAnchor(circles, currentRoi);
        if (outerCircle == null) {
            Point roiCenter = new Point(currentRoi.cols() / 2.0, currentRoi.rows() / 2.0);
            return new ConcentricityResult(roiCenter, roiCenter, 0.0);
        }

        CircleCandidate innerCircle = selectInnerCircle(outerCircle, circles);
        if (innerCircle == null) {
            Point outerCenter = new Point(outerCircle.x, outerCircle.y);
            return new ConcentricityResult(outerCenter, outerCenter, 0.0);
        }

        Point outerCenter = new Point(outerCircle.x, outerCircle.y);
        Point innerCenter = new Point(innerCircle.x, innerCircle.y);
        return buildConcentricityResult(outerCenter, innerCenter, pixelsToMm, maxReliableDeviationPx);
    }

    private List<CircleCandidate> detectCircles(Mat roi) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(roi, gray, Imgproc.COLOR_BGR2GRAY);
            return detectCirclesWithParams(gray, roi, circleMaxDim, 100.0, 24.0, 0.08, 0.52);
        } finally {
            release(gray);
        }
    }

    private List<CircleCandidate> detectCirclesWithParams(
            Mat gray,
            Mat roi,
            int maxDim,
            double param1,
            double param2,
            double minRadiusRatio,
            double maxRadiusRatio
    ) {
        Mat grayScaled = null;
        Mat circles = new Mat();
        try {
            ResizeResult resized = resizeForProcessing(gray, maxDim);
            grayScaled = resized.mat;
            Imgproc.medianBlur(grayScaled, grayScaled, 5);
            Imgproc.HoughCircles(
                    grayScaled,
                    circles,
                    Imgproc.HOUGH_GRADIENT,
                    1.0,
                    Math.max(14.0, grayScaled.rows() / 8.0),
                    param1,
                    param2,
                    0,
                    0
            );

            List<CircleCandidate> detected = new ArrayList<>();
            if (circles.empty() || circles.cols() < 1) {
                return detected;
            }

            double minDim = Math.min(roi.cols(), roi.rows());
            double scaleRadius = (resized.scaleX + resized.scaleY) * 0.5;
            for (int i = 0; i < circles.cols(); i++) {
                double[] values = circles.get(0, i);
                double x = values[0] / resized.scaleX;
                double y = values[1] / resized.scaleY;
                double radius = values[2] / scaleRadius;
                if (radius < minRadiusRatio * minDim || radius > maxRadiusRatio * minDim) {
                    continue;
                }
                detected.add(new CircleCandidate(x, y, radius));
            }
            return detected;
        } finally {
            release(grayScaled, circles);
        }
    }

    private ResizeResult resizeForProcessing(Mat src, int maxDim) {
        int maxSide = Math.max(src.cols(), src.rows());
        if (maxSide <= maxDim) {
            return new ResizeResult(src.clone(), 1.0, 1.0);
        }

        double scale = maxDim / (double) maxSide;
        int targetWidth = Math.max(1, (int) Math.round(src.cols() * scale));
        int targetHeight = Math.max(1, (int) Math.round(src.rows() * scale));
        Mat resized = new Mat();
        Imgproc.resize(src, resized, new Size(targetWidth, targetHeight), 0, 0, Imgproc.INTER_AREA);
        double scaleX = targetWidth / (double) src.cols();
        double scaleY = targetHeight / (double) src.rows();
        return new ResizeResult(resized, scaleX, scaleY);
    }

    private Mat toOriginalScaleHomography(Mat scaledHomography, double scaleX, double scaleY) {
        if (Math.abs(scaleX - 1.0) < 1e-9 && Math.abs(scaleY - 1.0) < 1e-9) {
            return scaledHomography.clone();
        }

        Mat scaleToSmall = Mat.eye(3, 3, CvType.CV_64F);
        Mat scaleToOriginal = Mat.eye(3, 3, CvType.CV_64F);
        Mat empty = new Mat();
        Mat tmp = new Mat();
        Mat result = new Mat();
        try {
            scaleToSmall.put(0, 0, scaleX);
            scaleToSmall.put(1, 1, scaleY);
            scaleToOriginal.put(0, 0, 1.0 / scaleX);
            scaleToOriginal.put(1, 1, 1.0 / scaleY);
            Core.gemm(scaledHomography, scaleToSmall, 1.0, empty, 0.0, tmp);
            Core.gemm(scaleToOriginal, tmp, 1.0, empty, 0.0, result);
            return result;
        } finally {
            release(scaleToSmall, scaleToOriginal, empty, tmp);
        }
    }

    private CircleCandidate selectReferenceAnchor(List<CircleCandidate> circles, Mat roi) {
        Point roiCenter = new Point(roi.cols() / 2.0, roi.rows() / 2.0);
        double bestScore = Double.MAX_VALUE;
        CircleCandidate best = null;

        for (CircleCandidate candidate : circles) {
            double centerPrior = Math.hypot(candidate.x - roiCenter.x, candidate.y - roiCenter.y);
            double score = 1.2 * centerPrior - 2.0 * candidate.radiusPx;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private CircleCandidate selectInnerCircle(CircleCandidate outerCircle, List<CircleCandidate> circles) {
        double bestScore = Double.MAX_VALUE;
        CircleCandidate best = null;

        for (CircleCandidate candidate : circles) {
            if (candidate == outerCircle) {
                continue;
            }
            double radiusRatio = candidate.radiusPx / outerCircle.radiusPx;
            if (radiusRatio < 0.35 || radiusRatio > 0.90) {
                continue;
            }
            double centerDistance = Math.hypot(outerCircle.x - candidate.x, outerCircle.y - candidate.y);
            if (centerDistance > Math.max(6.0, 0.25 * outerCircle.radiusPx)) {
                continue;
            }
            double score = centerDistance + 1.5 * Math.abs(outerCircle.radiusPx - candidate.radiusPx);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private double normalizeDeviationMm(double deviationMm, double pixelsToMm) {
        double epsilonMm = calibrationService.pixelsToMillimeters(2.0, pixelsToMm);
        return deviationMm <= epsilonMm ? 0.0 : deviationMm;
    }

    private ConcentricityResult buildConcentricityResult(
            Point referenceCenter,
            Point currentCenter,
            double pixelsToMm,
            double maxReliableDeviationPx
    ) {
        double deviationPx = Math.hypot(referenceCenter.x - currentCenter.x, referenceCenter.y - currentCenter.y);
        if (deviationPx > maxReliableDeviationPx) {
            return new ConcentricityResult(referenceCenter, referenceCenter, 0.0);
        }
        double deviationMm = calibrationService.pixelsToMillimeters(deviationPx, pixelsToMm);
        return new ConcentricityResult(referenceCenter, currentCenter, normalizeDeviationMm(deviationMm, pixelsToMm));
    }

    private Mat warpCurrentToReference(Mat current, Mat localHomographyRefToCurrent, Rect mainRect) {
        Mat globalHomography = null;
        Mat inverseGlobalHomography = new Mat();
        Mat aligned = new Mat();
        try {
            globalHomography = toGlobalHomography(localHomographyRefToCurrent, mainRect);
            Core.invert(globalHomography, inverseGlobalHomography);
            Imgproc.warpPerspective(
                    current,
                    aligned,
                    inverseGlobalHomography,
                    current.size(),
                    Imgproc.INTER_LINEAR,
                    Core.BORDER_REPLICATE
            );
            return aligned;
        } finally {
            release(globalHomography, inverseGlobalHomography);
        }
    }

    private Mat toGlobalHomography(Mat localHomographyRefToCurrent, Rect mainRect) {
        Mat translateToLocal = Mat.eye(3, 3, CvType.CV_64F);
        Mat translateToGlobal = Mat.eye(3, 3, CvType.CV_64F);
        Mat empty = new Mat();
        Mat tmp = new Mat();
        Mat global = new Mat();
        try {
            translateToLocal.put(0, 2, -mainRect.x);
            translateToLocal.put(1, 2, -mainRect.y);
            translateToGlobal.put(0, 2, mainRect.x);
            translateToGlobal.put(1, 2, mainRect.y);

            Core.gemm(localHomographyRefToCurrent, translateToLocal, 1.0, empty, 0.0, tmp);
            Core.gemm(translateToGlobal, tmp, 1.0, empty, 0.0, global);
            return global;
        } finally {
            release(translateToLocal, translateToGlobal, empty, tmp);
        }
    }

    private JointResult inspectJoint(Mat current, RoiRect jointRoi, double pixelsToMm) {
        if (jointRoi == null) {
            return new JointResult(0.0);
        }

        Rect roi = toSafeRect(jointRoi, current.cols(), current.rows());
        Mat jointMat = new Mat(current, roi);
        Mat gray = new Mat();
        Mat edges = new Mat();
        Mat closedEdges = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        try {
            Imgproc.cvtColor(jointMat, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.Canny(gray, edges, 50.0, 150.0);
            Imgproc.morphologyEx(edges, closedEdges, Imgproc.MORPH_CLOSE, kernel);

            int edgePixels = Core.countNonZero(closedEdges);
            double density = edgePixels / (double) (closedEdges.cols() * closedEdges.rows());
            double defectPx = Math.max(0.0, (0.10 - density) * closedEdges.cols());
            double defectMm = calibrationService.pixelsToMillimeters(defectPx, pixelsToMm);

            return new JointResult(defectMm);
        } finally {
            release(jointMat, gray, edges, closedEdges, kernel);
        }
    }

    private WrinklesResult inspectWrinkles(Mat reference, Mat current, RoiRect wrinklesRoi) {
        if (wrinklesRoi == null) {
            return new WrinklesResult(0.0);
        }

        Rect roi = toSafeRect(wrinklesRoi, current.cols(), current.rows());
        Mat referencePart = new Mat(reference, roi);
        Mat currentPart = new Mat(current, roi);
        Mat refGray = new Mat();
        Mat curGray = new Mat();
        Mat diff = new Mat();
        Mat binary = new Mat();

        try {
            Imgproc.cvtColor(referencePart, refGray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.cvtColor(currentPart, curGray, Imgproc.COLOR_BGR2GRAY);
            Core.absdiff(refGray, curGray, diff);
            Imgproc.GaussianBlur(diff, diff, new Size(5, 5), 0);
            Imgproc.threshold(diff, binary, 25, 255, Imgproc.THRESH_BINARY);

            double score = Core.countNonZero(binary) / (double) (binary.cols() * binary.rows());
            return new WrinklesResult(score);
        } finally {
            release(referencePart, currentPart, refGray, curGray, diff, binary);
        }
    }

    private void drawDebug(
            Mat debugFrame,
            Rect mainRect,
            AlignmentResult alignment,
            ConcentricityResult concentricity,
            InspectionRequest request
    ) {
        Imgproc.rectangle(debugFrame, mainRect, new Scalar(0, 255, 0), 2);

        Point center = new Point(mainRect.x + mainRect.width / 2.0, mainRect.y + mainRect.height / 2.0);
        Point shifted = new Point(center.x + alignment.shiftXPx, center.y + alignment.shiftYPx);
        Imgproc.arrowedLine(debugFrame, center, shifted, new Scalar(0, 0, 255), 3);

        Imgproc.putText(
                debugFrame,
                String.format("dx=%.2fmm dy=%.2fmm rot=%.2fdeg", alignment.shiftXmm, alignment.shiftYmm, alignment.rotationDeg),
                new Point(mainRect.x, Math.max(25, mainRect.y - 8)),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.7,
                new Scalar(255, 255, 0),
                2
        );

        if (concentricity.capCenter != null && concentricity.labelCenter != null) {
            Point cap = shiftPoint(concentricity.capCenter, mainRect);
            Point label = shiftPoint(concentricity.labelCenter, mainRect);
            Imgproc.circle(debugFrame, cap, 6, new Scalar(255, 0, 0), 2);
            Imgproc.circle(debugFrame, label, 6, new Scalar(0, 255, 255), 2);
            Imgproc.line(debugFrame, cap, label, new Scalar(255, 0, 255), 2);
        }

        if (request.jointRoi() != null) {
            Rect r = toSafeRect(request.jointRoi(), debugFrame.cols(), debugFrame.rows());
            Imgproc.rectangle(debugFrame, r, new Scalar(0, 165, 255), 2);
        }

        if (request.wrinklesRoi() != null) {
            Rect r = toSafeRect(request.wrinklesRoi(), debugFrame.cols(), debugFrame.rows());
            Imgproc.rectangle(debugFrame, r, new Scalar(255, 255, 255), 2);
        }
    }

    private Point shiftPoint(Point roiPoint, Rect roiRect) {
        return new Point(roiPoint.x + roiRect.x, roiPoint.y + roiRect.y);
    }

    private Rect toSafeRect(RoiRect roi, int maxWidth, int maxHeight) {
        int x = Math.max(0, roi.x());
        int y = Math.max(0, roi.y());
        int width = Math.min(roi.width(), maxWidth - x);
        int height = Math.min(roi.height(), maxHeight - y);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("ROI goes outside image boundaries.");
        }
        return new Rect(x, y, width, height);
    }

    private void validateInputFrames(Mat reference, Mat current) {
        if (reference.cols() != current.cols() || reference.rows() != current.rows()) {
            throw new IllegalArgumentException("Reference and current frame dimensions must match.");
        }
        if (reference.cols() != EXPECTED_WIDTH || reference.rows() != EXPECTED_HEIGHT) {
            throw new IllegalArgumentException("Expected frame resolution is 2448x2048.");
        }
    }

    private void release(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) {
                mat.release();
            }
        }
    }

    private void release(MatOfKeyPoint... keypointMats) {
        for (MatOfKeyPoint keypointMat : keypointMats) {
            if (keypointMat != null) {
                keypointMat.release();
            }
        }
    }

    private record AlignmentResult(
            double shiftXPx,
            double shiftYPx,
            double shiftXmm,
            double shiftYmm,
            double rotationDeg,
            Mat homographyRefToCurrent
    ) {
    }

    private record ConcentricityResult(Point capCenter, Point labelCenter, double deviationMm) {
    }

    private record CircleCandidate(double x, double y, double radiusPx) {
    }

    private record ResizeResult(Mat mat, double scaleX, double scaleY) {
    }

    private static final class PreparedReference {
        final long dataAddr;
        final int rows;
        final int cols;
        final double scaleX;
        final double scaleY;
        final Mat descriptors;
        final KeyPoint[] keypoints;

        private PreparedReference(long dataAddr, int rows, int cols, double scaleX, double scaleY, Mat descriptors, KeyPoint[] keypoints) {
            this.dataAddr = dataAddr;
            this.rows = rows;
            this.cols = cols;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.descriptors = descriptors;
            this.keypoints = keypoints;
        }
    }

    private record JointResult(double defectMm) {
    }

    private record WrinklesResult(double score) {
    }

    @FunctionalInterface
    public interface StageTimingSink {
        void record(String stage, long durationNs);
    }
}

package com.example.iml.service;

import com.example.iml.dto.InspectionRequest;
import com.example.iml.dto.RoiRect;
import com.example.iml.opencv.OpenCvNativeLoader;
import com.example.iml.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class GeometryRunnerMain {

    private static final Logger log = LogManager.getLogger(GeometryRunnerMain.class);
    private static String cachedReferenceKey;
    private static Mat cachedReferenceMat;
    private static byte[] shmReadBuffer = new byte[0];
    private static byte[] shmRowBuffer = new byte[0];

    private GeometryRunnerMain() {
    }

    public static void main(String[] args) throws Exception {
        OpenCvNativeLoader.ensureLoaded();
        GeometryAnalysisService service = new OpenCvGeometryAnalysisService(
                new OpenCvImageCodec(),
                new CalibrationService(),
                false,
                true
        );

        DataInputStream in = new DataInputStream(System.in);
        DataOutputStream out = new DataOutputStream(System.out);

        while (true) {
            BinaryProtocol.Message msg;
            try {
                msg = BinaryProtocol.read(in);
            } catch (Exception e) {
                return;
            }

            if (msg.type() != BinaryProtocol.MSG_COMMAND) {
                BinaryProtocol.write(out, BinaryProtocol.MSG_ERROR, Map.of("error", "unexpected message type"), new byte[0]);
                continue;
            }

            String op = String.valueOf(msg.header().getOrDefault("op", ""));
            try {
                switch (op) {
                    case "health" -> BinaryProtocol.write(
                            out,
                            BinaryProtocol.MSG_RESPONSE,
                            Map.of("status", "ok", "service", "java-geometry-service"),
                            new byte[0]
                    );
                    case "inspect_stub" -> BinaryProtocol.write(
                            out,
                            BinaryProtocol.MSG_RESPONSE,
                            Map.of(
                                    "camera_id", msg.header().get("camera_id"),
                                    "frame_id", msg.header().get("frame_id"),
                                    "status", "PASS",
                                    "alignmentPass", true,
                                    "overallPass", true
                            ),
                            new byte[0]
                    );
                    case "inspect" -> {
                        boolean includeDebug = bool(msg.header().get("includeDebugImage"), false);
                        InspectionRequest request = mapRequest(msg.header());
                        var response = ((OpenCvGeometryAnalysisService) service).inspect(request, includeDebug);
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("shiftXmm", response.shiftXmm());
                        payload.put("shiftYmm", response.shiftYmm());
                        payload.put("rotationDeg", response.rotationDeg());
                        payload.put("homographyRefToCurrent", response.homographyRefToCurrent());
                        payload.put("concentricityMm", response.concentricityMm());
                        payload.put("jointDefectMm", response.jointDefectMm());
                        payload.put("wrinklesScore", response.wrinklesScore());
                        payload.put("alignmentPass", response.alignmentPass());
                        payload.put("concentricityPass", response.concentricityPass());
                        payload.put("jointPass", response.jointPass());
                        payload.put("wrinklesPass", response.wrinklesPass());
                        payload.put("overallPass", response.overallPass());
                        payload.put("status", response.status());
                        if (includeDebug) {
                            payload.put("debugImageBase64", response.debugImageBase64());
                        }
                        BinaryProtocol.write(out, BinaryProtocol.MSG_RESPONSE, payload, new byte[0]);
                    }
                    case "inspect_shm" -> {
                        boolean includeDebug = bool(msg.header().get("includeDebugImage"), false);
                        Mat current = null;
                        Mat reference = null;
                        boolean releaseReference = false;
                        try {
                            current = readShmMat(msg.header());
                            ReferenceMatResult referenceResult = readReferenceShmOrCurrentMat(msg.header(), current);
                            reference = referenceResult.mat;
                            releaseReference = referenceResult.releaseAfterUse;
                            InspectionRequest request = mapShmMetadataRequest(msg.header());
                            var response = ((OpenCvGeometryAnalysisService) service).inspectMats(reference, current, request, includeDebug);
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("shiftXmm", response.shiftXmm());
                            payload.put("shiftYmm", response.shiftYmm());
                            payload.put("rotationDeg", response.rotationDeg());
                            payload.put("homographyRefToCurrent", response.homographyRefToCurrent());
                            payload.put("concentricityMm", response.concentricityMm());
                            payload.put("jointDefectMm", response.jointDefectMm());
                            payload.put("wrinklesScore", response.wrinklesScore());
                            payload.put("alignmentPass", response.alignmentPass());
                            payload.put("concentricityPass", response.concentricityPass());
                            payload.put("jointPass", response.jointPass());
                            payload.put("wrinklesPass", response.wrinklesPass());
                            payload.put("overallPass", response.overallPass());
                            payload.put("status", response.status());
                            if (includeDebug) {
                                payload.put("debugImageBase64", response.debugImageBase64());
                            }
                            BinaryProtocol.write(out, BinaryProtocol.MSG_RESPONSE, payload, new byte[0]);
                        } finally {
                            if (current != null) {
                                current.release();
                            }
                            if (releaseReference && reference != null && reference != current) {
                                reference.release();
                            }
                        }
                    }
                    case "inject_exit" -> {
                        out.flush();
                        System.exit(42);
                    }
                    case "inject_timeout_ms" -> {
                        int timeoutMs = (int) num(msg.header().get("timeout_ms"), 1000);
                        Thread.sleep(Math.max(0, timeoutMs));
                        BinaryProtocol.write(out, BinaryProtocol.MSG_RESPONSE, Map.of("status", "timeout_injected"), new byte[0]);
                    }
                    case "inject_broken_response" -> {
                        out.write("BROKEN_RESPONSE".getBytes());
                        out.flush();
                    }
                    default -> BinaryProtocol.write(
                            out,
                            BinaryProtocol.MSG_ERROR,
                            Map.of("error", "unknown op", "op", op),
                            new byte[0]
                    );
                }
            } catch (Exception e) {
                log.error("Geometry op failed: {}", op, e);
                BinaryProtocol.write(
                        out,
                        BinaryProtocol.MSG_ERROR,
                        Map.of("error", e.getMessage(), "op", op),
                        new byte[0]
                );
            }
        }
    }

    private static InspectionRequest mapRequest(Map<String, Object> h) {
        return new InspectionRequest(
                str(h.get("referenceImageBase64")),
                str(h.get("currentImageBase64")),
                roi(h.get("mainRoi")),
                roiOrNull(h.get("jointRoi")),
                roiOrNull(h.get("wrinklesRoi")),
                num(h.get("pixelsToMm"), 0.01),
                num(h.get("maxShiftMm"), 0.5),
                num(h.get("maxRotationDeg"), 1.0),
                num(h.get("maxConcentricityMm"), 0.2),
                num(h.get("maxJointDefectMm"), 0.3),
                num(h.get("maxWrinklesScore"), 0.05)
        );
    }

    private static InspectionRequest mapShmMetadataRequest(Map<String, Object> h) {
        return new InspectionRequest(
                "",
                "",
                roiOrDefault(h.get("mainRoi")),
                roiOrNull(h.get("jointRoi")),
                roiOrNull(h.get("wrinklesRoi")),
                num(h.get("pixelsToMm"), 0.01),
                num(h.get("maxShiftMm"), 0.5),
                num(h.get("maxRotationDeg"), 1.0),
                num(h.get("maxConcentricityMm"), 0.2),
                num(h.get("maxJointDefectMm"), 0.3),
                num(h.get("maxWrinklesScore"), 0.05)
        );
    }

    private static ReferenceMatResult readReferenceShmOrCurrentMat(Map<String, Object> h, Mat current) {
        if (h.get("reference_shm_name") == null) {
            return new ReferenceMatResult(current, false);
        }
        String key = buildReferenceKey(h);
        if (key.equals(cachedReferenceKey) && cachedReferenceMat != null) {
            return new ReferenceMatResult(cachedReferenceMat, false);
        }

        Map<String, Object> refHeader = new HashMap<>();
        refHeader.put("shm_name", h.get("reference_shm_name"));
        refHeader.put("shm_offset", h.get("reference_shm_offset"));
        refHeader.put("width", h.get("reference_width"));
        refHeader.put("height", h.get("reference_height"));
        refHeader.put("stride", h.get("reference_stride"));
        Mat loaded = readShmMat(refHeader);
        if (cachedReferenceMat != null) {
            cachedReferenceMat.release();
        }
        cachedReferenceMat = loaded;
        cachedReferenceKey = key;
        return new ReferenceMatResult(cachedReferenceMat, false);
    }

    private static String buildReferenceKey(Map<String, Object> h) {
        return str(h.get("reference_shm_name"))
                + "|" + (int) num(h.get("reference_shm_offset"), 0)
                + "|" + (int) num(h.get("reference_width"), 0)
                + "|" + (int) num(h.get("reference_height"), 0)
                + "|" + (int) num(h.get("reference_stride"), 0);
    }

    private static Mat readShmMat(Map<String, Object> h) {
        String shmName = str(h.get("shm_name"));
        int width = (int) num(h.get("width"), 2448);
        int height = (int) num(h.get("height"), 2048);
        int stride = (int) num(h.get("stride"), width * 3);
        int offset = (int) num(h.get("shm_offset"), 0);
        Path shmPath = Path.of("/dev/shm", shmName.startsWith("/") ? shmName.substring(1) : shmName);

        try (RandomAccessFile raf = new RandomAccessFile(shmPath.toFile(), "r");
             FileChannel channel = raf.getChannel()) {
            MappedByteBuffer mb = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            Mat mat = new Mat(height, width, CvType.CV_8UC3);
            try {
                int expected = width * 3;
                if (stride == expected) {
                    byte[] all = ensureShmReadBuffer(height * stride);
                    mb.position(offset);
                    mb.get(all);
                    mat.put(0, 0, all);
                } else {
                    byte[] row = ensureShmRowBuffer(expected);
                    for (int y = 0; y < height; y++) {
                        mb.position(offset + y * stride);
                        mb.get(row);
                        mat.put(y, 0, row);
                    }
                }
                return mat;
            } catch (Exception ex) {
                mat.release();
                throw ex;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read shm frame: " + shmPath, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static RoiRect roi(Object o) {
        Map<String, Object> m = (Map<String, Object>) o;
        return new RoiRect(
                (int) num(m.get("x"), 0),
                (int) num(m.get("y"), 0),
                (int) num(m.get("width"), 1),
                (int) num(m.get("height"), 1)
        );
    }

    private static RoiRect roiOrNull(Object o) {
        if (o == null) {
            return null;
        }
        return roi(o);
    }

    private static RoiRect roiOrDefault(Object o) {
        if (o == null) {
            return new RoiRect(0, 0, 2448, 2048);
        }
        return roi(o);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static double num(Object o, double fallback) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return fallback;
        }
        return Double.parseDouble(String.valueOf(o));
    }

    private static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static byte[] ensureShmReadBuffer(int required) {
        if (shmReadBuffer.length < required) {
            shmReadBuffer = new byte[required];
        }
        return shmReadBuffer;
    }

    private static byte[] ensureShmRowBuffer(int required) {
        if (shmRowBuffer.length < required) {
            shmRowBuffer = new byte[required];
        }
        return shmRowBuffer;
    }

    private static final class ReferenceMatResult {
        private final Mat mat;
        private final boolean releaseAfterUse;

        private ReferenceMatResult(Mat mat, boolean releaseAfterUse) {
            this.mat = mat;
            this.releaseAfterUse = releaseAfterUse;
        }
    }
}

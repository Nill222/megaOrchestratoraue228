package com.example.iml.orchestrator.integration;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class IntegrationBootstrap {

    private static final Logger log = LogManager.getLogger(IntegrationBootstrap.class);

    public void start(Map<String, Object> root, Path projectRoot) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cameras = (List<Map<String, Object>>) root.get("cameras");
        if (cameras == null || cameras.isEmpty()) {
            log.warn("No cameras in config; integration pipeline skipped");
            return;
        }

        Path workerBin = projectRoot.resolve("camera-worker/build/camera_worker");
        if (!workerBin.toFile().exists()) {
            log.warn("camera-worker binary not found at {}; skip live integration", workerBin);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> integration = (Map<String, Object>) root.get("integration");
        WorkerIpcMode workerIpcMode = WorkerIpcMode.fromConfig(integration == null ? null : integration.get("worker_ipc_mode"));
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String defaultPipeTemplate = isWindows ? "\\\\.\\pipe\\iml-camera-%d-binary" : "/tmp/iml-camera-%d.pipe";
        String workerPipeTemplate = String.valueOf(integration == null
                ? defaultPipeTemplate
                : integration.getOrDefault(
                        isWindows ? "worker_named_pipe_template" : "worker_named_pipe_template_linux",
                        integration.getOrDefault("worker_named_pipe_template", defaultPipeTemplate)));
        int workerPipeConnectTimeoutMs = toInt(integration == null ? null : integration.get("worker_named_pipe_connect_timeout_ms"), 3000);
        int workerCommandTimeoutMs = toInt(integration == null ? null : integration.get("worker_command_timeout_ms"), 5000);
        int serviceCommandTimeoutMs = toInt(integration == null ? null : integration.get("service_command_timeout_ms"), 7000);
        int lightStartupDelayMs = toInt(integration == null ? null : integration.get("light_server_startup_delay_ms"), 1200);
        int cameraParallelism = Math.max(1, toInt(integration == null ? null : integration.get("camera_parallelism"), Math.min(5, cameras.size())));
        int geometryPoolSize = Math.max(1, toInt(integration == null ? null : integration.get("geometry_pool_size"), 2));
        boolean reloadReference = toBool(integration == null ? null : integration.get("reload_reference"), false);
        int pythonParallelism = Math.max(1, toInt(integration == null ? null : integration.get("python_parallelism"), Math.min(cameraParallelism, 2)));
        List<ServiceProcessSupervisor> pythonPool = startOptionalServicePool(
                integration,
                "python_command_linux",
                projectRoot,
                "python-detectors",
                serviceCommandTimeoutMs,
                pythonParallelism
        );
        List<ServiceProcessSupervisor> geometryPool = startOptionalServicePool(
                integration,
                "geometry_command_linux",
                projectRoot,
                "java-geometry",
                serviceCommandTimeoutMs,
                geometryPoolSize
        );
        ExternalServiceProcess lightServerProcess = startOptionalExternalProcess(integration, projectRoot, "light-server", isWindows, lightStartupDelayMs);
        @SuppressWarnings("unchecked")
        Map<String, Object> pythonCfg = (Map<String, Object>) root.get("python_detector");
        @SuppressWarnings("unchecked")
        Map<String, Object> geometryCfg = (Map<String, Object>) root.get("java_geometry");
        @SuppressWarnings("unchecked")
        Map<String, Object> uiCfg = (Map<String, Object>) root.get("ui_http");
        @SuppressWarnings("unchecked")
        Map<String, Object> lightCfg = (Map<String, Object>) root.get("light_server");
        LightTriggerClient lightClient = buildLightClient(lightCfg);

        final UiHttpServer uiServer = startUiServerIfEnabled(uiCfg);
        final ServiceProcessSupervisor uiVisualsPython = startUiVisualsPythonIfEnabled(
                integration,
                uiCfg,
                projectRoot,
                serviceCommandTimeoutMs
        );
        final ExecutorService uiArtifactsExecutor = startUiArtifactsExecutorIfEnabled(uiCfg);
        FanOutCoordinator fanOut = null;
        ExecutorService cameraExecutor = null;
        ExecutorService captureStageExecutor = null;
        ExecutorService pythonStageExecutor = null;
        ExecutorService geometryStageExecutor = null;
        ExecutorService decisionStageExecutor = null;
        Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
        Map<Integer, ReferenceSnapshot> referenceByCamera = new ConcurrentHashMap<>();
        try {
            FanOutCoordinator activeFanOut = FanOutCoordinator.fromConfig(root);
            fanOut = activeFanOut;
            log.info("integration parallel settings: camera_parallelism={} geometry_pool_size={}", cameraParallelism, geometryPool.size());
            for (Map<String, Object> camera : cameras) {
                int cameraId = ((Number) camera.get("id")).intValue();
                List<String> cmd = new ArrayList<>();
                cmd.add(workerBin.toString());
                cmd.add(projectRoot.resolve("config/config.json").toString());
                cmd.add(String.valueOf(cameraId));
                if (workerIpcMode == WorkerIpcMode.STDIO) {
                    cmd.add("--binary-stdio");
                } else {
                    cmd.add("--named-pipe");
                    cmd.add(String.format(workerPipeTemplate, cameraId));
                }
                String workerPipePath = String.format(workerPipeTemplate, cameraId);
                WorkerProcessSupervisor worker = new WorkerProcessSupervisor(
                        cameraId, cmd, projectRoot, workerIpcMode, workerPipePath, workerPipeConnectTimeoutMs, workerCommandTimeoutMs);
                worker.start();
                BinaryProtocol.Message health = worker.health();
                log.info("worker cam={} health type={} header={}", cameraId, health.type(), health.header());
                workersByCamera.put(cameraId, worker);
            }
            cameraExecutor = Executors.newFixedThreadPool(cameraParallelism, r -> {
                Thread t = new Thread(r, "camera-flow");
                t.setDaemon(true);
                return t;
            });
            int stageQueueSize = Math.max(4, toInt(integration == null ? null : integration.get("stage_queue_size"), cameraParallelism * 2));
            captureStageExecutor = newStageExecutor("stage-capture", cameraParallelism, stageQueueSize);
            pythonStageExecutor = newStageExecutor("stage-python", pythonParallelism, stageQueueSize);
            geometryStageExecutor = newStageExecutor("stage-geometry", Math.max(1, geometryPool.size()), stageQueueSize);
            decisionStageExecutor = newStageExecutor("stage-decision", cameraParallelism, stageQueueSize);
            ExecutorService activeCaptureStageExecutor = captureStageExecutor;
            ExecutorService activePythonStageExecutor = pythonStageExecutor;
            ExecutorService activeGeometryStageExecutor = geometryStageExecutor;
            ExecutorService activeDecisionStageExecutor = decisionStageExecutor;
            log.info("pipeline settings: queue_size={} python_parallelism={}", stageQueueSize, pythonParallelism);
            Semaphore geometrySlots = new Semaphore(Math.max(1, geometryPool.size()));
            Semaphore pythonSlots = new Semaphore(Math.max(1, pythonPool.size()));
            AtomicInteger geometryRoundRobin = new AtomicInteger(0);
            AtomicInteger pythonRoundRobin = new AtomicInteger(0);
            List<Callable<Void>> tasks = new ArrayList<>();
            for (Map<String, Object> camera : cameras) {
                tasks.add(() -> {
                    int cameraId = ((Number) camera.get("id")).intValue();
                    WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
                    if (worker == null) {
                        throw new IllegalStateException("worker not initialized for camera " + cameraId);
                    }
                    processCamera(
                            camera,
                            worker,
                            pythonPool,
                            geometryPool,
                            lightClient,
                            pythonCfg,
                            geometryCfg,
                            activeFanOut,
                            geometrySlots,
                            pythonSlots,
                            geometryRoundRobin,
                            pythonRoundRobin,
                            referenceByCamera,
                            reloadReference,
                            activeCaptureStageExecutor,
                            activePythonStageExecutor,
                            activeGeometryStageExecutor,
                            activeDecisionStageExecutor,
                            uiCfg,
                            uiServer,
                            uiVisualsPython,
                            uiArtifactsExecutor
                    );
                    return null;
                });
            }
            List<Future<Void>> futures = cameraExecutor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            log.error("Integration bootstrap failed", e);
        } finally {
            if (cameraExecutor != null) {
                cameraExecutor.shutdownNow();
            }
            shutdownQuietly(captureStageExecutor);
            shutdownQuietly(pythonStageExecutor);
            shutdownQuietly(geometryStageExecutor);
            shutdownQuietly(decisionStageExecutor);
            for (Map.Entry<Integer, WorkerProcessSupervisor> entry : workersByCamera.entrySet()) {
                try {
                    log.info("worker supervisor camera={} restarts={}", entry.getKey(), entry.getValue().restartCount());
                    entry.getValue().close();
                } catch (Exception ignored) {
                }
            }
            for (ServiceProcessSupervisor python : pythonPool) {
                if (python != null) {
                    log.info("{} supervisor restarts={}", python.name(), python.restartCount());
                    python.close();
                }
            }
            for (ServiceProcessSupervisor geometry : geometryPool) {
                if (geometry != null) {
                    log.info("{} supervisor restarts={}", geometry.name(), geometry.restartCount());
                    geometry.close();
                }
            }
            if (lightServerProcess != null) {
                lightServerProcess.close();
            }
            if (uiVisualsPython != null) {
                log.info("{} supervisor restarts={}", uiVisualsPython.name(), uiVisualsPython.restartCount());
                uiVisualsPython.close();
            }
            shutdownQuietly(uiArtifactsExecutor);
            if (fanOut != null) {
                log.info("fanout metrics: {}", fanOut.metricsSummary());
                fanOut.close();
            }
            if (uiServer != null) {
                uiServer.close();
            }
        }
    }

    private InspectionDecision aggregateDecision(
            int cameraId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message pyResp,
            BinaryProtocol.Message geomResp
    ) {
        long frameId = toLong(capture.header().get("frame_id"), -1L);
        double anomalyScore = pyResp == null ? 0.0 : toDouble(pyResp.header().get("anomaly_score"), 0.0);
        String pyStatus = pyResp == null ? "UNKNOWN" : String.valueOf(pyResp.header().getOrDefault("status", "UNKNOWN"));
        boolean pythonPass = pyResp == null || pyResp.type() != BinaryProtocol.MSG_ERROR;
        boolean geometryPass = geomResp == null || (geomResp.type() != BinaryProtocol.MSG_ERROR && Boolean.TRUE.equals(geomResp.header().get("overallPass")));
        String geometryStatus = geomResp == null ? "UNKNOWN" : String.valueOf(geomResp.header().getOrDefault("status", "PASS"));

        boolean overallPass = pythonPass
                && geometryPass
                && !("БРАК".equalsIgnoreCase(pyStatus) || "FAIL".equalsIgnoreCase(pyStatus));
        String action = overallPass ? "ACCEPT" : "REJECT";
        InspectionDecision decision = new InspectionDecision(cameraId, frameId, overallPass, action, anomalyScore, pyStatus, geometryStatus);
        if (log.isDebugEnabled()) {
            log.debug("decision cam={} frame={} overall={} action={} pyStatus={} geomStatus={} score={}",
                    decision.cameraId(),
                    decision.frameId(),
                    decision.overallPass(),
                    decision.action(),
                    decision.pythonStatus(),
                    decision.geometryStatus(),
                    decision.anomalyScore());
        }
        return decision;
    }

    private void processCamera(
            Map<String, Object> camera,
            WorkerProcessSupervisor worker,
            List<ServiceProcessSupervisor> pythonPool,
            List<ServiceProcessSupervisor> geometryPool,
            LightTriggerClient lightClient,
            Map<String, Object> pythonCfg,
            Map<String, Object> geometryCfg,
            FanOutCoordinator fanOut,
            Semaphore geometrySlots,
            Semaphore pythonSlots,
            AtomicInteger geometryRoundRobin,
            AtomicInteger pythonRoundRobin,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            boolean reloadReferenceGlobal,
            ExecutorService captureStageExecutor,
            ExecutorService pythonStageExecutor,
            ExecutorService geometryStageExecutor,
            ExecutorService decisionStageExecutor,
            Map<String, Object> uiCfg,
            UiHttpServer uiServer,
            ServiceProcessSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor
    ) throws Exception {
        int cameraId = ((Number) camera.get("id")).intValue();
        String productType = String.valueOf(camera.getOrDefault("product_type", "camera-" + cameraId));
        String detectorId = String.valueOf(camera.getOrDefault("detector", "v1"));
        boolean reloadReferenceLocal = toBool(camera.get("reload_reference"), false);
        long tCameraStart = System.nanoTime();
        long referenceMs = 0L;

        ReferenceSnapshot referenceSnapshot = referenceByCamera.get(cameraId);
        boolean needReference = referenceSnapshot == null
                || !productType.equals(referenceSnapshot.productType())
                || reloadReferenceGlobal
                || reloadReferenceLocal;
        if (needReference) {
            long tRef0 = System.nanoTime();
            lightClient.trigger(cameraId, -1, "reference");
            BinaryProtocol.Message referenceCapture = worker.command(Map.of("op", "capture"));
            log.info("worker cam={} reference capture header={}", cameraId, referenceCapture.header());
            referenceSnapshot = new ReferenceSnapshot(productType, Map.copyOf(referenceCapture.header()));
            referenceByCamera.put(cameraId, referenceSnapshot);

            for (ServiceProcessSupervisor python : pythonPool) {
                python.command(Map.of(
                        "op", "set_reference_shm",
                        "product_type", productType,
                        "detector_id", detectorId,
                        "shm_name", referenceCapture.header().get("shm_name"),
                        "shm_offset", referenceCapture.header().get("shm_offset"),
                        "width", referenceCapture.header().get("width"),
                        "height", referenceCapture.header().get("height"),
                        "stride", referenceCapture.header().get("stride")
                ));
            }
            if (uiVisualsPython != null) {
                uiVisualsPython.command(Map.of(
                        "op", "set_reference_shm",
                        "product_type", productType,
                        "detector_id", detectorId,
                        "shm_name", referenceCapture.header().get("shm_name"),
                        "shm_offset", referenceCapture.header().get("shm_offset"),
                        "width", referenceCapture.header().get("width"),
                        "height", referenceCapture.header().get("height"),
                        "stride", referenceCapture.header().get("stride")
                ));
            }
            referenceMs = nanosToMs(System.nanoTime() - tRef0);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("worker cam={} reuse reference product_type={} frame_id={}",
                        cameraId, productType, referenceSnapshot.header().get("frame_id"));
            }
        }
        final long referenceMsFinal = referenceMs;
        ReferenceSnapshot activeReference = referenceSnapshot;
        CompletableFuture<PipelineState> captureStage = CompletableFuture.supplyAsync(() -> {
            try {
                long t0 = System.nanoTime();
                lightClient.trigger(cameraId, toLong(activeReference.header().get("frame_id"), -1L), "capture");
                BinaryProtocol.Message capture = worker.command(Map.of("op", "capture"));
                if (log.isDebugEnabled()) {
                    log.debug("worker cam={} current capture header={}", cameraId, capture.header());
                }
                return new PipelineState(capture, null, null, nanosToMs(System.nanoTime() - t0), 0L, 0L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, captureStageExecutor);

        CompletableFuture<PipelineState> geometryStage = captureStage.thenApplyAsync(state -> {
            if (geometryPool.isEmpty()) {
                return state;
            }
            ServiceProcessSupervisor geometry = geometryPool.get(Math.floorMod(geometryRoundRobin.getAndIncrement(), geometryPool.size()));
            try {
                long t0 = System.nanoTime();
                Map<String, Object> gHeader = new java.util.HashMap<>();
                gHeader.put("op", "inspect_shm");
                gHeader.put("camera_id", cameraId);
                gHeader.put("frame_id", state.capture.header().get("frame_id"));
                gHeader.put("shm_name", state.capture.header().get("shm_name"));
                gHeader.put("shm_offset", state.capture.header().get("shm_offset"));
                gHeader.put("width", state.capture.header().get("width"));
                gHeader.put("height", state.capture.header().get("height"));
                gHeader.put("stride", state.capture.header().get("stride"));
                gHeader.put("reference_shm_name", activeReference.header().get("shm_name"));
                gHeader.put("reference_shm_offset", activeReference.header().get("shm_offset"));
                gHeader.put("reference_width", activeReference.header().get("width"));
                gHeader.put("reference_height", activeReference.header().get("height"));
                gHeader.put("reference_stride", activeReference.header().get("stride"));
                gHeader.put("mainRoi", geometryCfg == null ? Map.of("x", 0, "y", 0, "width", 2448, "height", 2048) : geometryCfg.get("main_roi"));
                gHeader.put("jointRoi", geometryCfg == null ? null : geometryCfg.get("joint_roi"));
                gHeader.put("wrinklesRoi", geometryCfg == null ? null : geometryCfg.get("wrinkles_roi"));
                gHeader.put("pixelsToMm", toDouble(geometryCfg == null ? null : geometryCfg.get("pixels_to_mm"), 0.01));
                gHeader.put("maxShiftMm", toDouble(geometryCfg == null ? null : geometryCfg.get("max_shift_mm"), 0.5));
                gHeader.put("maxRotationDeg", toDouble(geometryCfg == null ? null : geometryCfg.get("max_rotation_deg"), 1.0));
                gHeader.put("maxConcentricityMm", toDouble(geometryCfg == null ? null : geometryCfg.get("max_concentricity_mm"), 0.2));
                gHeader.put("maxJointDefectMm", toDouble(geometryCfg == null ? null : geometryCfg.get("max_joint_defect_mm"), 0.3));
                gHeader.put("maxWrinklesScore", toDouble(geometryCfg == null ? null : geometryCfg.get("max_wrinkles_score"), 0.05));
                geometrySlots.acquire();
                try {
                    BinaryProtocol.Message geomResp = geometry.command(gHeader);
                    if (log.isDebugEnabled()) {
                        log.debug("{} cam={} frame={} => {}", geometry.name(), cameraId, state.capture.header().get("frame_id"), geomResp.header());
                    }
                    return new PipelineState(state.capture, state.py, geomResp, state.captureMs, state.pythonMs, nanosToMs(System.nanoTime() - t0));
                } finally {
                    geometrySlots.release();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, geometryStageExecutor);

        CompletableFuture<PipelineState> pythonStage = geometryStage.thenApplyAsync(state -> {
            if (pythonPool.isEmpty()) {
                return state;
            }
            ServiceProcessSupervisor python = pythonPool.get(Math.floorMod(pythonRoundRobin.getAndIncrement(), pythonPool.size()));
            try {
                long t0 = System.nanoTime();
                Map<String, Object> pyHeader = new java.util.HashMap<>();
                pyHeader.put("op", "inspect_shm");
                pyHeader.put("camera_id", cameraId);
                pyHeader.put("frame_id", state.capture.header().get("frame_id"));
                pyHeader.put("product_type", productType);
                pyHeader.put("detector_id", detectorId);
                pyHeader.put("threshold", toDouble(pythonCfg == null ? null : pythonCfg.get("fallback_threshold"), 0.25));
                // SLA: visual'ы отключены в горячем пути. UI-артефакты считаются отдельным асинхронным шагом.
                pyHeader.put("include_visuals", false);
                if (pythonCfg != null && pythonCfg.get("rois") != null) {
                    pyHeader.put("rois", pythonCfg.get("rois"));
                }
                pyHeader.put("shm_name", state.capture.header().get("shm_name"));
                pyHeader.put("shm_offset", state.capture.header().get("shm_offset"));
                pyHeader.put("width", state.capture.header().get("width"));
                pyHeader.put("height", state.capture.header().get("height"));
                pyHeader.put("stride", state.capture.header().get("stride"));
                if (state.geom != null) {
                    Object h = state.geom.header().get("homographyRefToCurrent");
                    if (h != null) {
                        pyHeader.put("alignment_h_ref_to_cur", h);
                    }
                }
                pythonSlots.acquire();
                try {
                    BinaryProtocol.Message pyResp = python.command(pyHeader);
                    if (log.isDebugEnabled()) {
                        log.debug("{} cam={} frame={} => {}", python.name(), cameraId, state.capture.header().get("frame_id"), pyResp.header());
                    }
                    return new PipelineState(state.capture, pyResp, state.geom, state.captureMs, nanosToMs(System.nanoTime() - t0), state.geometryMs);
                } finally {
                    pythonSlots.release();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, pythonStageExecutor);

        CompletableFuture<Void> decisionStage = pythonStage.thenAcceptAsync(state -> {
            long tDecision0 = System.nanoTime();
            InspectionDecision decision = aggregateDecision(cameraId, state.capture, state.py, state.geom);
            long tDecisionDone = System.nanoTime();
            scheduleUiArtifacts(uiServer, uiCfg, uiVisualsPython, uiArtifactsExecutor, cameraId, productType, detectorId, state.capture, state.geom);
            fanOut.publish(toFanOutEvent(decision));
            long tFanoutDone = System.nanoTime();
            long totalMs = nanosToMs(tFanoutDone - tCameraStart);
            Map<String, Object> pyHeader = state.py == null ? Map.of() : state.py.header();
            double pyStageAlignMs = toDouble(pyHeader.get("stage_ms_align"), 0.0);
            double pyStageDiffMs = toDouble(pyHeader.get("stage_ms_diff"), 0.0);
            double pyStageAnomalyMs = toDouble(pyHeader.get("stage_ms_anomaly"), 0.0);
            double pyStageFpRecheckMs = toDouble(pyHeader.get("stage_ms_fp_recheck"), 0.0);
            double pyStageEncodeMs = toDouble(pyHeader.get("stage_ms_encode"), 0.0);
            double pyStageTotalMs = toDouble(pyHeader.get("stage_ms_total"), 0.0);
            log.info("stage_timing cam={} frame={} total_ms={} reference_ms={} capture_ms={} python_ms={} geometry_ms={} decision_ms={} fanout_ms={} "
                            + "py_align_ms={} py_diff_ms={} py_anomaly_ms={} py_fp_recheck_ms={} py_encode_ms={} py_total_ms={}",
                    cameraId,
                    decision.frameId(),
                    totalMs,
                    referenceMsFinal,
                    state.captureMs,
                    state.pythonMs,
                    state.geometryMs,
                    nanosToMs(tDecisionDone - tDecision0),
                    nanosToMs(tFanoutDone - tDecisionDone),
                    pyStageAlignMs,
                    pyStageDiffMs,
                    pyStageAnomalyMs,
                    pyStageFpRecheckMs,
                    pyStageEncodeMs,
                    pyStageTotalMs);
        }, decisionStageExecutor);

        decisionStage.join();
    }

    private FanOutEvent toFanOutEvent(InspectionDecision decision) {
        return new FanOutEvent(
                decision.cameraId(),
                decision.frameId(),
                decision.overallPass(),
                decision.action(),
                decision.anomalyScore(),
                decision.pythonStatus(),
                decision.geometryStatus(),
                System.currentTimeMillis()
        );
    }

    private UiHttpServer startUiServerIfEnabled(Map<String, Object> uiCfg) {
        boolean enabled = toBool(uiCfg == null ? null : uiCfg.get("enabled"), false);
        if (!enabled) {
            return null;
        }
        String host = String.valueOf(uiCfg.getOrDefault("host", "127.0.0.1"));
        int port = toInt(uiCfg.get("port"), 8099);
        try {
            UiHttpServer server = new UiHttpServer(host, port);
            log.info("ui http started on {}:{}", host, port);
            return server;
        } catch (Exception e) {
            log.warn("ui http failed to start: {}", e.getMessage());
            return null;
        }
    }

    private ServiceProcessSupervisor startUiVisualsPythonIfEnabled(
            Map<String, Object> integration,
            Map<String, Object> uiCfg,
            Path projectRoot,
            int commandTimeoutMs
    ) {
        boolean enabled = toBool(uiCfg == null ? null : uiCfg.get("enabled"), false)
                && toBool(uiCfg == null ? null : uiCfg.get("visuals_async_enabled"), false);
        if (!enabled) {
            return null;
        }
        // Reuse python_command_linux as visuals worker.
        return startOptionalService(integration, "python_command_linux", projectRoot, "python-visuals", commandTimeoutMs);
    }

    private ExecutorService startUiArtifactsExecutorIfEnabled(Map<String, Object> uiCfg) {
        boolean enabled = toBool(uiCfg == null ? null : uiCfg.get("enabled"), false)
                && toBool(uiCfg == null ? null : uiCfg.get("visuals_async_enabled"), false);
        if (!enabled) {
            return null;
        }
        int q = Math.max(1, toInt(uiCfg == null ? null : uiCfg.get("visuals_queue_size"), 8));
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                30L,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(q),
                r -> {
                    Thread t = new Thread(r, "ui-artifacts");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private void scheduleUiArtifacts(
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            ServiceProcessSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message geomResp
    ) {
        if (uiServer == null || uiVisualsPython == null || uiArtifactsExecutor == null || capture == null) {
            return;
        }
        boolean storeCurrent = toBool(uiCfg == null ? null : uiCfg.get("store_current_jpeg"), true);
        boolean storeHeatmapU8 = toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        if (!storeCurrent && !storeHeatmapU8) {
            return;
        }

        Map<String, Object> cap = capture.header();
        String shmName = String.valueOf(cap.get("shm_name"));
        long frameId = toLong(cap.get("frame_id"), -1L);
        int width = toInt(cap.get("width"), 2448);
        int height = toInt(cap.get("height"), 2048);
        int stride = toInt(cap.get("stride"), width * 3);

        Object homography = geomResp == null ? null : geomResp.header().get("homographyRefToCurrent");

        uiArtifactsExecutor.execute(() -> {
            try {
                // Ask python for visuals for this same frame (reads current from SHM).
                Map<String, Object> pyHeader = new java.util.HashMap<>();
                pyHeader.put("op", "inspect_shm");
                pyHeader.put("camera_id", cameraId);
                pyHeader.put("frame_id", frameId);
                pyHeader.put("product_type", productType);
                pyHeader.put("detector_id", detectorId);
                pyHeader.put("threshold", 0.25);
                pyHeader.put("include_visuals", false);
                pyHeader.put("shm_name", shmName);
                pyHeader.put("shm_offset", cap.get("shm_offset"));
                pyHeader.put("width", width);
                pyHeader.put("height", height);
                pyHeader.put("stride", stride);
                if (homography != null) {
                    pyHeader.put("alignment_h_ref_to_cur", homography);
                }
                String base = (shmName.startsWith("/") ? shmName.substring(1) : shmName);
                if (storeHeatmapU8) {
                    pyHeader.put("heatmap_u8_output_path", "/dev/shm/" + base + ".heatmap.u8");
                }
                BinaryProtocol.Message pyResp = uiVisualsPython.command(pyHeader);
                if (pyResp.type() == BinaryProtocol.MSG_ERROR) {
                    return;
                }
                Path currentJpeg = null;
                int currentJpegW = 0;
                int currentJpegH = 0;
                if (storeCurrent) {
                    int previewMaxW = toInt(uiCfg == null ? null : uiCfg.get("client_preview_max_width"), 0);
                    int qualPct = toInt(uiCfg == null ? null : uiCfg.get("client_preview_jpeg_quality"), 58);
                    qualPct = Math.min(100, Math.max(5, qualPct));
                    float q = qualPct / 100f;
                    UiHttpServer.ClientPreviewArtifact art =
                            UiHttpServer.writeCurrentJpegFromBgrShm(shmName, width, height, stride, previewMaxW, q);
                    currentJpeg = art.path();
                    currentJpegW = art.width();
                    currentJpegH = art.height();
                }
                String u8Path = String.valueOf(pyResp.header().getOrDefault("heatmap_u8_path", ""));
                int uw = toInt(pyResp.header().get("heatmap_u8_width"), 0);
                int uh = toInt(pyResp.header().get("heatmap_u8_height"), 0);
                Path heatmapU8 = (!u8Path.isBlank() && Files.isRegularFile(Path.of(u8Path))) ? Path.of(u8Path) : null;
                UiHttpServer.Latest prev = uiServer.latest(cameraId).orElse(null);
                if (currentJpeg == null && prev != null) {
                    currentJpeg = prev.currentJpeg();
                    currentJpegW = prev.currentJpegWidth();
                    currentJpegH = prev.currentJpegHeight();
                }
                if (heatmapU8 == null && prev != null) {
                    heatmapU8 = prev.heatmapU8();
                    uw = prev.heatmapU8Width();
                    uh = prev.heatmapU8Height();
                }
                if (currentJpeg != null && heatmapU8 != null && uw > 0 && uh > 0) {
                    uiServer.update(cameraId, frameId, currentJpeg, currentJpegW, currentJpegH, heatmapU8, uw, uh);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private ServiceProcessSupervisor startOptionalService(Map<String, Object> integration,
                                                          String key,
                                                          Path projectRoot,
                                                          String label,
                                                          int commandTimeoutMs) {
        if (integration == null) {
            return null;
        }
        Object raw = integration.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> command = new ArrayList<>();
        for (Object e : list) {
            command.add(String.valueOf(e));
        }
        try {
            ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(label, command, projectRoot, commandTimeoutMs);
            supervisor.start();
            BinaryProtocol.Message health = supervisor.health();
            log.info("{} health => {}", label, health.header());
            return supervisor;
        } catch (Exception e) {
            log.warn("failed to start optional {} service command={}: {}", label, command, e.getMessage());
            return null;
        }
    }

    private List<ServiceProcessSupervisor> startOptionalServicePool(Map<String, Object> integration,
                                                                    String key,
                                                                    Path projectRoot,
                                                                    String label,
                                                                    int commandTimeoutMs,
                                                                    int poolSize) {
        List<ServiceProcessSupervisor> pool = new ArrayList<>();
        if (integration == null) {
            return pool;
        }
        Object raw = integration.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return pool;
        }
        List<String> command = new ArrayList<>();
        for (Object e : list) {
            command.add(String.valueOf(e));
        }
        for (int i = 0; i < poolSize; i++) {
            String serviceName = poolSize == 1 ? label : (label + "-" + i);
            try {
                ServiceProcessSupervisor supervisor = new ServiceProcessSupervisor(serviceName, command, projectRoot, commandTimeoutMs);
                supervisor.start();
                BinaryProtocol.Message health = supervisor.health();
                log.info("{} health => {}", serviceName, health.header());
                pool.add(supervisor);
            } catch (Exception e) {
                log.warn("failed to start optional {} service command={}: {}", serviceName, command, e.getMessage());
            }
        }
        return pool;
    }

    private ExternalServiceProcess startOptionalExternalProcess(Map<String, Object> integration,
                                                                Path projectRoot,
                                                                String label,
                                                                boolean isWindows,
                                                                int startupDelayMs) {
        if (integration == null) {
            return null;
        }
        String key = isWindows ? "light_server_command_windows" : "light_server_command_linux";
        Object raw = integration.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> command = new ArrayList<>();
        for (Object e : list) {
            command.add(String.valueOf(e));
        }
        try {
            ExternalServiceProcess process = ExternalServiceProcess.start(label, command, projectRoot);
            if (startupDelayMs > 0) {
                Thread.sleep(startupDelayMs);
            }
            return process;
        } catch (Exception e) {
            log.warn("failed to start optional {} process command={}: {}", label, command, e.getMessage());
            return null;
        }
    }

    private LightTriggerClient buildLightClient(Map<String, Object> lightCfg) {
        boolean enabled = lightCfg != null && Boolean.parseBoolean(String.valueOf(lightCfg.getOrDefault("enabled", false)));
        String baseUrl = lightCfg == null ? "http://127.0.0.1:5079" : String.valueOf(lightCfg.getOrDefault("base_url", "http://127.0.0.1:5079"));
        String triggerPath = lightCfg == null ? "/api/light/trigger-inspection" : String.valueOf(lightCfg.getOrDefault("trigger_path", "/api/light/trigger-inspection"));
        int timeoutMs = toInt(lightCfg == null ? null : lightCfg.get("timeout_ms"), 800);
        boolean failOnError = lightCfg != null && Boolean.parseBoolean(String.valueOf(lightCfg.getOrDefault("fail_on_error", false)));
        int brightness = toInt(lightCfg == null ? null : lightCfg.get("brightness"), 100);
        int durationMs = toInt(lightCfg == null ? null : lightCfg.get("duration_ms"), 100);
        return new LightTriggerClient(enabled, failOnError, baseUrl, triggerPath, timeoutMs, brightness, durationMs);
    }

    private static long toLong(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean toBool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static long nanosToMs(long nanos) {
        return nanos / 1_000_000L;
    }

    private ExecutorService newStageExecutor(String name, int threads, int queueSize) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                Math.max(1, threads),
                Math.max(1, threads),
                30L,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(Math.max(1, queueSize)),
                r -> {
                    Thread t = new Thread(r, name);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private void shutdownQuietly(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
    }

    private record InspectionDecision(
            int cameraId,
            long frameId,
            boolean overallPass,
            String action,
            double anomalyScore,
            String pythonStatus,
            String geometryStatus
    ) {
    }

    private record ReferenceSnapshot(String productType, Map<String, Object> header) {
    }

    private record PipelineState(
            BinaryProtocol.Message capture,
            BinaryProtocol.Message py,
            BinaryProtocol.Message geom,
            long captureMs,
            long pythonMs,
            long geometryMs
    ) {
    }
}

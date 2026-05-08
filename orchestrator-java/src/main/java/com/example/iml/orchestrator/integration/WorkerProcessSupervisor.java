package com.example.iml.orchestrator.integration;

import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WorkerProcessSupervisor implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(WorkerProcessSupervisor.class);

    private final int cameraId;
    private final List<String> command;
    private final Path workingDir;
    private final WorkerIpcMode ipcMode;
    private final String namedPipePath;
    private final int namedPipeConnectTimeoutMs;
    private final int commandTimeoutMs;
    private final ExecutorService callExecutor;
    private BinaryClient client;
    private int restartCount;

    WorkerProcessSupervisor(int cameraId, List<String> command, Path workingDir, WorkerIpcMode ipcMode,
                            String namedPipePath, int namedPipeConnectTimeoutMs, int commandTimeoutMs) {
        this.cameraId = cameraId;
        this.command = command;
        this.workingDir = workingDir;
        this.ipcMode = ipcMode;
        this.namedPipePath = namedPipePath;
        this.namedPipeConnectTimeoutMs = namedPipeConnectTimeoutMs;
        this.commandTimeoutMs = Math.max(100, commandTimeoutMs);
        this.callExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "worker-call-" + cameraId);
            t.setDaemon(true);
            return t;
        });
    }

    void start() throws IOException {
        if (client != null && client.isAlive()) {
            return;
        }
        this.client = createClient();
        log.info("worker supervisor started camera={} ipc_mode={}", cameraId, ipcMode);
    }

    BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            ensureAlive();
            try {
                return commandNoRetry(header);
            } catch (IOException error) {
                lastError = error;
                if (attempt == 3) {
                    break;
                }
                log.warn("worker camera={} command failed on attempt {}/3; restarting: {}", cameraId, attempt, error.getMessage());
                restart();
            }
        }
        throw lastError == null ? new IOException("worker command failed") : lastError;
    }

    BinaryProtocol.Message commandNoRetry(Map<String, Object> header) throws IOException {
        ensureAlive();
        CompletableFuture<BinaryProtocol.Message> future = CompletableFuture.supplyAsync(() -> {
            try {
                return client.command(header);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, callExecutor);
        try {
            return future.get(commandTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("worker camera=" + cameraId + " command timeout after " + commandTimeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re && re.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("worker camera=" + cameraId + " command failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("worker camera=" + cameraId + " command interrupted", e);
        }
    }

    BinaryProtocol.Message health() throws IOException {
        return command(Map.of("op", "health"));
    }

    void restart() throws IOException {
        restartCount++;
        closeClientOnly();
        start();
    }

    int restartCount() {
        return restartCount;
    }

    private void ensureAlive() throws IOException {
        if (client == null || !client.isAlive()) {
            log.warn("worker camera={} is not alive; restarting", cameraId);
            restart();
        }
    }

    private BinaryClient createClient() throws IOException {
        return switch (ipcMode) {
            case STDIO -> new BinaryProcessClient(command, workingDir);
            case NAMED_PIPE -> new NamedPipeBinaryClient(command, workingDir, namedPipePath, namedPipeConnectTimeoutMs);
        };
    }

    private void closeClientOnly() {
        if (client != null) {
            try {
                client.command(Map.of("op", "stop"));
            } catch (Exception ignored) {
            }
            client.close();
            client = null;
        }
    }

    @Override
    public void close() {
        closeClientOnly();
        callExecutor.shutdownNow();
    }
}

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

final class ServiceProcessSupervisor implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(ServiceProcessSupervisor.class);

    private final String name;
    private final List<String> command;
    private final Path workingDir;
    private final int commandTimeoutMs;
    private final ExecutorService callExecutor;
    private BinaryClient client;
    private int restartCount;

    ServiceProcessSupervisor(String name, List<String> command, Path workingDir, int commandTimeoutMs) {
        this.name = name;
        this.command = command;
        this.workingDir = workingDir;
        this.commandTimeoutMs = Math.max(100, commandTimeoutMs);
        this.callExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "svc-call-" + name);
            t.setDaemon(true);
            return t;
        });
    }

    void start() throws IOException {
        if (client != null && client.isAlive()) return;
        client = new BinaryProcessClient(command, workingDir);
        log.info("{} supervisor started", name);
    }

    BinaryProtocol.Message command(Map<String, Object> header) throws IOException {
        ensureAlive();
        try {
            return commandNoRetry(header);
        } catch (IOException first) {
            log.warn("{} command failed; restart and retry: {}", name, first.getMessage());
            restart();
            return commandNoRetry(header);
        }
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
            throw new IOException(name + " command timeout after " + commandTimeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re && re.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException(name + " command failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(name + " interrupted", e);
        }
    }

    BinaryProtocol.Message health() throws IOException {
        return command(Map.of("op", "health"));
    }

    int restartCount() {
        return restartCount;
    }

    String name() {
        return name;
    }

    void restart() throws IOException {
        restartCount++;
        closeClientOnly();
        start();
    }

    private void ensureAlive() throws IOException {
        if (client == null || !client.isAlive()) {
            log.warn("{} is not alive; restarting", name);
            restart();
        }
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

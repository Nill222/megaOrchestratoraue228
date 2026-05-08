package com.example.iml.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientHttpStubServer implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(ClientHttpStubServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BoundedEventQueue queue;
    private final int artificialDelayMs;
    private final Thread sinkWorker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final HttpServer server;

    ClientHttpStubServer(String host, int port, int queueSize, int artificialDelayMs) throws IOException {
        this.queue = new BoundedEventQueue(queueSize);
        this.artificialDelayMs = Math.max(0, artificialDelayMs);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.server.createContext("/health", this::healthHandler);
        this.server.createContext("/events", this::eventsHandler);
        this.server.createContext("/metrics", this::metricsHandler);
        this.server.setExecutor(Executors.newFixedThreadPool(2));
        this.server.start();
        this.sinkWorker = new Thread(this::runSinkLoop, "fanout-client-sink");
        this.sinkWorker.start();
        log.info("client http stub listening on {}:{}", host, port);
    }

    void publish(FanOutEvent event) {
        queue.offer(event);
    }

    long droppedTotal() {
        return queue.droppedTotal();
    }

    int queueDepth() {
        return queue.size();
    }

    private void runSinkLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                FanOutEvent ignored = queue.take();
                if (artificialDelayMs > 0) {
                    Thread.sleep(artificialDelayMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("client sink loop error: {}", e.getMessage());
            }
        }
    }

    private void healthHandler(HttpExchange exchange) throws IOException {
        writeJson(exchange, 200, Map.of("status", "ok", "service", "client-http-stub"));
    }

    private void metricsHandler(HttpExchange exchange) throws IOException {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("queue_depth", queue.size());
        metrics.put("queue_dropped_total", queue.droppedTotal());
        metrics.put("queue_pushed_total", queue.pushedTotal());
        metrics.put("artificial_delay_ms", artificialDelayMs);
        writeJson(exchange, 200, metrics);
    }

    private void eventsHandler(HttpExchange exchange) throws IOException {
        List<FanOutEvent> events = queue.snapshot();
        writeJson(exchange, 200, events);
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] data = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    @Override
    public void close() {
        running.set(false);
        sinkWorker.interrupt();
        server.stop(0);
    }
}

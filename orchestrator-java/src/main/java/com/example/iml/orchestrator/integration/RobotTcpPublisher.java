package com.example.iml.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class RobotTcpPublisher implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(RobotTcpPublisher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int writeTimeoutMs;
    private final BoundedEventQueue queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);

    RobotTcpPublisher(String host, int port, int connectTimeoutMs, int writeTimeoutMs, int queueSize) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
        this.queue = new BoundedEventQueue(queueSize);
        this.worker = new Thread(this::runLoop, "fanout-robot-tcp");
        this.worker.start();
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

    private void runLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                FanOutEvent event = queue.take();
                sendLine(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("robot tcp publish failed: {}", e.getMessage());
            }
        }
    }

    private void sendLine(FanOutEvent event) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("camera_id", event.cameraId());
        body.put("frame_id", event.frameId());
        body.put("action", event.action());
        body.put("overall_pass", event.overallPass());
        body.put("anomaly_score", event.anomalyScore());
        body.put("python_status", event.pythonStatus());
        body.put("geometry_status", event.geometryStatus());
        body.put("timestamp_ms", event.timestampMs());
        String json = MAPPER.writeValueAsString(body);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(writeTimeoutMs);
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.println(json);
                writer.flush();
                if (writer.checkError()) {
                    throw new IllegalStateException("tcp write failed");
                }
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        worker.interrupt();
    }
}

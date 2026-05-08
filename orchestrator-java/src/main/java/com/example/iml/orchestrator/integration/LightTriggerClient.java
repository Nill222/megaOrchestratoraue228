package com.example.iml.orchestrator.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

final class LightTriggerClient {
    private static final Logger log = LogManager.getLogger(LightTriggerClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final boolean failOnError;
    private final URI triggerUri;
    private final HttpClient httpClient;
    private final Duration timeout;
    private final int defaultBrightness;
    private final int defaultDurationMs;

    LightTriggerClient(boolean enabled, boolean failOnError, String baseUrl, String triggerPath, int timeoutMs,
                       int defaultBrightness, int defaultDurationMs) {
        this.enabled = enabled;
        this.failOnError = failOnError;
        this.timeout = Duration.ofMillis(Math.max(100, timeoutMs));
        this.defaultBrightness = defaultBrightness;
        this.defaultDurationMs = defaultDurationMs;
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = triggerPath.startsWith("/") ? triggerPath : "/" + triggerPath;
        this.triggerUri = URI.create(root + path);
        this.httpClient = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    void trigger(int cameraId, long frameId, String phase) {
        if (!enabled) {
            return;
        }
        try {
            byte[] body = MAPPER.writeValueAsBytes(Map.of(
                    "cameraId", cameraId,
                    "frameId", frameId,
                    "phase", phase,
                    "brightness", defaultBrightness,
                    "durationMs", defaultDurationMs
            ));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(triggerUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                String msg = "light trigger failed status=" + response.statusCode() + " body=" + response.body();
                if (failOnError) {
                    throw new IllegalStateException(msg);
                }
                log.warn(msg);
            }
        } catch (Exception e) {
            if (failOnError) {
                throw new IllegalStateException("light trigger error", e);
            }
            log.warn("light trigger error: {}", e.getMessage());
        }
    }
}

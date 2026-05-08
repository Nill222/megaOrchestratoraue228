package com.example.iml.orchestrator.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Формирует сводку по загруженному конфигу в лог (отдельно от загрузки и от точки входа).
 */
public final class StartupConfigurationReporter {

    private static final Logger log = LogManager.getLogger(StartupConfigurationReporter.class);

    public void report(Map<String, Object> root, Path configPath) {
        Object version = root.get("version");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cameras = (List<Map<String, Object>>) root.get("cameras");
        @SuppressWarnings("unchecked")
        Map<String, Object> orchestrator = (Map<String, Object>) root.get("orchestrator");
        @SuppressWarnings("unchecked")
        Map<String, Object> client = (Map<String, Object>) root.get("client");
        @SuppressWarnings("unchecked")
        Map<String, Object> robot = (Map<String, Object>) root.get("robot");

        int camCount = Optional.ofNullable(cameras).map(List::size).orElse(0);
        log.info(
                "Оркестратор: старт с конфигом версии {} ({}) — камер: {}",
                version,
                configPath.toAbsolutePath(),
                camCount
        );

        logMapKeys(orchestrator, "control_pipe", "control_pipe_linux");
        logMapKey(client, "client.url", "url");
        Optional.ofNullable(robot)
                .ifPresent(r -> log.info("  robot.url: {} enabled={}", r.get("url"), r.get("enabled")));
    }

    /** Печатает строки {@code label: value} для каждого ключа из секции; секция отсутствует — ничего не делаем. */
    private void logMapKeys(Map<String, Object> section, String... keys) {
        Optional.ofNullable(section)
                .ifPresent(s -> Arrays.stream(keys).forEach(k -> log.info("  {}: {}", k, s.get(k))));
    }

    /** Одна строка с произвольной подписью и значением из карты по {@code mapKey}. */
    private void logMapKey(Map<String, Object> section, String label, String mapKey) {
        Optional.ofNullable(section).ifPresent(s -> log.info("  {}: {}", label, s.get(mapKey)));
    }
}

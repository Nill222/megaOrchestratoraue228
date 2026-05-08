package com.example.iml.orchestrator;

import com.example.iml.orchestrator.config.ConfigLoader;
import com.example.iml.orchestrator.config.StartupConfigurationReporter;
import com.example.iml.orchestrator.integration.IntegrationBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Сценарий запуска оркестратора: загрузка конфига и отчёт (OCP: поведение расширяется новыми реализациями {@link ConfigLoader}).
 */
public final class OrchestratorApplication {

    private static final Logger log = LogManager.getLogger(OrchestratorApplication.class);

    private final ConfigLoader configLoader;
    private final StartupConfigurationReporter startupReporter;
    private final IntegrationBootstrap integrationBootstrap;

    public OrchestratorApplication(ConfigLoader configLoader, StartupConfigurationReporter startupReporter) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.startupReporter = Objects.requireNonNull(startupReporter, "startupReporter");
        this.integrationBootstrap = new IntegrationBootstrap();
    }

    public void run(Path configPath) throws OrchestratorStartupException {
        final Map<String, Object> root;
        try {
            root = configLoader.load(configPath);
        } catch (IOException e) {
            throw new OrchestratorStartupException(
                    "Не удалось загрузить конфигурацию: " + configPath.toAbsolutePath(),
                    e
            );
        }
        if (root == null) {
            log.error("Конфигурация пуста или файл не содержит корневого YAML-объекта: {}", configPath.toAbsolutePath());
            throw new OrchestratorStartupException(
                    "Конфигурация пуста или файл не содержит корневого YAML-объекта: " + configPath.toAbsolutePath()
            );
        }
        startupReporter.report(root, configPath);
        Path projectRoot = configPath.toAbsolutePath().getParent().getParent();
        integrationBootstrap.start(root, projectRoot);
    }
}

package com.example.iml.orchestrator;

import com.example.iml.orchestrator.config.StartupConfigurationReporter;
import com.example.iml.orchestrator.config.YamlFileConfigLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Точка входа: только разбор аргументов, проверка пути и делегирование {@link OrchestratorApplication}.
 */
public final class OrchestratorMain {

    private static final Logger log = LogManager.getLogger(OrchestratorMain.class);

    public static void main(String[] args) {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/config.yaml");
        if (!Files.isRegularFile(configPath)) {
            log.error(
                    "Файл конфигурации не найден: {}. Запуск из корня репозитория или передайте путь к YAML.",
                    configPath.toAbsolutePath()
            );
            System.exit(1);
        }

        var application = new OrchestratorApplication(
                new YamlFileConfigLoader(),
                new StartupConfigurationReporter()
        );
        try {
            application.run(configPath);
        } catch (OrchestratorStartupException e) {
            log.error("Ошибка при старте оркестратора: {}", e.getMessage(), e);
            System.exit(2);
        }
    }
}

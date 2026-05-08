package com.example.iml.orchestrator.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Загрузка сырого дерева конфигурации (отделено от разбора полей — SRP).
 */
@FunctionalInterface
public interface ConfigLoader {

    Map<String, Object> load(Path path) throws IOException;
}

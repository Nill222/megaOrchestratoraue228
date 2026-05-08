package com.example.iml.orchestrator.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Реализация {@link ConfigLoader} для YAML-файла на диске.
 */
public final class YamlFileConfigLoader implements ConfigLoader {

    private final Yaml yaml = new Yaml();

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return (Map<String, Object>) yaml.load(in);
        }
    }
}

package com.github.obhen233.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * YAML configuration loader that flattens nested YAML structures into
 * Properties-style dot-notation key-value pairs.
 * <p>
 * Example:
 * <pre>
 *   api:
 *     model: gpt-4
 *     base-url: https://api.openai.com
 * </pre>
 * becomes:
 * <pre>
 *   api.model=gpt-4
 *   api.base-url=https://api.openai.com
 * </pre>
 */
public class YamlConfigLoader {
    private static final Logger logger = LoggerFactory.getLogger(YamlConfigLoader.class);

    /**
     * Load YAML from a file path and flatten to dot-notation properties.
     */
    public static Map<String, String> loadFlat(Path yamlPath) {
        if (!Files.exists(yamlPath)) {
            return Collections.emptyMap();
        }
        try (InputStream is = Files.newInputStream(yamlPath)) {
            return loadFlat(is);
        } catch (Exception e) {
            logger.warn("Failed to load YAML config from {}: {}", yamlPath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Load YAML from an InputStream and flatten to dot-notation properties.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> loadFlat(InputStream inputStream) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            Yaml yaml = new Yaml();
            Object raw = yaml.load(inputStream);
            if (raw instanceof Map) {
                flatten("", (Map<String, Object>) raw, result);
            }
        } catch (Exception e) {
            logger.warn("Failed to parse YAML: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Load YAML from classpath resource and flatten to dot-notation properties.
     */
    public static Map<String, String> loadFlatFromClasspath(String resourcePath) {
        InputStream is = YamlConfigLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        if (is == null) {
            return Collections.emptyMap();
        }
        return loadFlat(is);
    }

    /**
     * Recursively flatten a nested Map into dot-notation keys.
     */
    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> source, Map<String, String> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map) {
                flatten(key, (Map<String, Object>) value, target);
            } else if (value instanceof List) {
                target.put(key, String.join(",", (List<String>) value));
            } else if (value != null) {
                target.put(key, value.toString());
            }
        }
    }
}

package com.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SecretConfig {
    private static final Logger logger = Logger.getLogger(SecretConfig.class.getName());
    private static final Map<String, String> FILE_SECRETS = loadFileSecrets();

    private SecretConfig() {
    }

    public static String get(String key) {
        String envValue = normalize(System.getenv(key));
        if (envValue != null) {
            return envValue;
        }
        return normalize(FILE_SECRETS.get(key));
    }

    public static String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    public static String getRequired(String key) {
        String value = get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required secret: " + key);
        }
        return value;
    }

    private static Map<String, String> loadFileSecrets() {
        String pathValue = normalize(System.getenv("APP_SECRETS_PATH"));
        if (pathValue == null) {
            return Collections.emptyMap();
        }

        Path secretsPath = Path.of(pathValue);
        if (!Files.exists(secretsPath)) {
            return Collections.emptyMap();
        }

        ObjectMapper mapper = new ObjectMapper();
        try {
            Map<String, Object> raw = mapper.readValue(secretsPath.toFile(), new TypeReference<Map<String, Object>>() {
            });
            Map<String, String> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() != null) {
                    normalized.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            return normalized;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to read secrets JSON from " + secretsPath + ": " + e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
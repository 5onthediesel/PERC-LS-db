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

    /**
     * Inputs:      key (String) — secret name to look up
     * Outputs:     String — trimmed secret value, or null if not found in either source
     * Functionality: Returns the secret value by checking environment variables first, then the
     *               JSON secrets file; returns null if the key is absent or blank in both.
     * Dependencies: loadFileSecrets, normalize
     * Called by:   getRequired, getOrDefault, and all callers throughout the application that need optional secrets
     */
    public static String get(String key) {
        String envValue = normalize(System.getenv(key));
        if (envValue != null) {
            return envValue;
        }
        return normalize(FILE_SECRETS.get(key));
    }

    /**
     * Inputs:      key (String) — secret name to look up; defaultValue (String) — fallback value
     * Outputs:     String — the resolved secret value if present, otherwise defaultValue
     * Functionality: Wraps get(key) with a caller-supplied default for optional secrets that have
     *               a sensible fallback.
     * Dependencies: get
     * Called by:   Any caller that needs a secret with a fallback (currently unused in production paths)
     */
    public static String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Inputs:      key (String) — secret name to look up
     * Outputs:     String — trimmed non-blank secret value
     * Functionality: Delegates to get(key) and throws IllegalStateException if the value is null,
     *               enforcing that required secrets are present before the application proceeds.
     * Dependencies: get
     * Called by:   Config (all fields), db.connect, GoogleCloudStorageAPI.buildStorage,
     *              FileProcessor.downloadFromCloudUri, EmailProcessor.buildGmailService,
     *              MessagingController.sendGridEmailWebhook, TaskController.runEmailPollingTask,
     *              and all other callers that require a mandatory secret
     */
    public static String getRequired(String key) {
        String value = get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required secret: " + key);
        }
        return value;
    }

    /**
     * Inputs:      None (reads APP_SECRETS_PATH environment variable)
     * Outputs:     Map<String, String> — key-value pairs loaded from the JSON file,
     *              or an empty map if APP_SECRETS_PATH is unset or the file does not exist
     * Functionality: Reads the JSON secrets file at the path given by APP_SECRETS_PATH, converts
     *               all values to strings, and returns a normalized map; logs a warning on parse failure.
     * Dependencies: com.fasterxml.jackson.databind.ObjectMapper, java.nio.file.Files,
     *               java.nio.file.Path
     * Called by:   Static initializer (class load time)
     */
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

    /**
     * Inputs:      value (String) — raw string value (may be null)
     * Outputs:     String — trimmed value, or null if the input is null or blank after trimming
     * Functionality: Trims whitespace and converts blank strings to null to ensure consistent
     *               absent-value semantics throughout SecretConfig lookups.
     * Dependencies: None
     * Called by:   get, loadFileSecrets
     */
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

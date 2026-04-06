package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;

/**
 * AnimalDetectAPI client for wildlife detection.
 * 
 * Calls the AnimalDetect API to detect animals in images and extract elk
 * counts.
 * Usage:
 * AnimalDetectAPI api = new AnimalDetectAPI(apiKey, timeout);
 * Map<String, Object> response = api.callAnimalDetectAPI(imageBytes, filename,
 * "USA", 0.2);
 * int elkCount = api.countElkFromResponse(response, 0.2);
 */
public class AnimalDetectAPI {
    private static final Logger logger = Logger.getLogger(AnimalDetectAPI.class.getName());
    private static final String ANIMALDETECT_URL = "https://www.animaldetect.com/api/v1/detect";
    private static final int DEFAULT_TIMEOUT = 60;
    // Practical raw payload budget before request encoding/multipart overhead.
    private static final int PRACTICAL_RAW_LIMIT_BYTES = 1_100_000;

    private String apiKey;
    private int timeout;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    private static class PreparedUploadImage {
        final byte[] bytes;
        final String filename;

        PreparedUploadImage(byte[] bytes, String filename) {
            this.bytes = bytes;
            this.filename = filename;
        }
    }

    public AnimalDetectAPI(String apiKey) {
        this(apiKey, DEFAULT_TIMEOUT);
    }

    public AnimalDetectAPI(String apiKey, int timeout) {
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Call AnimalDetect API with multipart form data.
     */
    public Map<String, Object> callAnimalDetectAPI(
            byte[] imageBytes,
            String filename,
            String country,
            double threshold) throws Exception {

        String safeFilename = (filename == null || filename.isBlank()) ? "upload.jpeg" : filename;
        String imageContentType = detectImageContentType(safeFilename);

        String boundary = "----AnimalDetectBoundary" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        // Add image file part
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write("Content-Disposition: form-data; name=\"image\"; filename=\"".getBytes());
        body.write(safeFilename.getBytes(StandardCharsets.UTF_8));
        body.write(("\"\r\nContent-Type: " + imageContentType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(imageBytes);
        body.write("\r\n".getBytes());

        // Add country parameter
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write("Content-Disposition: form-data; name=\"country\"\r\n\r\n".getBytes());
        body.write(country.getBytes());
        body.write("\r\n".getBytes());

        // Add threshold parameter
        body.write(("--" + boundary + "\r\n").getBytes());
        body.write("Content-Disposition: form-data; name=\"threshold\"\r\n\r\n".getBytes());
        body.write(String.valueOf(threshold).getBytes());
        body.write("\r\n".getBytes());

        // Final boundary
        body.write(("--" + boundary + "--\r\n").getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANIMALDETECT_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .timeout(java.time.Duration.ofSeconds(timeout))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            String body_text = response.body().substring(0, Math.min(1000, response.body().length()));
            throw new RuntimeException("AnimalDetect API " + response.statusCode() + ": " + body_text);
        }

        try {
            return objectMapper.readValue(response.body(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("AnimalDetect API returned non-JSON response: " +
                    response.body().substring(0, Math.min(500, response.body().length())), e);
        }
    }

    /**
     * Call AnimalDetect API with strict preflight size enforcement and fallback
     * compression retries if API still reports payload too large.
     */
    public Map<String, Object> callAnimalDetectAPIWithFallback(
            byte[] imageBytes,
            String filename,
            String country,
            double threshold) throws Exception {

        PreparedUploadImage prepared = prepareImageForPayloadLimit(imageBytes, filename);

        // Initial call uses strictly limited bytes (<= practical raw budget).
        try {
            return callAnimalDetectAPI(prepared.bytes, prepared.filename, country, threshold);
        } catch (Exception e) {
            if (!isPayloadTooLargeError(e)) {
                throw e;
            }
        }

        Exception lastError = null;
        byte[] currentBytes = prepared.bytes;
        String currentName = prepared.filename;
        int[][] fallbackSteps = {
                { 1800, 85 },
                { 1400, 78 },
                { 1100, 70 },
                { 900, 62 },
                { 720, 55 }
        };

        for (int[] step : fallbackSteps) {
            try {
                currentBytes = compressImageForUpload(currentBytes, currentName, step[0], step[1]);
                if (currentBytes.length > PRACTICAL_RAW_LIMIT_BYTES) {
                    continue;
                }
                currentName = toCompressedFilename(filename);
                logger.info("Retrying with compressed image (" + (currentBytes.length / 1024) +
                        " KB, max_side=" + step[0] + ", quality=" + step[1] + ")");

                return callAnimalDetectAPI(currentBytes, currentName, country, threshold);
            } catch (Exception e) {
                lastError = e;
                if (!isPayloadTooLargeError(e)) {
                    throw e;
                }
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new RuntimeException("AnimalDetect API call failed with unknown error");
    }

    /**
     * Compress image for upload if it's too large.
     */
    public byte[] compressImageForUpload(byte[] imageBytes, String filename, int maxSide, int quality)
            throws Exception {

        BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        img = convertToRGB(img);

        int width = img.getWidth();
        int height = img.getHeight();
        int longestSide = Math.max(width, height);

        if (longestSide > maxSide) {
            float scale = maxSide / (float) longestSide;
            int newWidth = Math.max(1, (int) (width * scale));
            int newHeight = Math.max(1, (int) (height * scale));
            BufferedImage resized = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = resized.createGraphics();
            g2d.drawImage(img, 0, 0, newWidth, newHeight, null);
            g2d.dispose();
            img = resized;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality / 100f);

        writer.setOutput(ImageIO.createImageOutputStream(out));
        writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
        writer.dispose();

        return out.toByteArray();
    }

    private PreparedUploadImage prepareImageForPayloadLimit(byte[] imageBytes, String filename) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image payload is empty");
        }

        String safeFilename = (filename == null || filename.isBlank()) ? "upload.jpeg" : filename;
        if (imageBytes.length <= PRACTICAL_RAW_LIMIT_BYTES) {
            return new PreparedUploadImage(imageBytes, safeFilename);
        }

        int[][] strictSteps = {
                { 2200, 92 },
                { 1800, 85 },
                { 1400, 78 },
                { 1100, 70 },
                { 900, 62 },
                { 720, 55 }
        };

        for (int[] step : strictSteps) {
            byte[] compressed = compressImageForUpload(imageBytes, safeFilename, step[0], step[1]);
            if (compressed.length <= PRACTICAL_RAW_LIMIT_BYTES) {
                logger.info("Compressed oversized image from " + (imageBytes.length / 1024) + " KB to "
                        + (compressed.length / 1024) + " KB to satisfy practical raw limit (~1.1MB)");
                return new PreparedUploadImage(compressed, toCompressedFilename(safeFilename));
            }
        }

        throw new IllegalArgumentException(
                "Image is too large: unable to shrink under practical raw limit (~1.1MB) required by Vertex encoded request cap");
    }

    private String toCompressedFilename(String filename) {
        String baseName = (filename == null || filename.isBlank()) ? "upload"
                : (filename.lastIndexOf('.') > 0 ? filename.substring(0, filename.lastIndexOf('.')) : filename);
        return baseName + "_compressed.jpeg";
    }

    /**
     * Convert image to RGB if needed.
     */
    private BufferedImage convertToRGB(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgbImage = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = rgbImage.createGraphics();
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();
        return rgbImage;
    }

    /**
     * Check if error is due to payload being too large.
     */
    private boolean isPayloadTooLargeError(Exception exc) {
        String msg = exc.toString();
        return msg.contains("413") || msg.contains("FUNCTION_PAYLOAD_TOO_LARGE");
    }

    private String detectImageContentType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) {
            return "image/heic";
        }
        if (lower.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    /**
     * Extract detections from API response.
     */
    private List<Map<String, Object>> extractDetections(Map<String, Object> payload) {
        Object[] candidates = {
                payload.get("annotations"),
                payload.get("detections"),
                payload.get("results"),
                payload.get("predictions"),
                payload.getOrDefault("data", new HashMap<>()),
        };

        for (Object item : candidates) {
            if (item instanceof List) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Object obj : (List<?>) item) {
                    if (obj instanceof Map) {
                        list.add((Map<String, Object>) obj);
                    }
                }
                if (!list.isEmpty()) {
                    return list;
                }
            } else if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                List<?> annotations = (List<?>) map.get("annotations");
                if (annotations != null) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (Object obj : annotations) {
                        if (obj instanceof Map) {
                            list.add((Map<String, Object>) obj);
                        }
                    }
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
                List<?> detections = (List<?>) map.get("detections");
                if (detections != null) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    for (Object obj : detections) {
                        if (obj instanceof Map) {
                            list.add((Map<String, Object>) obj);
                        }
                    }
                    if (!list.isEmpty()) {
                        return list;
                    }
                }
            }
        }
        return new ArrayList<>();
    }

    /**
     * Get the most specific taxonomy level from a detection.
     */
    private String getDetectionLabel(Map<String, Object> det) {
        Map<String, Object> taxonomy = (Map<String, Object>) det.get("taxonomy");
        if (taxonomy != null) {
            for (String key : new String[] { "species", "genus", "family", "order", "class" }) {
                Object value = taxonomy.get(key);
                if (value instanceof String && !((String) value).trim().isEmpty()) {
                    return ((String) value).trim().toLowerCase();
                }
            }
        }

        for (String key : new String[] { "species", "class", "label", "name", "animal" }) {
            Object value = det.get(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return ((String) value).trim().toLowerCase();
            }
        }
        return "";
    }

    /**
     * Get detection confidence score.
     */
    private Double getDetectionScore(Map<String, Object> det) {
        for (String key : new String[] { "confidence", "score", "probability" }) {
            Object value = det.get(key);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        }
        return null;
    }

    /**
     * Count elk detections from API response.
     */
    public int countElkFromResponse(Map<String, Object> payload, double threshold) {
        List<Map<String, Object>> detections = extractDetections(payload);
        int elkCount = 0;
        String[] elkMarkers = { "elk", "wapiti", "cervus canadensis" };

        for (Map<String, Object> det : detections) {
            String label = getDetectionLabel(det);

            // Build label text with multiple sources
            StringBuilder labelText = new StringBuilder(label);
            Object rawLabel = det.get("label");
            if (rawLabel instanceof String && !((String) rawLabel).trim().isEmpty()) {
                labelText.append(" | ").append(((String) rawLabel).trim().toLowerCase());
            }

            Map<String, Object> taxonomy = (Map<String, Object>) det.get("taxonomy");
            if (taxonomy != null) {
                for (String taxKey : new String[] { "species", "genus", "family", "order", "class" }) {
                    Object taxVal = taxonomy.get(taxKey);
                    if (taxVal instanceof String && !((String) taxVal).trim().isEmpty()) {
                        labelText.append(" | ").append(((String) taxVal).trim().toLowerCase());
                    }
                }
            }

            // Check if any elk marker is in the label
            String labelStr = labelText.toString();
            boolean isElk = false;
            for (String marker : elkMarkers) {
                if (labelStr.contains(marker)) {
                    isElk = true;
                    break;
                }
            }

            if (!isElk) {
                continue;
            }

            // Check score threshold
            Double score = getDetectionScore(det);
            if (score == null || score >= threshold) {
                elkCount++;
            }
        }

        return elkCount;
    }

    /**
     * Format model detections for console output with label + confidence.
     */
    public List<String> formatDetectionsForConsole(Map<String, Object> payload) {
        List<Map<String, Object>> detections = extractDetections(payload);
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < detections.size(); i++) {
            Map<String, Object> det = detections.get(i);
            String label = getDetectionLabel(det);
            if (label == null || label.isBlank()) {
                label = "unknown";
            }

            Double score = getDetectionScore(det);
            String confidenceText = "n/a";
            if (score != null) {
                double percent = score <= 1.0 ? score * 100.0 : score;
                confidenceText = String.format(Locale.US, "%.1f%%", percent);
            }

            lines.add("prediction " + i + ": " + label + " (confidence=" + confidenceText + ")");
        }

        return lines;
    }

    /**
     * Get API key from environment or config.
     */
    public static String resolveApiKey(String cliKey) throws Exception {
        if (cliKey != null && !cliKey.trim().isEmpty()) {
            return cliKey.trim();
        }

        String envKey = System.getenv("ANIMALDETECT_API_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) {
            return envKey.trim();
        }

        String secretKey = SecretConfig.get("ANIMALDETECT_API_KEY");
        if (secretKey != null && !secretKey.trim().isEmpty()) {
            return secretKey.trim();
        }

        throw new Exception("AnimalDetect API key not set. Provide via environment variable " +
                "ANIMALDETECT_API_KEY or in app-secrets.json");
    }
}

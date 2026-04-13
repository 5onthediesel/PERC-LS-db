package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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

    /**
     * Inputs:      apiKey (String) — AnimalDetect API key
     * Outputs:     AnimalDetectAPI instance with DEFAULT_TIMEOUT (60s)
     * Functionality: Convenience constructor that delegates to the two-arg constructor with a default timeout.
     * Dependencies: None
     * Called by:   FileProcessor.uploadAndProcessFiles, FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              EmailProcessor.pollAndProcess, MessagingController.sendGridEmailWebhook
     */
    public AnimalDetectAPI(String apiKey) {
        this(apiKey, DEFAULT_TIMEOUT);
    }

    /**
     * Inputs:      apiKey (String) — AnimalDetect API key; timeout (int) — HTTP timeout in seconds
     * Outputs:     Fully initialized AnimalDetectAPI instance
     * Functionality: Initializes the HTTP client and JSON object mapper used for all API calls.
     * Dependencies: java.net.http.HttpClient, com.fasterxml.jackson.databind.ObjectMapper
     * Called by:   AnimalDetectAPI(String) single-arg constructor; callers that need a custom timeout
     */
    public AnimalDetectAPI(String apiKey, int timeout) {
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Inputs:      imageBytes (byte[]) — raw image data; filename (String) — original file name;
     *              country (String) — country code for detection context (e.g. "USA");
     *              threshold (double) — minimum confidence score to include a detection
     * Outputs:     Map<String, Object> — parsed JSON response from the AnimalDetect API
     * Functionality: Sends the image to the AnimalDetect REST API as a multipart/form-data POST and returns the parsed response.
     * Dependencies: java.net.http.HttpClient, java.net.http.HttpRequest/HttpResponse,
     *               com.fasterxml.jackson.databind.ObjectMapper
     * Called by:   callAnimalDetectAPIWithFallback (retry loop)
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
     * Inputs:      imageBytes (byte[]) — raw image data; filename (String) — original file name;
     *              country (String) — country code; threshold (double) — confidence threshold
     * Outputs:     Map<String, Object> — parsed API response after successful call
     * Functionality: Enforces a payload size limit before calling the API, then retries with
     *               progressively smaller/lower-quality JPEG compressions on HTTP 413 errors.
     * Dependencies: compressImageForUpload, prepareImageForPayloadLimit, callAnimalDetectAPI
     * Called by:   FileProcessor.uploadAndProcessFiles, FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              EmailProcessor.pollAndProcess, MessagingController.sendGridEmailWebhook
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
     * Inputs:      imageBytes (byte[]) — raw image data; filename (String) — used for format hints;
     *              maxSide (int) — maximum pixel length for the longest side after resizing;
     *              quality (int) — JPEG compression quality (1–100)
     * Outputs:     byte[] — JPEG-encoded bytes of the resized/compressed image
     * Functionality: Decodes, optionally downscales, converts to RGB, and JPEG-encodes the image to
     *               reduce its byte size for upload.
     * Dependencies: javax.imageio.ImageIO, java.awt.image.BufferedImage, java.awt.Graphics2D
     * Called by:   callAnimalDetectAPIWithFallback (retry steps), prepareImageForPayloadLimit
     */
    public byte[] compressImageForUpload(byte[] imageBytes, String filename, int maxSide, int quality)
            throws Exception {
        BufferedImage img = readScaledImageForCompression(imageBytes, maxSide);
        if (img == null) {
            throw new IllegalArgumentException("Unsupported or corrupt image payload");
        }

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

            // Null the original decoded image immediately after drawing — it can be
            // very large (e.g. 4000x3000x4 bytes = ~46MB) and is no longer needed.
            // This lets the GC reclaim it before we encode the resized copy.
            img = null;
            System.gc();

            img = resized;
        }

        img = convertToRGB(img);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        try {
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100f);

            try (javax.imageio.stream.ImageOutputStream imageOut = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(imageOut);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            }

            // Null the final image after encoding — the encoded bytes are all we need now.
            img = null;
            System.gc();

            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /**
     * Inputs:      imageBytes (byte[]) — raw image data; maxSide (int) — target maximum side length
     * Outputs:     BufferedImage — decoded image, sub-sampled if source is larger than maxSide
     * Functionality: Uses an ImageReader with sub-sampling to decode only enough pixel data needed
     *               for the target size, avoiding full allocation of very large images.
     * Dependencies: javax.imageio.ImageIO, javax.imageio.ImageReader, javax.imageio.ImageReadParam,
     *               javax.imageio.stream.ImageInputStream
     * Called by:   compressImageForUpload
     */
    private BufferedImage readScaledImageForCompression(byte[] imageBytes, int maxSide) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            if (input == null) {
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return ImageIO.read(new ByteArrayInputStream(imageBytes));
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int srcWidth = reader.getWidth(0);
                int srcHeight = reader.getHeight(0);
                int longest = Math.max(srcWidth, srcHeight);

                ImageReadParam param = reader.getDefaultReadParam();
                if (longest > maxSide) {
                    int subsample = Math.max(1, (int) Math.ceil(longest / (double) maxSide));
                    param.setSourceSubsampling(subsample, subsample, 0, 0);
                }

                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Inputs:      imageBytes (byte[]) — raw image data; filename (String) — original file name
     * Outputs:     PreparedUploadImage — wrapper containing (possibly compressed) bytes and a safe filename
     * Functionality: Returns the image as-is if under the payload limit; otherwise estimates a target
     *               scale and compresses the image to fit within PRACTICAL_RAW_LIMIT_BYTES.
     * Dependencies: compressImageForUpload, readImageDimensions, toCompressedFilename
     * Called by:   callAnimalDetectAPIWithFallback
     */
    private PreparedUploadImage prepareImageForPayloadLimit(byte[] imageBytes, String filename) throws Exception {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("Image payload is empty");
        }

        String safeFilename = (filename == null || filename.isBlank()) ? "upload.jpeg" : filename;
        if (imageBytes.length <= PRACTICAL_RAW_LIMIT_BYTES) {
            return new PreparedUploadImage(imageBytes, safeFilename);
        }

        // Estimate the scale factor needed to hit the target size.
        // We use sqrt because area (pixels) scales as the square of linear dimensions.
        // Apply a 0.85 safety margin since JPEG compression isn't perfectly linear.
        double ratio = (double) PRACTICAL_RAW_LIMIT_BYTES / imageBytes.length;
        double scale = Math.sqrt(ratio) * 0.85;

        // Read just the image dimensions without fully decoding the pixel buffer.
        int[] dims = readImageDimensions(imageBytes);
        int longestSide = Math.max(dims[0], dims[1]);
        int targetMaxSide = Math.max(256, (int) (longestSide * scale));
        int targetQuality = 82; // balanced default

        logger.info("Dynamic compression: " + longestSide + "px → " + targetMaxSide +
                "px side (ratio=" + String.format("%.2f", ratio) + ", scale=" + String.format("%.2f", scale) + ")");

        byte[] compressed = compressImageForUpload(imageBytes, safeFilename, targetMaxSide, targetQuality);

        // One safety retry if the estimate was slightly off (e.g. very noisy image).
        if (compressed.length > PRACTICAL_RAW_LIMIT_BYTES) {
            logger.warning("Dynamic estimate overshot, applying single safety retry at 90% of target side");
            int fallbackSide = Math.max(256, (int) (targetMaxSide * 0.90));
            compressed = compressImageForUpload(imageBytes, safeFilename, fallbackSide, 72);
        }

        if (compressed.length > PRACTICAL_RAW_LIMIT_BYTES) {
            throw new IllegalArgumentException(
                    "Image is too large: unable to shrink under practical raw limit (~1.1MB)");
        }

        System.gc();
        logger.info("Compressed oversized image from " + (imageBytes.length / 1024) + " KB to "
                + (compressed.length / 1024) + " KB");
        return new PreparedUploadImage(compressed, toCompressedFilename(safeFilename));
    }

    /**
     * Inputs:      imageBytes (byte[]) — raw image data
     * Outputs:     int[] — two-element array [width, height]; defaults to [1920, 1080] if unreadable
     * Functionality: Reads only the image header to extract dimensions without decoding the full pixel buffer.
     * Dependencies: javax.imageio.ImageIO, javax.imageio.ImageReader, javax.imageio.stream.ImageInputStream
     * Called by:   prepareImageForPayloadLimit
     */
    private int[] readImageDimensions(byte[] imageBytes) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return new int[] { 1920, 1080 }; // safe fallback
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Inputs:      filename (String) — original file name (may be null or blank)
     * Outputs:     String — new filename with "_compressed.jpeg" suffix
     * Functionality: Strips the original extension and appends "_compressed.jpeg" to produce a storage-safe name.
     * Dependencies: None
     * Called by:   prepareImageForPayloadLimit, callAnimalDetectAPIWithFallback
     */
    private String toCompressedFilename(String filename) {
        String baseName = (filename == null || filename.isBlank()) ? "upload"
                : (filename.lastIndexOf('.') > 0 ? filename.substring(0, filename.lastIndexOf('.')) : filename);
        return baseName + "_compressed.jpeg";
    }

    /**
     * Inputs:      img (BufferedImage) — source image, any color model
     * Outputs:     BufferedImage — image guaranteed to be TYPE_INT_RGB
     * Functionality: Redraws the image onto a fresh RGB canvas to strip alpha channels or unusual color spaces
     *               before JPEG encoding.
     * Dependencies: java.awt.image.BufferedImage, java.awt.Graphics2D
     * Called by:   compressImageForUpload
     */
    private BufferedImage convertToRGB(BufferedImage img) {
        if (img == null) {
            throw new IllegalArgumentException("Image decode returned null");
        }
        if (img.getType() == BufferedImage.TYPE_INT_RGB) {
            return img;
        }
        BufferedImage rgbImage = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = rgbImage.createGraphics();
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();

        // Null the original so GC can reclaim it — convertToRGB is called just before
        // JPEG encoding, so both the original and RGB copy would otherwise coexist.
        img = null;
        System.gc();

        return rgbImage;
    }

    /**
     * Inputs:      exc (Exception) — exception thrown by an API call
     * Outputs:     boolean — true if the error indicates the payload was too large (HTTP 413)
     * Functionality: Checks the exception message for HTTP 413 or a known cloud-function payload error string.
     * Dependencies: None
     * Called by:   callAnimalDetectAPIWithFallback
     */
    private boolean isPayloadTooLargeError(Exception exc) {
        String msg = exc.toString();
        return msg.contains("413") || msg.contains("FUNCTION_PAYLOAD_TOO_LARGE");
    }

    /**
     * Inputs:      filename (String) — file name whose extension determines the MIME type
     * Outputs:     String — MIME type string (e.g. "image/jpeg", "image/png")
     * Functionality: Maps common image file extensions to their corresponding MIME types, defaulting to "image/jpeg".
     * Dependencies: None
     * Called by:   callAnimalDetectAPI
     */
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
     * Inputs:      payload (Map<String, Object>) — parsed API JSON response
     * Outputs:     List<Map<String, Object>> — list of detection objects; empty list if none found
     * Functionality: Searches common API response field names (annotations, detections, results, predictions, data)
     *               to extract the list of animal detections regardless of the exact response schema.
     * Dependencies: None
     * Called by:   countElkFromResponse, formatDetectionsForConsole
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
     * Inputs:      det (Map<String, Object>) — a single detection object from the API response
     * Outputs:     String — the most specific taxonomy label available, lowercased; empty string if none found
     * Functionality: Walks the taxonomy hierarchy (species → genus → family → order → class) then
     *               falls back to top-level label fields to return the best available animal name.
     * Dependencies: None
     * Called by:   countElkFromResponse, formatDetectionsForConsole
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
     * Inputs:      det (Map<String, Object>) — a single detection object from the API response
     * Outputs:     Double — confidence score in [0,1] range, or null if no score field is present
     * Functionality: Checks common confidence field names (confidence, score, probability) and returns
     *               the first numeric value found.
     * Dependencies: None
     * Called by:   countElkFromResponse, formatDetectionsForConsole
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
     * Inputs:      payload (Map<String, Object>) — parsed API response; threshold (double) — minimum confidence score
     * Outputs:     int — number of elk detections at or above the confidence threshold
     * Functionality: Filters detections to those labelled as elk/wapiti/cervus canadensis with a confidence
     *               score meeting the threshold, and returns the total count.
     * Dependencies: extractDetections, getDetectionLabel, getDetectionScore
     * Called by:   FileProcessor.uploadAndProcessFiles, FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              EmailProcessor.pollAndProcess, MessagingController.sendGridEmailWebhook
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
     * Inputs:      payload (Map<String, Object>) — parsed API response
     * Outputs:     List<String> — human-readable lines, one per detection, e.g. "prediction 0: elk (confidence=87.3%)"
     * Functionality: Formats every detection in the API response as a labeled confidence string for console logging.
     * Dependencies: extractDetections, getDetectionLabel, getDetectionScore
     * Called by:   FileProcessor.uploadAndProcessFiles, FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              EmailProcessor.pollAndProcess
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
     * Inputs:      cliKey (String) — API key passed via CLI argument (may be null)
     * Outputs:     String — resolved, non-blank API key
     * Functionality: Returns the first non-blank key found across three sources in priority order:
     *               CLI argument → ANIMALDETECT_API_KEY env var → SecretConfig JSON file.
     * Dependencies: SecretConfig
     * Called by:   FileProcessor.uploadAndProcessFiles, FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              EmailProcessor.pollAndProcess, MessagingController.sendGridEmailWebhook
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

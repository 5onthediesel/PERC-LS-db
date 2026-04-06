package com.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.FileInputStream;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

public class FileProcessor {

    private static final Logger logger = Logger.getLogger(FileProcessor.class.getName());
    private static final String BUCKET_NAME = "cs370perc-bucket";
    private static final String[] ALLOWED_EXTENSIONS = { ".png", ".jpg", ".jpeg", ".heic" };

    private static class UploadMetadataData {
        public String filename;
        public Double longitude;
        public Double latitude;
        public Double altitude;
        public Double temperature_c;
        public Double humidity;
        public String weather_desc;
        public Integer width;
        public Integer height;
        public String datetime;

        @JsonProperty("temp")
        public void setTemp(Double value) {
            this.temperature_c = value;
        }

        @JsonProperty("hum")
        public void setHum(Double value) {
            this.humidity = value;
        }

        @JsonProperty("weather")
        public void setWeather(String value) {
            this.weather_desc = value;
        }
    }

    public static class BatchResult {
        public final int attempted;
        public final int processed;
        public final List<String> errors;

        public BatchResult(int attempted, int processed, List<String> errors) {
            this.attempted = attempted;
            this.processed = processed;
            this.errors = errors;
        }
    }

    public static record ImagePayload(String filename, byte[] bytes) {
    }

    private static class PythonInferenceClient {
        private final RestClient restClient;

        PythonInferenceClient(RestClient.Builder builder) {
            // Force HTTP/1.1 to avoid h2c upgrade issues with Uvicorn.
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

            this.restClient = builder
                    .baseUrl("http://localhost:8000")
                    .requestFactory(requestFactory)
                    .build();
        }

        Integer inferCount(byte[] imageBytes) {
            try {
                String response = restClient.post()
                        .uri("/infer")
                        .contentType(MediaType.IMAGE_JPEG)
                        .body(imageBytes)
                        .retrieve()
                        .body(String.class);

                if (response == null || response.isBlank()) {
                    return null;
                }
                return Integer.parseInt(response.trim());
            } catch (Exception e) {
                System.err.println("Inference failed: " + e.getMessage());
                return null;
            }
        }

        List<Integer> inferCounts(List<ImagePayload> images) {
            List<Integer> counts = new ArrayList<>();
            for (ImagePayload img : images) {
                Integer count = inferCount(img.bytes());
                counts.add(count);
            }
            return counts;
        }
    }

    public static List<Map<String, Object>> uploadAndProcessFiles(
            MultipartFile[] files,
            String metadataJson) throws Exception {
        validateUploadedFiles(files);

        List<UploadMetadataData> metadataList = parseUploadMetadata(files, metadataJson);

        AnimalDetectAPI animalDetectAPI = null;
        try {
            String apiKey = AnimalDetectAPI.resolveApiKey(null);
            animalDetectAPI = new AnimalDetectAPI(apiKey, 60);
        } catch (Exception e) {
            logger.log(Level.WARNING, "AnimalDetect API not available", e);
        }

        List<Map<String, Object>> uploadedFiles = new ArrayList<>();
        try (Connection conn = db.connect()) {
            for (int i = 0; i < files.length; i++) {
                MultipartFile file = files[i];
                String originalName = file.getOriginalFilename();
                String suffix = (originalName == null || originalName.isBlank()) ? ".bin" : "-" + originalName;
                Path tempFile = Files.createTempFile("upload-", suffix);

                try {
                    Files.write(tempFile, file.getBytes());

                    String ext = ImageUtils.getExtension(originalName == null ? "" : originalName).toLowerCase();
                    String dotExt = normalizedStorageExtension(ext);
                    UploadMetadataData uploadData = metadataList.isEmpty() ? null : metadataList.get(i);

                    Metadata meta = buildMetadataForUpload(tempFile, originalName, uploadData, i);
                    String objectName = meta.sha256 + dotExt;

                    Metadata existing = db.getImageByHash(conn, meta.sha256);
                    if (existing != null) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("originalName", originalName);
                        fileInfo.put("status", "duplicate hash; skipped upload");
                        fileInfo.put("objectName", objectName);
                        fileInfo.put("sha256", existing.sha256);
                        fileInfo.put("cloudUri", existing.cloud_uri);
                        addMetadataToFileInfo(fileInfo, existing);
                        uploadedFiles.add(fileInfo);
                        continue;
                    }

                    GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                    meta.cloud_uri = "gs://" + BUCKET_NAME + "/" + objectName;
                    db.insertMeta(conn, meta);

                    if (animalDetectAPI != null) {
                        try {
                            byte[] imageBytes = Files.readAllBytes(tempFile);
                            Map<String, Object> response = animalDetectAPI.callAnimalDetectAPIWithFallback(
                                    imageBytes,
                                    originalName,
                                    "USA",
                                    0.2);
                            List<String> predictionLines = animalDetectAPI.formatDetectionsForConsole(response);
                            if (predictionLines.isEmpty()) {
                                logger.info("Model predictions for " + originalName + ": none");
                            } else {
                                for (String predictionLine : predictionLines) {
                                    logger.info("Model predictions for " + originalName + " -> " + predictionLine);
                                }
                            }

                            int elkCount = animalDetectAPI.countElkFromResponse(response, 0.2);
                            meta.elk_count = elkCount;
                            meta.processed_status = true;
                        } catch (Exception detectionError) {
                            logger.log(Level.WARNING,
                                    "Animal detection failed for " + originalName, detectionError);
                            meta.elk_count = null;
                            meta.processed_status = false;
                        }
                    }

                    db.updateMetaWithDetection(conn, meta.sha256, meta.elk_count, meta.processed_status);

                    Map<String, Object> fileInfo = new HashMap<>();
                    fileInfo.put("originalName", originalName);
                    fileInfo.put("objectName", objectName);
                    fileInfo.put("cloudUri", meta.cloud_uri);
                    fileInfo.put("sha256", meta.sha256);
                    fileInfo.put("processedStatus", meta.processed_status);
                    fileInfo.put("elkCount", meta.elk_count);
                    addMetadataToFileInfo(fileInfo, meta);
                    uploadedFiles.add(fileInfo);

                } catch (SQLException e) {
                    if ("23505".equals(e.getSQLState())) {
                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("originalName", originalName);
                        fileInfo.put("error", "Duplicate image hash; skipping DB insert");
                        uploadedFiles.add(fileInfo);
                    } else {
                        throw e;
                    }
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            }
        }

        return uploadedFiles;
    }

    public static BatchResult processUnprocessedBatch() {
        return processAllUnprocessedWithAnimalDetect();
    }

    /**
     * Backlog processor that uses the custom Python model endpoint.
     * Keep this separate from AnimalDetect flow so you can swap/iterate model
     * behavior independently.
     */
    public static BatchResult processAllUnprocessedWithPythonInference() {
        PythonInferenceClient inferenceClient = new PythonInferenceClient(RestClient.builder());

        int processedCount = 0;
        int attemptedCount = 0;
        List<String> errors = new ArrayList<>();

        try (Connection conn = db.connect()) {
            List<Metadata> pending = db.getUnprocessedImages(conn, Integer.MAX_VALUE);
            attemptedCount = pending.size();

            List<ImagePayload> payloads = new ArrayList<>();
            List<Metadata> rows = new ArrayList<>();
            List<Path> tempFiles = new ArrayList<>();

            for (Metadata row : pending) {
                try {
                    if (row.cloud_uri == null || row.cloud_uri.isBlank()) {
                        throw new IllegalArgumentException("Missing cloud_uri");
                    }

                    String ext = ImageUtils.getExtension(row.cloud_uri).toLowerCase();
                    Path tempFile = Files.createTempFile("processor-py-", "." + ext);
                    downloadFromCloudUri(row.cloud_uri, tempFile);

                    String computedHash = ImageUtils.sha256(tempFile.toFile());
                    if (computedHash != null && row.sha256 != null && !row.sha256.equals(computedHash)) {
                        throw new IllegalStateException("Downloaded file hash does not match DB hash");
                    }

                    String filename = (row.filename == null || row.filename.isBlank())
                            ? row.sha256 + ".jpeg"
                            : row.filename;

                    payloads.add(new ImagePayload(filename, Files.readAllBytes(tempFile)));
                    rows.add(row);
                    tempFiles.add(tempFile);
                } catch (Exception e) {
                    errors.add("hash=" + row.sha256 + " failed to prepare: " + e.getMessage());
                }
            }

            List<Integer> counts = inferenceClient.inferCounts(payloads);
            for (int i = 0; i < rows.size(); i++) {
                Metadata row = rows.get(i);
                Integer elkCount = (i < counts.size()) ? counts.get(i) : null;
                try {
                    db.updateMetaWithDetection(conn, row.sha256, elkCount, true);
                    processedCount++;
                } catch (Exception e) {
                    errors.add("hash=" + row.sha256 + " failed to persist: " + e.getMessage());
                }
            }

            for (Path tempFile : tempFiles) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }

            return new BatchResult(attemptedCount, processedCount, errors);
        } catch (Exception e) {
            errors.add("Batch failed: " + e.getMessage());
            return new BatchResult(0, 0, errors);
        }
    }

    private static void validateUploadedFiles(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("At least one image file is required");
        }

        for (MultipartFile file : files) {
            String originalName = file.getOriginalFilename();
            if (!isAllowedImageName(originalName)) {
                throw new IllegalArgumentException("Files must be images (png, jpeg, jpg, or heic)");
            }
        }
    }

    private static List<UploadMetadataData> parseUploadMetadata(MultipartFile[] files, String metadataJson)
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<UploadMetadataData> metadataList = List.of();
        if (metadataJson != null && !metadataJson.isBlank()) {
            metadataList = objectMapper.readValue(metadataJson,
                    new TypeReference<List<UploadMetadataData>>() {
                    });
            if (metadataList.size() != files.length) {
                throw new IllegalArgumentException("metadata length must match files length");
            }
        }
        return metadataList;
    }

    private static Metadata buildMetadataForUpload(
            Path tempFile,
            String originalName,
            UploadMetadataData uploadData,
            int fileIndex) throws Exception {
        Metadata meta = new Metadata();
        meta.filename = originalName;
        meta.filesize = Files.size(tempFile);
        meta.sha256 = ImageUtils.sha256(tempFile.toFile());
        meta.cloud_uri = "";
        meta.processed_status = false;
        meta.elk_count = null;

        if (uploadData != null && uploadData.filename != null && originalName != null
                && !uploadData.filename.equals(originalName)) {
            throw new IllegalArgumentException(
                    "metadata filename mismatch at index " + fileIndex + ": expected "
                            + originalName + " but got " + uploadData.filename);
        }

        if (uploadData != null && uploadData.latitude != null && uploadData.longitude != null) {
            meta.latitude = uploadData.latitude;
            meta.longitude = uploadData.longitude;
            meta.altitude = uploadData.altitude;
            meta.gps_flag = true;
        } else {
            meta.latitude = null;
            meta.longitude = null;
            meta.altitude = null;
            meta.gps_flag = false;
        }

        meta.datetime = uploadData == null ? null : uploadData.datetime;
        meta.width = (uploadData == null || uploadData.width == null) ? 0 : uploadData.width;
        meta.height = (uploadData == null || uploadData.height == null) ? 0 : uploadData.height;
        meta.temperature_c = (uploadData == null || uploadData.temperature_c == null) ? 0.0 : uploadData.temperature_c;
        meta.humidity = (uploadData == null || uploadData.humidity == null) ? 0.0 : uploadData.humidity;
        meta.weather_desc = uploadData == null ? null : uploadData.weather_desc;
        return meta;
    }

    private static boolean isAllowedImageName(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String lower = filename.toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizedStorageExtension(String ext) {
        if (ext == null || ext.isBlank()) {
            return ".bin";
        }
        if ("jpg".equals(ext) || "jpeg".equals(ext)) {
            return ".jpeg";
        }
        return "." + ext;
    }

    private static void addMetadataToFileInfo(Map<String, Object> fileInfo, Metadata meta) {
        fileInfo.put("filename", meta.filename);
        fileInfo.put("filesizeBytes", meta.filesize);
        fileInfo.put("width", meta.width);
        fileInfo.put("height", meta.height);

        fileInfo.put("gpsFlag", meta.gps_flag);
        fileInfo.put("latitude", meta.latitude);
        fileInfo.put("longitude", meta.longitude);
        fileInfo.put("altitude", meta.altitude);

        fileInfo.put("datetimeTaken", meta.datetime);

        fileInfo.put("temperatureC", meta.temperature_c);
        fileInfo.put("humidity", meta.humidity);

        fileInfo.put("elkCount", meta.elk_count);
    }

    public static BatchResult processAllUnprocessedWithAnimalDetect() {
        int processedCount = 0;
        int attemptedCount = 0;
        List<String> errors = new ArrayList<>();

        AnimalDetectAPI animalDetectAPI;
        try {
            String apiKey = AnimalDetectAPI.resolveApiKey(null);
            animalDetectAPI = new AnimalDetectAPI(apiKey, 60);
        } catch (Exception e) {
            errors.add("AnimalDetect API not available: " + e.getMessage());
            return new BatchResult(0, 0, errors);
        }

        try (Connection conn = db.connect()) {
            List<Metadata> pending = db.getUnprocessedImages(conn, Integer.MAX_VALUE);
            attemptedCount = pending.size();

            for (Metadata row : pending) {
                Path tempFile = null;
                try {
                    if (row.cloud_uri == null || row.cloud_uri.isBlank()) {
                        throw new IllegalArgumentException("Missing cloud_uri");
                    }

                    String ext = ImageUtils.getExtension(row.cloud_uri).toLowerCase();
                    tempFile = Files.createTempFile("processor-", "." + ext);
                    downloadFromCloudUri(row.cloud_uri, tempFile);

                    String computedHash = ImageUtils.sha256(tempFile.toFile());
                    if (computedHash != null && row.sha256 != null && !row.sha256.equals(computedHash)) {
                        throw new IllegalStateException("Downloaded file hash does not match DB hash");
                    }

                    byte[] imageBytes = Files.readAllBytes(tempFile);
                    String filename = (row.filename == null || row.filename.isBlank())
                            ? row.sha256 + ".jpeg"
                            : row.filename;

                    Map<String, Object> response = animalDetectAPI.callAnimalDetectAPIWithFallback(
                            imageBytes,
                            filename,
                            "USA",
                            0.2);
                    List<String> predictionLines = animalDetectAPI.formatDetectionsForConsole(response);
                    if (predictionLines.isEmpty()) {
                        logger.info("Model predictions for " + filename + ": none");
                    } else {
                        for (String predictionLine : predictionLines) {
                            logger.info("Model predictions for " + filename + " -> " + predictionLine);
                        }
                    }

                    int elkCount = animalDetectAPI.countElkFromResponse(response, 0.2);
                    db.updateMetaWithDetection(conn, row.sha256, elkCount, true);
                    processedCount++;
                } catch (Exception e) {
                    errors.add("hash=" + row.sha256 + " failed: " + e.getMessage());
                } finally {
                    if (tempFile != null) {
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            return new BatchResult(attemptedCount, processedCount, errors);
        } catch (Exception e) {
            errors.add("Batch failed: " + e.getMessage());
            return new BatchResult(0, 0, errors);
        }
    }

    private static void downloadFromCloudUri(String cloudUri, Path destinationPath) throws Exception {
        if (!cloudUri.startsWith("gs://")) {
            throw new IllegalArgumentException("Unsupported cloud_uri: " + cloudUri);
        }

        String withoutScheme = cloudUri.substring("gs://".length());
        int slashIdx = withoutScheme.indexOf('/');
        if (slashIdx <= 0 || slashIdx == withoutScheme.length() - 1) {
            throw new IllegalArgumentException("Invalid gs:// URI: " + cloudUri);
        }

        String bucket = withoutScheme.substring(0, slashIdx);
        String objectName = withoutScheme.substring(slashIdx + 1);

        String projectId = SecretConfig.getRequired("GCS_PROJECT_ID");
        String credentialsPath = SecretConfig.getRequired("GCS_CREDENTIALS_PATH");

        StorageOptions.Builder storageBuilder = StorageOptions.newBuilder().setProjectId(projectId);
        if (credentialsPath != null && Files.exists(Paths.get(credentialsPath))) {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new FileInputStream(credentialsPath))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            storageBuilder.setCredentials(credentials);
        }
        Storage storage = storageBuilder.build().getService();
        Blob blob = storage.get(BlobId.of(bucket, objectName));
        if (blob == null) {
            throw new IllegalStateException("Object not found: " + cloudUri);
        }
        blob.downloadTo(destinationPath);
    }

    public static void main(String[] args) {
        BatchResult result = processAllUnprocessedWithAnimalDetect();
        System.out.println("attempted=" + result.attempted + ", processed=" + result.processed);
        if (!result.errors.isEmpty()) {
            for (String error : result.errors) {
                System.err.println(error);
            }
        }
    }

}

package com.example;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// mvn spring-boot:run
@RestController
@RequestMapping("/api")
// @CrossOrigin(origins = "http://localhost:3000") // Switched to global cors
// config
public class FileUploadController {
    private static final Logger logger = Logger.getLogger(FileUploadController.class.getName());

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

    private static final String BUCKET_NAME = "cs370perc-bucket";
    private static final String[] ALLOWED_EXTENSIONS = { ".png", ".jpg", ".jpeg", ".heic" };

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

    private static void ensureSchema(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("create schema if not exists cs370");
            s.execute("set search_path to cs370");
            s.execute("create table if not exists images ("
                    + "id serial primary key, "
                    + "img_hash varchar(64) unique, "
                    + "cloud_uri text not null, "
                    + "filename text, "
                    + "filesize_bytes bigint, "
                    + "width int, "
                    + "height int, "
                    + "gps_flag boolean, "
                    + "latitude double precision, "
                    + "longitude double precision, "
                    + "altitude double precision, "
                    + "datetime_taken timestamptz, "
                    + "datetime_uploaded timestamptz default now(), "
                    + "temperature_c double precision, "
                    + "humidity double precision, "
                    + "weather_desc text, "
                    + "processed_status boolean"
                    + ")");
            s.execute("alter table images add column if not exists filename text");
            s.execute("alter table images add column if not exists filesize_bytes bigint");
            s.execute("alter table images add column if not exists width int");
            s.execute("alter table images add column if not exists height int");
            s.execute("alter table images add column if not exists gps_flag boolean");
            s.execute("alter table images add column if not exists latitude double precision");
            s.execute("alter table images add column if not exists longitude double precision");
            s.execute("alter table images add column if not exists altitude double precision");
            s.execute("alter table images add column if not exists datetime_taken timestamptz");
            s.execute("alter table images add column if not exists datetime_uploaded timestamptz default now()");
            s.execute("alter table images add column if not exists temperature_c double precision");
            s.execute("alter table images add column if not exists humidity double precision");
            s.execute("alter table images add column if not exists weather_desc text");
            s.execute("alter table images add column if not exists elk_count integer");
            s.execute("alter table images add column if not exists processed_status boolean default false");
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFileInstantProcessed(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "metadata", required = false) String metadataJson) {
        try {
            for (MultipartFile file : files) {
                String originalName = file.getOriginalFilename();
                if (!isAllowedImageName(originalName)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Files must be images (png, jpeg, jpg, or heic)"));
                }
            }

            ObjectMapper objectMapper = new ObjectMapper();
            List<UploadMetadataData> metadataList = List.of();
            if (metadataJson != null && !metadataJson.isBlank()) {
                metadataList = objectMapper.readValue(metadataJson,
                        new TypeReference<List<UploadMetadataData>>() {
                        });
                if (metadataList.size() != files.length) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "metadata length must match files length"));
                }
            }

            // Initialize AnimalDetect API for live detection
            AnimalDetectAPI animalDetectAPI = null;
            try {
                String apiKey = AnimalDetectAPI.resolveApiKey(null);
                animalDetectAPI = new AnimalDetectAPI(apiKey, 60);
            } catch (Exception e) {
                logger.log(Level.WARNING, "AnimalDetect API not available", e);
            }

            List<Map<String, Object>> uploadedFiles = new ArrayList<>();
            try (Connection conn = db.connect()) {
                ensureSchema(conn);

                for (int i = 0; i < files.length; i++) {
                    MultipartFile file = files[i];
                    String originalName = file.getOriginalFilename();
                    String suffix = (originalName == null || originalName.isBlank()) ? ".bin" : "-" + originalName;
                    Path tempFile = Files.createTempFile("upload-", suffix);
                    try {
                        Files.write(tempFile, file.getBytes());

                        String ext = ImgDet.getExtension(originalName == null ? "" : originalName).toLowerCase();
                        String dotExt = normalizedStorageExtension(ext);

                        Metadata meta = new Metadata();
                        meta.filename = originalName;
                        meta.filesize = Files.size(tempFile);
                        meta.sha256 = ImgHash.sha256(tempFile.toFile());
                        meta.cloud_uri = "";
                        meta.processed_status = false;
                        meta.elk_count = null;

                        UploadMetadataData uploadData = metadataList.isEmpty() ? null : metadataList.get(i);
                        if (uploadData != null && uploadData.filename != null && originalName != null
                                && !uploadData.filename.equals(originalName)) {
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body(Map.of("error",
                                            "metadata filename mismatch at index " + i + ": expected "
                                                    + originalName + " but got " + uploadData.filename));
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
                        meta.temperature_c = (uploadData == null || uploadData.temperature_c == null) ? 0.0
                                : uploadData.temperature_c;
                        meta.humidity = (uploadData == null || uploadData.humidity == null) ? 0.0 : uploadData.humidity;
                        meta.weather_desc = uploadData == null ? null : uploadData.weather_desc;

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

                        System.out.println("AnimalDetect API initialized: " + (animalDetectAPI != null));
                        // ===== LIVE DETECTION: Call AnimalDetect API =====
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

                                System.out.println("Live detection completed: " + originalName +
                                        " - " + elkCount + " elk detected");
                            } catch (Exception detectionError) {
                                logger.log(Level.WARNING,
                                        "Animal detection failed for " + originalName, detectionError);
                                meta.elk_count = null;
                                meta.processed_status = false;
                            }
                        }

                        // Update metadata in database with detection results
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

            return ResponseEntity
                    .ok(Map.of("message", "Files uploaded successfully (with live detection)", "files", uploadedFiles));
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.log(Level.SEVERE, "Upload failed in uploadFileInstantProcessed", e);
            System.err.print(sw.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "stackTrace", sw.toString()));
        }
    }

    // @PostMapping(value = "/upload/unprocessed", consumes =
    // MediaType.MULTIPART_FORM_DATA_VALUE)
    // public ResponseEntity<?> uploadFileUnprocessed(
    // @RequestParam("files") MultipartFile[] files,
    // @RequestParam(value = "metadata", required = false) String metadataJson) {
    // try {
    // for (MultipartFile file : files) {
    // String originalName = file.getOriginalFilename();
    // if (!isAllowedImageName(originalName)) {
    // return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    // .body(Map.of("error", "Files must be images (png, jpeg, jpg, or heic)"));
    // }
    // }

    // ObjectMapper objectMapper = new ObjectMapper();
    // List<UploadMetadataData> metadataList = List.of();
    // if (metadataJson != null && !metadataJson.isBlank()) {
    // metadataList = objectMapper.readValue(metadataJson,
    // new TypeReference<List<UploadMetadataData>>() {
    // });
    // if (metadataList.size() != files.length) {
    // return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    // .body(Map.of("error", "metadata length must match files length"));
    // }
    // }

    // List<Map<String, Object>> uploadedFiles = new ArrayList<>();
    // try (Connection conn = db.connect()) {
    // ensureSchema(conn);

    // for (int i = 0; i < files.length; i++) {
    // MultipartFile file = files[i];
    // String originalName = file.getOriginalFilename();
    // String suffix = (originalName == null || originalName.isBlank()) ? ".bin" :
    // "-" + originalName;
    // Path tempFile = Files.createTempFile("upload-", suffix);
    // try {
    // Files.write(tempFile, file.getBytes());

    // String ext = ImgDet.getExtension(originalName == null ? "" :
    // originalName).toLowerCase();
    // String dotExt = normalizedStorageExtension(ext);

    // Metadata meta = new Metadata();
    // meta.filename = originalName;
    // meta.filesize = Files.size(tempFile);
    // meta.sha256 = ImgHash.sha256(tempFile.toFile());
    // meta.cloud_uri = "";
    // meta.processed_status = false;
    // meta.elk_count = null;

    // UploadMetadataData uploadData = metadataList.isEmpty() ? null :
    // metadataList.get(i);
    // if (uploadData != null && uploadData.filename != null && originalName != null
    // && !uploadData.filename.equals(originalName)) {
    // return ResponseEntity.status(HttpStatus.BAD_REQUEST)
    // .body(Map.of("error",
    // "metadata filename mismatch at index " + i + ": expected "
    // + originalName + " but got " + uploadData.filename));
    // }
    // if (uploadData != null && uploadData.latitude != null && uploadData.longitude
    // != null) {
    // meta.latitude = uploadData.latitude;
    // meta.longitude = uploadData.longitude;
    // meta.altitude = uploadData.altitude;
    // meta.gps_flag = true;
    // } else {
    // meta.latitude = null;
    // meta.longitude = null;
    // meta.altitude = null;
    // meta.gps_flag = false;
    // }

    // meta.datetime = uploadData == null ? null : uploadData.datetime;
    // meta.width = (uploadData == null || uploadData.width == null) ? 0 :
    // uploadData.width;
    // meta.height = (uploadData == null || uploadData.height == null) ? 0 :
    // uploadData.height;
    // meta.temperature_c = (uploadData == null || uploadData.temperature_c == null)
    // ? 0.0
    // : uploadData.temperature_c;
    // meta.humidity = (uploadData == null || uploadData.humidity == null) ? 0.0 :
    // uploadData.humidity;
    // meta.weather_desc = uploadData == null ? null : uploadData.weather_desc;

    // String objectName = meta.sha256 + dotExt;

    // Metadata existing = db.getImageByHash(conn, meta.sha256);
    // if (existing != null) {
    // Map<String, Object> fileInfo = new HashMap<>();
    // fileInfo.put("originalName", originalName);
    // fileInfo.put("status", "duplicate hash; skipped upload");
    // fileInfo.put("objectName", objectName);
    // fileInfo.put("sha256", existing.sha256);
    // fileInfo.put("cloudUri", existing.cloud_uri);
    // addMetadataToFileInfo(fileInfo, existing);
    // uploadedFiles.add(fileInfo);
    // continue;
    // }

    // GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
    // meta.cloud_uri = "gs://" + BUCKET_NAME + "/" + objectName;
    // db.insertMeta(conn, meta);

    // Map<String, Object> fileInfo = new HashMap<>();
    // fileInfo.put("originalName", originalName);
    // fileInfo.put("objectName", objectName);
    // fileInfo.put("cloudUri", meta.cloud_uri);
    // fileInfo.put("sha256", meta.sha256);
    // fileInfo.put("processedStatus", meta.processed_status);
    // fileInfo.put("elkCount", meta.elk_count);
    // addMetadataToFileInfo(fileInfo, meta);
    // uploadedFiles.add(fileInfo);
    // } catch (SQLException e) {
    // if ("23505".equals(e.getSQLState())) {
    // Map<String, Object> fileInfo = new HashMap<>();
    // fileInfo.put("originalName", originalName);
    // fileInfo.put("error", "Duplicate image hash; skipping DB insert");
    // uploadedFiles.add(fileInfo);
    // } else {
    // throw e;
    // }
    // } finally {
    // Files.deleteIfExists(tempFile);
    // }
    // }
    // }

    // return ResponseEntity
    // .ok(Map.of("message", "Files uploaded successfully (unprocessed)", "files",
    // uploadedFiles));
    // } catch (Exception e) {
    // StringWriter sw = new StringWriter();
    // e.printStackTrace(new PrintWriter(sw));
    // logger.log(Level.SEVERE, "Upload failed in uploadFileUnprocessed", e);
    // System.err.print(sw.toString());
    // return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    // .body(Map.of("error", e.getMessage(), "stackTrace", sw.toString()));
    // }
    // }

    private static void addMetadataToFileInfo(Map<String, Object> fileInfo, Metadata meta) {
        // File information
        fileInfo.put("filename", meta.filename);
        fileInfo.put("filesizeBytes", meta.filesize);
        fileInfo.put("width", meta.width);
        fileInfo.put("height", meta.height);

        // GPS information
        fileInfo.put("gpsFlag", meta.gps_flag);
        fileInfo.put("latitude", meta.latitude);
        fileInfo.put("longitude", meta.longitude);
        fileInfo.put("altitude", meta.altitude);

        // Datetime information
        fileInfo.put("datetimeTaken", meta.datetime);

        // Weather information
        fileInfo.put("temperatureC", meta.temperature_c);
        fileInfo.put("humidity", meta.humidity);

        // Inference information
        fileInfo.put("elkCount", meta.elk_count);
    }
}
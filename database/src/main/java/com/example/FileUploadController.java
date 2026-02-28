package com.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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

// mvn spring-boot:run
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class FileUploadController {

    private static class GpsUploadData {
        public String filename;
        public Double longitude;
        public Double latitude;
        public Double altitude;
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
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFileUnprocessed(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "gpsData", required = false) String gpsDataJson) {
        try {
            for (MultipartFile file : files) {
                String originalName = file.getOriginalFilename();
                if (!isAllowedImageName(originalName)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Files must be images (png, jpeg, jpg, or heic)"));
                }
            }

            List<GpsUploadData> gpsDataList = List.of();
            if (gpsDataJson != null && !gpsDataJson.isBlank()) {
                ObjectMapper objectMapper = new ObjectMapper();
                gpsDataList = objectMapper.readValue(gpsDataJson, new TypeReference<List<GpsUploadData>>() {
                });
                if (gpsDataList.size() != files.length) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "gpsData length must match files length"));
                }
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
                        String dotExt = (ext == null || ext.isBlank()) ? ".bin" : "." + ext;

                        Metadata meta = new Metadata();
                        meta.filename = originalName;
                        meta.filesize = Files.size(tempFile);
                        meta.sha256 = ImgHash.sha256(tempFile.toFile());
                        meta.cloud_uri = "";
                        meta.processed_status = false;

                        GpsUploadData gps = gpsDataList.isEmpty() ? null : gpsDataList.get(i);
                        if (gps != null && gps.filename != null && originalName != null
                                && !gps.filename.equals(originalName)) {
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body(Map.of("error",
                                            "gpsData filename mismatch at index " + i + ": expected "
                                                    + originalName + " but got " + gps.filename));
                        }
                        if (gps != null && gps.latitude != null && gps.longitude != null) {
                            meta.latitude = gps.latitude;
                            meta.longitude = gps.longitude;
                            meta.altitude = gps.altitude;
                            meta.gps_flag = true;
                        } else {
                            meta.latitude = null;
                            meta.longitude = null;
                            meta.altitude = null;
                            meta.gps_flag = false;
                        }

                        meta.datetime = null;
                        meta.width = 0;
                        meta.height = 0;
                        meta.temperature_c = 0.0;
                        meta.humidity = 0.0;
                        meta.weather_desc = null;

                        String objectName = meta.sha256 + dotExt;

                        Metadata existing = db.getImageByHash(conn, meta.sha256);
                        if (existing != null) {
                            System.out.println("Duplicate image hash detected; skipping upload. hash=" + meta.sha256
                                    + ", originalName=" + originalName);
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

                        // Upload original file without converting file type
                        GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                        meta.cloud_uri = "gs://" + BUCKET_NAME + "/" + objectName;
                        db.insertMeta(conn, meta);

                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("originalName", originalName);
                        fileInfo.put("objectName", objectName);
                        fileInfo.put("cloudUri", meta.cloud_uri);
                        fileInfo.put("sha256", meta.sha256);
                        fileInfo.put("processedStatus", false);
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
                    .ok(Map.of("message", "Files uploaded successfully (unprocessed)", "files", uploadedFiles));
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            System.err.println(sw.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "stackTrace", sw.toString()));
        }
    }

    @PostMapping(value = "/upload/instant", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFileInstantProcessing(@RequestParam("files") MultipartFile[] files) {
        try {
            for (MultipartFile file : files) {
                String originalName = file.getOriginalFilename();
                if (!isAllowedImageName(originalName)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("error", "Files must be images (png, jpeg, jpg, or heic)"));
                }
            }

            List<Map<String, Object>> uploadedFiles = new ArrayList<>();
            try (Connection conn = db.connect()) {
                ensureSchema(conn);

                for (MultipartFile file : files) {
                    String originalName = file.getOriginalFilename();
                    String suffix = (originalName == null || originalName.isBlank()) ? ".bin" : "-" + originalName;
                    Path tempFile = Files.createTempFile("upload-", suffix);
                    File jpgFile = null;
                    try {
                        Files.write(tempFile, file.getBytes());

                        String ext = ImgDet.getExtension(originalName == null ? "" : originalName).toLowerCase();
                        if (ext.equals("png")) {
                            jpgFile = ImgDet.convertPngToJpg(tempFile.toFile());
                        } else {
                            jpgFile = ImgDet.convertToJpg(tempFile.toFile());
                        }
                        Metadata meta = db.loadMetadata(jpgFile);
                        String objectName = meta.sha256 + ".jpg";

                        Metadata existing = db.getImageByHash(conn, meta.sha256);
                        if (existing != null) {
                            System.out.println("Duplicate image hash detected; skipping upload. hash=" + meta.sha256
                                    + ", originalName=" + originalName);
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

                        // upload to GCS and insert metadata into DB
                        GoogleCloudStorageAPI.uploadFile(jpgFile.getAbsolutePath(), objectName);
                        meta.cloud_uri = "gs://" + BUCKET_NAME + "/" + objectName;
                        db.insertMeta(conn, meta);

                        Map<String, Object> fileInfo = new HashMap<>();
                        fileInfo.put("originalName", originalName);
                        fileInfo.put("objectName", objectName);
                        fileInfo.put("cloudUri", meta.cloud_uri);
                        fileInfo.put("sha256", meta.sha256);
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
                        if (jpgFile != null && !jpgFile.equals(tempFile.toFile())) {
                            Files.deleteIfExists(jpgFile.toPath());
                        }
                        Files.deleteIfExists(tempFile);
                    }
                }
            }

            return ResponseEntity
                    .ok(Map.of("message", "Files uploaded successfully (instant processing)", "files", uploadedFiles));
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            System.err.println(sw.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "stackTrace", sw.toString()));
        }
    }

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
    }
}

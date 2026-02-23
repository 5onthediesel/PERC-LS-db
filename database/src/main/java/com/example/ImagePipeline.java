package com.example;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class ImagePipeline {

    public static class Result {
        public final Metadata meta;
        public final String filePath;
        public final Exception error;

        public Result(Metadata meta, String filePath, Exception error) {
            this.meta = meta;
            this.filePath = filePath;
            this.error = error;
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * Process an image from MultipartFile: extract metadata, save file locally, and store in PostgreSQL.
     * GCS upload and wildlife inference wiring deferred.
     */
    public static Result processImage(MultipartFile file) throws Exception {
        String original = file.getOriginalFilename();
        String suffix = (original == null || original.isBlank()) ? ".bin" : "-" + original;
        Path tempFile = Files.createTempFile("pipeline-", suffix);
        try {
            Files.write(tempFile, file.getBytes());
            return processImage(tempFile.toFile());
        } finally {
            try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
        }
    }

    /**
     * Process a local File: extract metadata, save file reference, and store in PostgreSQL.
     */
    public static Result processImage(File inputFile) {
        try {
            // Validate file exists
            if (inputFile == null || !inputFile.exists()) {
                return new Result(null, null, new IOException("Input file does not exist"));
            }

            // Extract metadata using existing db.java utilities
            Metadata meta = db.loadMetadata(inputFile);
            if (meta == null) {
                return new Result(null, null, new IOException("Failed to load metadata from file"));
            }

            // Save file locally (simple approach: store in temp with hash name)
            String fileName = meta.sha256 + ".jpg";
            Path savedPath = Files.createTempFile("image-", "-" + fileName);
            Files.copy(inputFile.toPath(), savedPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            meta.cloud_uri = savedPath.toString(); // For now, just use local path

            // !!Wire in later: GCS upload via GoogleCloudStorageAPI.uploadFile()
            // !!Wire in later: Wildlife inference via ImgDet model

            // Save metadata to PostgreSQL database
            try (Connection conn = db.connect()) {
                insertMeta(conn, meta, savedPath.toString());
            } catch (SQLException e) {
                return new Result(meta, savedPath.toString(), e);
            }

            return new Result(meta, savedPath.toString(), null);

        } catch (Exception e) {
            return new Result(null, null, e);
        }
    }

    /**
     * Insert metadata into PostgreSQL database (cs370.images table).
     */
    private static void insertMeta(Connection conn, Metadata meta, String filePath) throws SQLException {
        String sql = "INSERT INTO cs370.images (" +
                "img_hash, filename, filesize_bytes, width, height, " +
                "gps_flag, latitude, longitude, altitude, " +
                "datetime_taken, temperature_c, humidity, weather_desc, cloud_uri" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS'), ?, ?, ?, ?)";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, meta.sha256);
            ps.setString(2, meta.filename);
            ps.setLong(3, meta.filesize);
            ps.setInt(4, meta.width);
            ps.setInt(5, meta.height);
            ps.setBoolean(6, meta.gps_flag);

            if (meta.gps_flag) {
                ps.setDouble(7, meta.latitude);
                ps.setDouble(8, meta.longitude);
                ps.setDouble(9, meta.altitude);
            } else {
                ps.setNull(7, java.sql.Types.DOUBLE);
                ps.setNull(8, java.sql.Types.DOUBLE);
                ps.setNull(9, java.sql.Types.DOUBLE);
            }

            ps.setString(10, meta.datetime);
            ps.setDouble(11, meta.temperature_c != null ? meta.temperature_c : 0.0);
            ps.setDouble(12, meta.humidity != null ? meta.humidity : 0.0);
            ps.setString(13, meta.weather_desc != null ? meta.weather_desc : "");
            ps.setString(14, filePath);

            ps.executeUpdate();
        }
    }
}


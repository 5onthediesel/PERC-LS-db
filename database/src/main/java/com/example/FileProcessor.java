package com.example;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

public class FileProcessor {

    private static final int DEFAULT_BATCH_SIZE = 16;

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

    public static BatchResult processUnprocessedBatch() {
        return processUnprocessedBatch(DEFAULT_BATCH_SIZE);
    }

    public static String extractDateTime(File imageFile) {
        try {
            if (imageFile == null || !imageFile.exists()) {
                return null;
            }

            String ext = ImgDet.getExtension(imageFile.getName()).toLowerCase();

            // Handle PNG files - try EXIF extraction, fallback to converting first
            if (ext.equals("png")) {
                try {
                    EXIFParser.ExifData exif = ImgDet.parseExifFromPng(imageFile);
                    if (exif != null && exif.date != null && !exif.date.isEmpty()) {
                        return exif.date;
                    }
                } catch (Exception e) {
                    // PNG EXIF extraction failed, will try converting below
                    System.out.println("Warning: Failed to extract EXIF from PNG " +
                            imageFile.getName() + ": " + e.getMessage());
                }

                // Fallback: Convert PNG to JPG to extract EXIF more reliably
                File jpgFile = null;
                try {
                    jpgFile = ImgDet.convertPngToJpg(imageFile);
                    EXIFParser.ExifData exif = EXIFParser.parse(jpgFile.getAbsolutePath());
                    String datetime = (exif != null) ? exif.date : null;
                    if (jpgFile != null && !jpgFile.equals(imageFile)) {
                        jpgFile.delete();
                    }
                    return datetime;
                } catch (Exception e) {
                    if (jpgFile != null && !jpgFile.equals(imageFile)) {
                        try {
                            jpgFile.delete();
                        } catch (Exception ignored) {
                        }
                    }
                    return null;
                }
            }

            // Handle JPEG and other formats
            EXIFParser.ExifData exif = EXIFParser.parse(imageFile.getAbsolutePath());
            return (exif != null) ? exif.date : null;

        } catch (Exception e) {
            // Return null on any error to avoid crashing metadata extraction
            System.err.println("Warning: Failed to extract datetime from " +
                    imageFile.getName() + ": " + e.getMessage());
            return null;
        }
    }

    public static BatchResult processUnprocessedBatch(int batchSize) {
        int effectiveBatchSize = Math.max(1, batchSize);
        int processedCount = 0;
        List<String> errors = new ArrayList<>();

        try (Connection conn = db.connect()) {
            List<Metadata> pending = db.getUnprocessedImages(conn, effectiveBatchSize);

            for (Metadata row : pending) {
                Path tempFile = null;
                try {
                    if (row.cloud_uri == null || row.cloud_uri.isBlank()) {
                        throw new IllegalArgumentException("Missing cloud_uri");
                    }

                    String ext = ImgDet.getExtension(row.cloud_uri).toLowerCase();
                    tempFile = Files.createTempFile("processor-", "." + ext);
                    downloadFromCloudUri(row.cloud_uri, tempFile);

                    Metadata extracted = new Metadata();
                    extracted.sha256 = ImgHash.sha256(tempFile.toFile());
                    extracted.datetime = extractDateTime(tempFile.toFile());
                    extracted.cloud_uri = row.cloud_uri;
                    extracted.filesize = tempFile.toFile().length();
                    extracted.width = ImgDet.getWidth(tempFile.toFile());
                    extracted.height = ImgDet.getHeight(tempFile.toFile());

                    // Preserve uploaded GPS values from DB (do not recompute from EXIF)
                    extracted.latitude = row.latitude;
                    extracted.longitude = row.longitude;
                    extracted.altitude = row.altitude;
                    extracted.gps_flag = (row.latitude != null && row.longitude != null);

                    // Recompute weather using uploaded GPS + extracted datetime
                    extracted.temperature_c = null;
                    extracted.humidity = null;
                    extracted.weather_desc = null;
                    db.populateWeather(extracted);

                    if (row.filename != null && !row.filename.isBlank()) {
                        extracted.filename = row.filename;
                    }

                    if (extracted.sha256 != null && row.sha256 != null
                            && !row.sha256.equals(ImgHash.sha256(tempFile.toFile()))) {
                        throw new IllegalStateException("Downloaded file hash does not match DB hash");
                    }

                    int updatedRows = db.updateProcessedMetadata(conn, row.sha256, extracted);
                    if (updatedRows == 1) {
                        processedCount++;
                    } else {
                        errors.add("No row updated for hash=" + row.sha256);
                    }
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

            return new BatchResult(pending.size(), processedCount, errors);
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

        Storage storage = StorageOptions.getDefaultInstance().getService();
        Blob blob = storage.get(BlobId.of(bucket, objectName));
        if (blob == null) {
            throw new IllegalStateException("Object not found: " + cloudUri);
        }
        blob.downloadTo(destinationPath);
    }

    public static void main(String[] args) {
        int batchSize = DEFAULT_BATCH_SIZE;
        if (args.length > 0) {
            try {
                batchSize = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        BatchResult result = processUnprocessedBatch(batchSize);
        System.out.println("attempted=" + result.attempted + ", processed=" + result.processed);
        if (!result.errors.isEmpty()) {
            for (String error : result.errors) {
                System.err.println(error);
            }
        }
    }

}

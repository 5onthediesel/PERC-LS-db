package com.example;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class MessagingController {

    /**
     * Endpoint for local testing: POST /test/send-image
     */
    @PostMapping("/test/send-image")
    public ResponseEntity<?> sendImageTest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "phone_number", required = false) String phoneNumber) {
        try {
            if (file == null || file.isEmpty())
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "File is required"));

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/"))
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Only image files are accepted"));

            List<Map<String, Object>> processed = FileProcessor.uploadAndProcessFiles(
                    new MultipartFile[] { file },
                    null);

            if (processed.isEmpty()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Pipeline processing failed",
                                "details", "No output from file processor"));
            }

            Map<String, Object> fileInfo = processed.get(0);
            if (fileInfo.containsKey("error")) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Pipeline processing failed",
                                "details", String.valueOf(fileInfo.get("error"))));
            }

            String filename = String.valueOf(fileInfo.getOrDefault("filename", fileInfo.get("originalName")));
            String sha256 = String.valueOf(fileInfo.getOrDefault("sha256", "unknown"));
            String cloudUri = String.valueOf(fileInfo.getOrDefault("cloudUri", "unknown"));

            String message = "Image processed: " + filename +
                    " | Hash: " + sha256 +
                    " | Stored at: " + cloudUri;
            Messenger.sendReply(phoneNumber, message);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("metadata", Map.of(
                    "filename", filename,
                    "sha256", sha256,
                    "cloud_uri", cloudUri));
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Twilio webhook: POST /sms
     * Twilio calls this when a landowner texts an image to the PERC number.
     */
    @PostMapping("/sms")
    public ResponseEntity<String> smsWebhook(
            @RequestParam(value = "From", required = false) String fromPhone,
            @RequestParam(value = "MediaUrl0", required = false) String mediaUrl,
            @RequestParam(value = "MediaContentType0", required = false) String mediaContentType) {

        // No image attached — prompt the user
        if (mediaUrl == null || mediaUrl.isBlank()) {
            Messenger.sendReply(fromPhone,
                    "Hi! Please text a photo of the wildlife you've observed and we'll log it to the PERC database.");
            return ResponseEntity.ok("<Response></Response>");
        }

        Path tempFile = null;
        try {
            // Download the image from Twilio's media URL
            String ext = (mediaContentType != null && mediaContentType.contains("png")) ? ".png" : ".jpg";
            tempFile = Files.createTempFile("twilio-", ext);

            try (InputStream in = URI.create(mediaUrl).toURL().openStream()) {
                Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // Run through instant pipeline: EXIF, GCS upload, DB insert
            Metadata meta = db.loadMetadata(tempFile.toFile());
            String objectName = meta.sha256 + ".jpg";

            // Check for duplicate
            try (java.sql.Connection conn = db.connect()) {
                Metadata existing = db.getImageByHash(conn, meta.sha256);
                if (existing != null) {
                    Messenger.sendReply(fromPhone,
                            "It looks like we've already received this photo! No worries, it's already in the database.");
                    return ResponseEntity.ok("<Response></Response>");
                }

                GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                meta.cloud_uri = "gs://" + "cs370perc-bucket" + "/" + objectName;
                db.insertMeta(conn, meta);
            }

            // Build reply based on whether GPS was extracted
            String reply;
            if (meta.gps_flag) {
                String lat = String.format("%.5f", meta.latitude);
                String lon = String.format("%.5f", meta.longitude);
                String when = (meta.datetime != null) ? meta.datetime : "unknown time";
                String weather = (meta.weather_desc != null) ? " Weather: " + meta.weather_desc + "." : "";
                reply = "Thanks! Photo received and logged. " +
                        "Location: " + lat + ", " + lon + " at " + when + "." + weather +
                        " We'll scan it for elk activity shortly.";
            } else {
                reply = "Thanks for the photo! We received it, but couldn't detect a GPS location. " +
                        "Could you reply with your approximate coordinates or ranch name so we can log it accurately?";
            }

            Messenger.sendReply(fromPhone, reply);
            return ResponseEntity.ok("<Response></Response>");

        } catch (Exception e) {
            System.err.println("SMS webhook error: " + e.getMessage());
            Messenger.sendReply(fromPhone,
                    "Sorry, something went wrong processing your photo. Please try again or contact PERC directly.");
            return ResponseEntity.ok("<Response></Response>");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
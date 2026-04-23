package com.example;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
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
     * Inputs: file (MultipartFile) — image file to process;
     * phone_number (String, optional) — phone number to send the result
     * notification to
     * Outputs: ResponseEntity<?> — 200 OK with status/metadata map on success;
     * 400 Bad Request if file is missing or not an image;
     * 500 Internal Server Error on processing failure
     * Functionality: Local test endpoint (POST /test/send-image) that runs a single
     * image through the
     * full upload pipeline and sends an SMS-style notification via Messenger.
     * Dependencies: FileProcessor.uploadAndProcessFiles, Messenger.sendReply,
     * org.springframework.web.multipart.MultipartFile
     * Called by: HTTP clients during local development/testing via POST
     * /test/send-image
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
     * Inputs: fromPhone (String) — sender's phone number (Twilio "From" param);
     * mediaUrl (String) — URL of the attached image hosted by Twilio;
     * mediaContentType (String) — MIME type of the attached image
     * Outputs: ResponseEntity<String> — always 200 OK with TwiML
     * "<Response></Response>"
     * (Twilio requires a 200 even on errors)
     * Functionality: Twilio webhook handler (POST /sms) that downloads an MMS image
     * from Twilio,
     * runs it through the pipeline (EXIF, GCS upload, DB insert), and replies to
     * the
     * landowner with GPS/weather info or a duplicate notice.
     * Dependencies: db.loadMetadata, db.connect, db.getImageByHash, db.insertMeta,
     * GoogleCloudStorageAPI.uploadFile, Messenger.sendReply, java.net.URI,
     * java.nio.file.Files
     * Called by: Twilio platform via POST /sms when a landowner texts a photo to
     * the PERC number
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
                meta.cloud_uri = "gs://" + "postgresperc-bucket" + "/" + objectName;
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

    /**
     * Inputs: fromEmail (String) — sender's email address (SendGrid "from" param);
     * subject (String) — email subject line (SendGrid "subject" param);
     * attachment1-10 (MultipartFile) — image attachments sent by SendGrid
     * Outputs: ResponseEntity<String> — always 200 OK with an empty body
     * (SendGrid requires a 200 to stop retrying)
     * Functionality: SendGrid Inbound Parse webhook handler (POST
     * /webhook/inbound-email) that
     * processes up to 10 image attachments, runs each valid image through the
     * full pipeline (EXIF, GCS, DB, AnimalDetect), and sends a reply email with
     * processing summary; skips duplicates.
     * Dependencies: db.loadMetadata, db.connect, db.getImageByHash, db.insertMeta,
     * db.updateMetaWithDetection, GoogleCloudStorageAPI.uploadFile,
     * AnimalDetectAPI, isAllowedImageType, sendReplyEmail, SecretConfig,
     * org.springframework.web.multipart.MultipartFile, com.sendgrid
     * Called by: SendGrid platform via POST /webhook/inbound-email when an email is
     * received at the configured inbound parse address
     */
    @PostMapping("/webhook/inbound-email")
    public ResponseEntity<String> sendGridEmailWebhook(
            @RequestParam(value = "from", required = false) String fromEmail,
            @RequestParam(value = "subject", required = false) String subject,
            @RequestParam(value = "attachment1", required = false) MultipartFile attachment1,
            @RequestParam(value = "attachment2", required = false) MultipartFile attachment2,
            @RequestParam(value = "attachment3", required = false) MultipartFile attachment3,
            @RequestParam(value = "attachment4", required = false) MultipartFile attachment4,
            @RequestParam(value = "attachment5", required = false) MultipartFile attachment5,
            @RequestParam(value = "attachment6", required = false) MultipartFile attachment6,
            @RequestParam(value = "attachment7", required = false) MultipartFile attachment7,
            @RequestParam(value = "attachment8", required = false) MultipartFile attachment8,
            @RequestParam(value = "attachment9", required = false) MultipartFile attachment9,
            @RequestParam(value = "attachment10", required = false) MultipartFile attachment10) {

        System.out.println("[SendGrid] Inbound email from: " + fromEmail + ", subject: " + subject);
        String replyTarget = extractEmailAddress(fromEmail);
        if (replyTarget == null) {
            // Fall back to raw value when parsing fails so caller still gets an explicit
            // send error log.
            replyTarget = fromEmail;
        }

        boolean foundImage = false;
        int processedImages = 0;
        int duplicateImages = 0;
        int failedImages = 0;
        int modelInvocations = 0;
        int modelSuccesses = 0;
        int modelFailures = 0;
        List<String> allImageStatusLines = new ArrayList<>();

        AnimalDetectAPI animalDetectAPI = null;
        try {
            String apiKey = AnimalDetectAPI.resolveApiKey(null);
            animalDetectAPI = new AnimalDetectAPI(apiKey, 60);
        } catch (Exception e) {
            System.err.println("[SendGrid] AnimalDetect API unavailable for this request: " + e.getMessage());
        }

        MultipartFile[] attachments = { attachment1, attachment2, attachment3, attachment4, attachment5,
                attachment6, attachment7, attachment8, attachment9, attachment10 };

        for (int i = 0; i < attachments.length; i++) {
            MultipartFile attachment = attachments[i];
            if (attachment == null || attachment.isEmpty())
                continue;

            try {
                String contentType = attachment.getContentType();
                if (contentType == null || !isAllowedImageType(contentType)) {
                    System.out.println("[SendGrid] Skipping non-image attachment: " + contentType);
                    continue;
                }

                foundImage = true;
                String ext = contentType.contains("png") ? ".png" : contentType.contains("heic") ? ".heic" : ".jpg";
                String originalFilename = attachment.getOriginalFilename();
                String attachmentDisplayName = resolveAttachmentDisplayName(originalFilename, ext);
                System.out.println("[SendGrid] Processing attachment" + (i + 1)
                        + " name=" + attachmentDisplayName
                        + " type=" + contentType
                        + " bytes=" + attachment.getSize());
                Path tempFile = Files.createTempFile("sendgrid-", ext);

                try {
                    Files.write(tempFile, attachment.getBytes());

                    Metadata meta = db.loadMetadata(tempFile.toFile());
                    meta.processed_status = false;
                    String objectName = meta.sha256 + ext;

                    try (java.sql.Connection conn = db.connect()) {
                        // Duplicate check
                        Metadata existing = db.getImageByHash(conn, meta.sha256);
                        if (existing != null) {
                            System.out.println("[SendGrid] Duplicate image, skipping: " + meta.sha256);
                            duplicateImages++;
                            allImageStatusLines.add(attachmentDisplayName + ": already in database!");
                            continue;
                        }

                        // Upload to GCS
                        GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                        meta.cloud_uri = "gs://" + SecretConfig.getRequired("GCS_BUCKET_NAME") + "/" + objectName;
                        meta.filename = attachmentDisplayName;

                        db.insertMeta(conn, meta);

                        // Run AnimalDetect immediately (same as EmailProcessor)
                        try {
                            if (animalDetectAPI != null) {
                                modelInvocations++;
                                System.out.println("[SendGrid] Running AnimalDetect for attachment" + (i + 1)
                                        + " hash=" + meta.sha256);
                                java.util.Map<String, Object> response = animalDetectAPI
                                        .callAnimalDetectAPIWithFallback(
                                                attachment.getBytes(), meta.filename, "USA", 0.2);
                                meta.elk_count = animalDetectAPI.countElkFromResponse(response, 0.2);
                                meta.processed_status = true;
                                modelSuccesses++;
                                System.out.println("[SendGrid] AnimalDetect complete for attachment" + (i + 1)
                                        + " elk_count=" + meta.elk_count);
                            } else {
                                meta.elk_count = null;
                                meta.processed_status = false;
                                modelFailures++;
                                System.err.println("[SendGrid] AnimalDetect skipped for attachment" + (i + 1)
                                        + " because API client is unavailable");
                            }

                            processedImages++;
                            String elkStatus = formatElkCount(meta.elk_count) + " elk";
                            allImageStatusLines.add(meta.filename + ": " + elkStatus);
                        } catch (Exception detectionError) {
                            System.err.println("[SendGrid] Animal detection failed for attachment" + (i + 1)
                                    + ": " + detectionError.getMessage());
                            meta.elk_count = null;
                            meta.processed_status = false;
                            modelFailures++;
                            failedImages++;
                            processedImages++;
                            String elkStatus = formatElkCount(meta.elk_count) + " elk";
                            allImageStatusLines.add(meta.filename + ": " + elkStatus);
                        }

                        db.updateMetaWithDetection(conn, meta.sha256, meta.elk_count, meta.processed_status);
                        System.out.println("[SendGrid] Stored: " + meta.filename
                                + " | elk_count=" + meta.elk_count);
                    }

                } finally {
                    Files.deleteIfExists(tempFile);
                }

            } catch (Exception e) {
                System.err.println("[SendGrid] Error processing attachment" + (i + 1) + ": " + e.getMessage());
                failedImages++;
                allImageStatusLines.add("Attachment " + (i + 1) + ": failed (" + e.getMessage() + ").");
            }
        }

        // Send reply with processing summary
        if (!foundImage) {
            System.out.println("[SendGrid] Email from " + fromEmail + " had no valid image attachments.");
            sendReplyEmail(replyTarget, subject,
                    "Hi! We received your email but couldn't find any valid image attachments. " +
                            "Please send JPG, PNG, or HEIC images and try again.");
        } else {
            String replyBody = buildReplyBody(allImageStatusLines, processedImages, duplicateImages, failedImages);
            sendReplyEmail(replyTarget, subject, replyBody);
        }

        System.out.println("[SendGrid] Summary: attachments_processed=" + processedImages
                + ", duplicates=" + duplicateImages
                + ", failures=" + failedImages
                + ", model_invocations=" + modelInvocations
                + ", model_successes=" + modelSuccesses
                + ", model_failures=" + modelFailures);

        // SendGrid requires a 200 response — always return OK
        return ResponseEntity.ok("");
    }

    /**
     * Inputs: toEmail (String) — recipient's email address;
     * originalSubject (String) — original email subject;
     * bodyText (String) — reply message body
     * Outputs: void — sends email or logs error
     * Functionality: Sends a reply email via SendGrid API v3 REST endpoint.
     * Automatically prefixes subject with "Re: " if needed.
     * Dependencies: SecretConfig, com.fasterxml.jackson.databind.ObjectMapper
     * Called by: sendGridEmailWebhook
     */
    private void sendReplyEmail(String toEmail, String originalSubject, String bodyText) {
        try {
            if (toEmail == null || toEmail.isBlank()) {
                System.err.println("[SendGrid] Failed to send reply: missing recipient email address");
                return;
            }

            String sendGridApiKey = SecretConfig.get("SENDGRID_API_KEY");
            if (sendGridApiKey == null || sendGridApiKey.isBlank()) {
                System.err.println("[SendGrid] Cannot send reply: SENDGRID_API_KEY not configured");
                return;
            }

            String baseSubject = (originalSubject == null || originalSubject.isBlank())
                    ? "PERC Wildlife Observer Update"
                    : originalSubject;
            String subject = baseSubject.startsWith("Re:") ? baseSubject : "Re: " + baseSubject;

            String fromEmail = SecretConfig.get("SENDGRID_FROM_EMAIL");
            if (fromEmail == null || fromEmail.isBlank()) {
                System.err.println("[SendGrid] Cannot send reply: SENDGRID_FROM_EMAIL not configured");
                return;
            }

            // Build the email JSON payload for SendGrid API v3
            Map<String, Object> emailData = new HashMap<>();

            Map<String, String> from = new HashMap<>();
            from.put("email", fromEmail);
            emailData.put("from", from);

            emailData.put("subject", subject);

            List<Map<String, String>> personalization = new ArrayList<>();
            Map<String, String> personalizationEntry = new HashMap<>();
            List<Map<String, String>> toList = new ArrayList<>();
            Map<String, String> toEntry = new HashMap<>();
            toEntry.put("email", toEmail);
            toList.add(toEntry);
            personalizationEntry.put("to", toList.toString()); // Simplified - proper would use JSONArray
            personalization.add(personalizationEntry);

            List<Map<String, String>> content = new ArrayList<>();
            Map<String, String> contentEntry = new HashMap<>();
            contentEntry.put("type", "text/plain");
            contentEntry.put("value", bodyText);
            content.add(contentEntry);
            emailData.put("content", content);

            // Manual JSON construction for simplicity (avoiding additional dependencies)
            String jsonPayload = buildSendGridPayload(fromEmail, toEmail, subject, bodyText);

            // Make HTTP POST request to SendGrid API
            URL url = new URL("https://api.sendgrid.com/v3/mail/send");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + sendGridApiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            String responseBody = readConnectionBody(connection, responseCode >= 200 && responseCode < 300);
            if (responseCode >= 200 && responseCode < 300) {
                System.out.println("[SendGrid] Reply accepted by SendGrid (HTTP " + responseCode
                        + ") to=" + toEmail + " subject='" + subject + "'");
                if (responseBody != null && !responseBody.isBlank()) {
                    System.out.println("[SendGrid] API response: " + responseBody);
                }
            } else {
                System.err.println("[SendGrid] Failed to send reply: HTTP " + responseCode
                        + " to=" + toEmail + " body=" + responseBody);
            }
        } catch (Exception e) {
            System.err.println("[SendGrid] Error sending reply email: " + e.getMessage());
        }
    }

    private String readConnectionBody(HttpURLConnection connection, boolean success) {
        try (InputStream stream = success ? connection.getInputStream() : connection.getErrorStream()) {
            if (stream == null) {
                return "";
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    /**
     * Inputs: fromEmail, toEmail, subject, bodyText (String) — email components
     * Outputs: String — JSON payload for SendGrid API v3
     * Functionality: Constructs the JSON payload needed for SendGrid's mail/send
     * endpoint.
     * Dependencies: None
     * Called by: sendReplyEmail
     */
    private String buildSendGridPayload(String fromEmail, String toEmail, String subject, String bodyText) {
        // Escape special characters for JSON
        String escapedBody = bodyText.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String escapedSubject = subject.replace("\\", "\\\\")
                .replace("\"", "\\\"");

        return "{"
                + "\"personalizations\":[{"
                + "\"to\":[{\"email\":\"" + toEmail + "\"}]"
                + "}],"
                + "\"from\":{\"email\":\"" + fromEmail + "\"},"
                + "\"subject\":\"" + escapedSubject + "\","
                + "\"content\":[{"
                + "\"type\":\"text/plain\","
                + "\"value\":\"" + escapedBody + "\""
                + "}]"
                + "}";
    }

    private String extractEmailAddress(String rawFrom) {
        if (rawFrom == null || rawFrom.isBlank()) {
            return null;
        }

        int start = rawFrom.indexOf('<');
        int end = rawFrom.indexOf('>');
        if (start >= 0 && end > start) {
            String inside = rawFrom.substring(start + 1, end).trim();
            return inside.isBlank() ? null : inside;
        }

        String trimmed = rawFrom.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    /**
     * Inputs: allImageStatusLines (List<String>) — per-image status summaries;
     * processedImages (int) — count of successfully processed images;
     * duplicateImages (int) — count of duplicate images;
     * failedImages (int) — count of failed images
     * Outputs: String — formatted reply body
     * Functionality: Builds a nicely formatted reply summarizing the processing
     * results.
     * Dependencies: None
     * Called by: sendGridEmailWebhook
     */
    private String buildReplyBody(List<String> allImageStatusLines, int processedImages,
            int duplicateImages, int failedImages) {
        StringBuilder replyBuilder = new StringBuilder();
        if (processedImages > 0 && duplicateImages == 0 && failedImages == 0) {
            replyBuilder.append("Thanks! Your photo(s) have been received and logged. ");
        } else if (processedImages > 0) {
            replyBuilder.append("Thanks! We received your email and processed ")
                    .append(processedImages)
                    .append(" photo(s). ")
                    .append(duplicateImages > 0 ? "Some attachments were already in the database. " : "")
                    .append(failedImages > 0 ? "Some attachments could not be processed. " : "");
        } else if (duplicateImages > 0) {
            replyBuilder.append("It looks like we've already received the photo(s) in this email. ")
                    .append("They're already in the PERC database.");
        } else {
            replyBuilder.append("Hi! We received your email but couldn't process any photo attachments. ")
                    .append("Please try sending JPG, PNG, or HEIC images.");
        }

        if (!allImageStatusLines.isEmpty()) {
            replyBuilder.append("\n\nImages:\n");
            for (String statusLine : allImageStatusLines) {
                if (statusLine != null && !statusLine.isBlank()) {
                    replyBuilder.append(statusLine).append("\n");
                }
            }
        }
        return replyBuilder.toString();
    }

    /**
     * Inputs: filename (String) — original filename from attachment;
     * ext (String) — file extension with dot (e.g., ".jpg")
     * Outputs: String — display name for the attachment
     * Functionality: Generates a user-friendly display name for attachments in
     * status messages.
     * Dependencies: None
     * Called by: sendGridEmailWebhook
     */
    private String resolveAttachmentDisplayName(String filename, String ext) {
        if (filename != null && !filename.isBlank() && !"noname".equalsIgnoreCase(filename)) {
            return filename;
        }
        return "email-upload" + ext;
    }

    private String formatElkCount(Integer elkCount) {
        return elkCount != null ? String.valueOf(elkCount) : "0";
    }

    /**
     * Inputs: contentType (String) — MIME type string to check
     * Outputs: boolean — true if the content type is one of: image/jpeg, image/jpg,
     * image/png, image/heic
     * Functionality: Guards the SendGrid webhook from processing non-image
     * attachments.
     * Dependencies: None
     * Called by: sendGridEmailWebhook
     */
    private boolean isAllowedImageType(String contentType) {
        return contentType.equals("image/jpeg") ||
                contentType.equals("image/jpg") ||
                contentType.equals("image/png") ||
                contentType.equals("image/heic");
    }
}

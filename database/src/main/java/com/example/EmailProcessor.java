package com.example;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

public class EmailProcessor {

    private static final String APPLICATION_NAME = "PERC Wildlife Observer";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(GmailScopes.MAIL_GOOGLE_COM);

    private static final String[] ALLOWED_MIME_TYPES = {
            "image/jpeg", "image/jpg", "image/png", "image/heic"
    };

    /**
     * Builds an authorized Gmail client using OAuth2.
     * First run opens browser for authorization, saves token for subsequent runs.
     */
    private static Gmail buildGmailService() throws Exception {
        String credentialsPath = SecretConfig.getRequired("GMAIL_CREDENTIALS_PATH");
        String tokenPath = SecretConfig.getRequired("GMAIL_TOKEN_PATH");

        File tokenPathFile = new File(tokenPath);
        File tokenStoreDir = tokenPathFile;
        if (tokenPathFile.isFile() || tokenPathFile.getName().equals("StoredCredential")) {
            tokenStoreDir = tokenPathFile.getParentFile();
        }
        if (tokenStoreDir == null) {
            throw new IOException("Invalid GMAIL_TOKEN_PATH: " + tokenPath);
        }

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                JSON_FACTORY,
                new FileReader(credentialsPath));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(tokenStoreDir))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder()
                .setPort(8888)
                .build();

        Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");

        return new Gmail.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * Main polling method. Called by InferenceScheduler hourly.
     * Finds unread emails with image attachments, processes them,
     * replies to sender, marks as read.
     */
    public static void pollAndProcess() {
        System.out.println("[EmailProcessor] Starting email poll...");
        int processed = 0;
        int failed = 0;

        try {
            Gmail gmail = buildGmailService();
            AnimalDetectAPI animalDetectAPI = null;
            try {
                String apiKey = AnimalDetectAPI.resolveApiKey(null);
                animalDetectAPI = new AnimalDetectAPI(apiKey, 60);
            } catch (Exception e) {
                System.err.println("[EmailProcessor] AnimalDetect API not available: " + e.getMessage());
            }

            String user = "me";

            // Find unread emails with attachments
            ListMessagesResponse listResponse = gmail.users().messages()
                    .list(user)
                    .setQ("is:unread has:attachment")
                    .execute();

            List<Message> messages = listResponse.getMessages();
            if (messages == null || messages.isEmpty()) {
                System.out.println("[EmailProcessor] No unread emails with attachments.");
                return;
            }

            for (Message messageSummary : messages) {
                try {
                    // Get full message
                    Message message = gmail.users().messages()
                            .get(user, messageSummary.getId())
                            .setFormat("full")
                            .execute();

                    String fromEmail = extractHeader(message, "From");
                    String fromAddress = extractEmailAddress(fromEmail);
                    String subject = extractHeader(message, "Subject");
                    System.out
                            .println("[EmailProcessor] Processing email from: " + fromEmail + ", subject: " + subject);

                    boolean voiceGoogleSender = fromAddress != null
                            && fromAddress.toLowerCase().endsWith("@txt.voice.google.com");

                    // Find image attachments, including nested multipart sections.
                    List<MessagePart> imageParts = new ArrayList<>();
                    collectImageAttachmentParts(message.getPayload(), imageParts, voiceGoogleSender);
                    if (imageParts.isEmpty()) {
                        if (!voiceGoogleSender) {
                            sendReply(gmail, user, fromEmail, subject,
                                    "Hi! We received your email but couldn't find a valid photo attachment. " +
                                            "Please attach a JPG, PNG, or HEIC photo and try again.");
                        }
                        markAsRead(gmail, user, messageSummary.getId());
                        continue;
                    }

                    int processedImages = 0;
                    int duplicateImages = 0;
                    int failedImages = 0;
                    boolean skipReply = voiceGoogleSender;
                    List<String> elkCountLines = new ArrayList<>();

                    for (MessagePart part : imageParts) {
                        String mimeType = part.getMimeType();
                        String attachmentId = part.getBody().getAttachmentId();
                        String filename = part.getFilename();
                        boolean assumeExifParsable = voiceGoogleSender;

                        // Download attachment
                        var attachment = gmail.users().messages().attachments()
                                .get(user, messageSummary.getId(), attachmentId)
                                .execute();

                        byte[] imageBytes = Base64.getUrlDecoder().decode(attachment.getData());

                        // Write to temp file
                        String ext = mimeType.contains("png") ? ".png" : mimeType.contains("heic") ? ".heic" : ".jpg";
                        Path tempFile = Files.createTempFile("email-", ext);

                        try {
                            Files.write(tempFile, imageBytes);

                            // Run through instant pipeline
                            Metadata meta = db.loadMetadata(tempFile.toFile(), assumeExifParsable);
                            meta.processed_status = false;
                            String objectName = meta.sha256 + ".jpg";

                            try (java.sql.Connection conn = db.connect()) {
                                // Duplicate check
                                Metadata existing = db.getImageByHash(conn, meta.sha256);
                                if (existing != null) {
                                    System.out.println("[EmailProcessor] Duplicate image, skipping: " + meta.sha256);
                                    duplicateImages++;
                                    continue;
                                }

                                // Upload to GCS
                                GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                                meta.cloud_uri = "gs://" + SecretConfig.getRequired("GCS_BUCKET_NAME")
                                        + "/" + objectName;
                                meta.filename = (filename != null && !filename.isBlank()
                                        && !"noname".equalsIgnoreCase(filename))
                                                ? filename
                                                : "email-upload" + ext;

                                db.insertMeta(conn, meta);

                                if (animalDetectAPI != null) {
                                    try {
                                        java.util.Map<String, Object> response = animalDetectAPI
                                                .callAnimalDetectAPIWithFallback(
                                                        imageBytes,
                                                        meta.filename,
                                                        "USA",
                                                        0.2);

                                        List<String> predictionLines = animalDetectAPI
                                                .formatDetectionsForConsole(response);
                                        if (predictionLines.isEmpty()) {
                                            System.out.println("[EmailProcessor] Model predictions for " + meta.filename
                                                    + ": none");
                                        } else {
                                            for (String predictionLine : predictionLines) {
                                                System.out.println(
                                                        "[EmailProcessor] Model predictions for " + meta.filename
                                                                + " -> " + predictionLine);
                                            }
                                        }

                                        meta.elk_count = animalDetectAPI.countElkFromResponse(response, 0.2);
                                        meta.processed_status = true;
                                    } catch (Exception detectionError) {
                                        System.err.println("[EmailProcessor] Animal detection failed for "
                                                + meta.filename + ": " + detectionError.getMessage());
                                        meta.elk_count = null;
                                        meta.processed_status = false;
                                        failedImages++;
                                    }
                                }

                                db.updateMetaWithDetection(conn, meta.sha256, meta.elk_count, meta.processed_status);
                                System.out.println("[EmailProcessor] Final elk count for " + meta.filename + ": "
                                        + (meta.elk_count != null ? meta.elk_count : "unknown"));
                            }

                            elkCountLines.add(meta.filename + " : elk count = "
                                    + (meta.elk_count != null ? meta.elk_count : "unknown"));
                            processedImages++;
                            processed++;

                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    }

                    if (!skipReply) {
                        StringBuilder replyBuilder = new StringBuilder();
                        if (processedImages > 0 && duplicateImages == 0 && failedImages == 0) {
                            replyBuilder.append("Thanks! Your photo(s) have been received and logged. ");
                        } else if (processedImages > 0) {
                            replyBuilder.append("Thanks! We received your email and processed ")
                                    .append(processedImages)
                                    .append(" photo(s). ")
                                    .append(duplicateImages > 0 ? "Some attachments were already in the database. "
                                            : "")
                                    .append(failedImages > 0 ? "Some attachments could not be processed. " : "");
                        } else if (duplicateImages > 0) {
                            replyBuilder.append("It looks like we've already received the photo(s) in this email. ")
                                    .append("They're already in the PERC database.");
                        } else {
                            replyBuilder
                                    .append("Hi! We received your email but couldn't process any photo attachments. ")
                                    .append("Please try sending JPG, PNG, or HEIC images.");
                        }

                        if (processedImages > 0) {
                            replyBuilder.append("\n\nElk counts by image:\n");
                            for (String line : elkCountLines) {
                                replyBuilder.append(line).append("\n");
                            }
                            if (duplicateImages > 0) {
                                replyBuilder.append("Some attachments were duplicates and were skipped.\n");
                            }
                            if (failedImages > 0) {
                                replyBuilder.append("Some attachments could not be processed.\n");
                            }
                        }

                        sendReply(gmail, user, fromEmail, subject, replyBuilder.toString());
                    }

                    // Mark as read regardless of outcome
                    markAsRead(gmail, user, messageSummary.getId());

                } catch (Exception e) {
                    failed++;
                    System.err.println("[EmailProcessor] Failed to process message "
                            + messageSummary.getId() + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            System.err.println("[EmailProcessor] Poll failed: " + e.getMessage());
        }

        System.out.println("[EmailProcessor] Done. processed=" + processed + ", failed=" + failed);
    }

    private static void markAsRead(Gmail gmail, String user, String messageId) {
        try {
            gmail.users().messages().modify(user, messageId,
                    new com.google.api.services.gmail.model.ModifyMessageRequest()
                            .setRemoveLabelIds(Collections.singletonList("UNREAD")))
                    .execute();
        } catch (Exception e) {
            System.err.println("[EmailProcessor] Failed to mark as read: " + e.getMessage());
        }
    }

    private static void sendReply(Gmail gmail, String user, String toEmail,
            String originalSubject, String bodyText) {
        try {
            String subject = originalSubject != null && !originalSubject.startsWith("Re:")
                    ? "Re: " + originalSubject
                    : originalSubject;

            Properties props = new Properties();
            Session session = Session.getDefaultInstance(props, null);
            MimeMessage email = new MimeMessage(session);
            email.setFrom("me");
            email.addRecipient(jakarta.mail.Message.RecipientType.TO,
                    new jakarta.mail.internet.InternetAddress(toEmail));
            email.setSubject(subject);
            email.setText(bodyText);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            email.writeTo(buffer);
            byte[] rawEmail = buffer.toByteArray();
            String encodedEmail = Base64.getUrlEncoder().encodeToString(rawEmail);

            Message message = new Message();
            message.setRaw(encodedEmail);
            gmail.users().messages().send(user, message).execute();

            System.out.println("[EmailProcessor] Reply sent to: " + toEmail);
        } catch (Exception e) {
            System.err.println("[EmailProcessor] Failed to send reply to " + toEmail + ": " + e.getMessage());
        }
    }

    private static String extractHeader(Message message, String headerName) {
        if (message.getPayload() == null || message.getPayload().getHeaders() == null)
            return null;
        for (MessagePartHeader header : message.getPayload().getHeaders()) {
            if (header.getName().equalsIgnoreCase(headerName)) {
                return header.getValue();
            }
        }
        return null;
    }

    private static String extractEmailAddress(String headerValue) {
        if (headerValue == null) {
            return null;
        }

        int start = headerValue.indexOf('<');
        int end = headerValue.indexOf('>');
        if (start >= 0 && end > start) {
            return headerValue.substring(start + 1, end).trim();
        }

        String trimmed = headerValue.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void collectImageAttachmentParts(MessagePart part, List<MessagePart> imageParts,
            boolean attachmentsOnly) {
        if (part == null) {
            return;
        }

        List<MessagePart> children = part.getParts();
        if (children != null && !children.isEmpty()) {
            for (MessagePart child : children) {
                collectImageAttachmentParts(child, imageParts, attachmentsOnly);
            }
            return;
        }

        String mimeType = part.getMimeType();
        if (!isAllowedMimeType(mimeType)) {
            return;
        }

        if (attachmentsOnly && (part.getBody() == null || part.getBody().getAttachmentId() == null)) {
            return;
        }

        if (part.getBody() != null && part.getBody().getAttachmentId() != null) {
            imageParts.add(part);
        }
    }

    private static boolean isAllowedMimeType(String mimeType) {
        if (mimeType == null)
            return false;
        for (String allowed : ALLOWED_MIME_TYPES) {
            if (allowed.equalsIgnoreCase(mimeType))
                return true;
        }
        return false;
    }
}
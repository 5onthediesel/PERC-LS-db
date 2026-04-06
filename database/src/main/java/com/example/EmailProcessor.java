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

        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                JSON_FACTORY,
                new FileReader(credentialsPath));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                clientSecrets,
                SCOPES)
                .setDataStoreFactory(new FileDataStoreFactory(new File(tokenPath)))
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
                    String subject = extractHeader(message, "Subject");
                    System.out
                            .println("[EmailProcessor] Processing email from: " + fromEmail + ", subject: " + subject);

                    // Find image attachments
                    List<MessagePart> parts = message.getPayload().getParts();
                    if (parts == null) {
                        markAsRead(gmail, user, messageSummary.getId());
                        continue;
                    }

                    boolean foundImage = false;
                    for (MessagePart part : parts) {
                        String mimeType = part.getMimeType();
                        if (!isAllowedMimeType(mimeType))
                            continue;

                        foundImage = true;
                        String attachmentId = part.getBody().getAttachmentId();
                        String filename = part.getFilename();

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
                            Metadata meta = db.loadMetadata(tempFile.toFile());
                            String objectName = meta.sha256 + ".jpg";

                            try (java.sql.Connection conn = db.connect()) {
                                // Duplicate check
                                Metadata existing = db.getImageByHash(conn, meta.sha256);
                                if (existing != null) {
                                    System.out.println("[EmailProcessor] Duplicate image, skipping: " + meta.sha256);
                                    sendReply(gmail, user, fromEmail, subject,
                                            "It looks like we've already received this photo! " +
                                                    "It's already in the PERC database.");
                                    break;
                                }

                                // Upload to GCS
                                GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                                meta.cloud_uri = "gs://" + SecretConfig.getRequired("GCS_BUCKET_NAME")
                                        + "/" + objectName;
                                meta.filename = (filename != null && !filename.isBlank())
                                        ? filename
                                        : "email-upload" + ext;

                                db.insertMeta(conn, meta);
                            }

                            // Build reply based on GPS
                            String reply;
                            if (meta.gps_flag) {
                                String lat = String.format("%.5f", meta.latitude);
                                String lon = String.format("%.5f", meta.longitude);
                                String when = (meta.datetime != null) ? meta.datetime : "unknown time";
                                String weather = (meta.weather_desc != null)
                                        ? " Weather at time of photo: " + meta.weather_desc + "."
                                        : "";
                                reply = "Thanks! Your photo has been received and logged. " +
                                        "Location: " + lat + ", " + lon + " at " + when + "." +
                                        weather + " We'll scan it for elk activity shortly.";
                            } else {
                                reply = "Thanks for your photo! We received it, but couldn't detect " +
                                        "a GPS location. Could you reply with your approximate " +
                                        "coordinates or ranch name so we can log it accurately?";
                            }

                            sendReply(gmail, user, fromEmail, subject, reply);
                            processed++;

                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    }

                    if (!foundImage) {
                        // Email had attachments but none were images
                        sendReply(gmail, user, fromEmail, subject,
                                "Hi! We received your email but couldn't find a valid image attachment. " +
                                        "Please attach a JPG, PNG, or HEIC photo and try again.");
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
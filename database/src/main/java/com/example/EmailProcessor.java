package com.example;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponseException;
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
import java.nio.file.StandardCopyOption;
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
    private static final int MAX_FILES_PER_EMAIL = 10;
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024L * 1024L;

    /**
     * Inputs: None (reads GMAIL_CREDENTIALS_PATH and GMAIL_TOKEN_PATH from
     * SecretConfig)
     * Outputs: Gmail — authorized Gmail API client
     * Functionality: Builds an OAuth2-authorized Gmail service client, opening a
     * local browser for
     * first-run authorization and caching the token for subsequent runs.
     * Dependencies: com.google.api.services.gmail.Gmail,
     * GoogleAuthorizationCodeFlow,
     * GoogleNetHttpTransport, FileDataStoreFactory, LocalServerReceiver,
     * SecretConfig
     * Called by: pollAndProcess
     */
    private static Gmail buildGmailService() throws Exception {
        String credentialsPath = SecretConfig.getRequired("GMAIL_CREDENTIALS_PATH");
        String tokenPath = SecretConfig.getRequired("GMAIL_TOKEN_PATH");

        File tokenStoreDir = resolveWritableTokenStoreDir(tokenPath);

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
     * Inputs: tokenPath (String) -- configured Gmail token path from SecretConfig
     * Outputs: File -- writable token store directory, or a fallback directory
     * under /tmp
     * Functionality: Resolves the runtime token datastore location. Prefers the
     * configured
     * directory when it is writable, but automatically falls back to a writable
     * /tmp directory when the configured path points at a read-only Cloud Run
     * secret mount or another non-writable location.
     * Dependencies: isDirectoryWritable, prepareFallbackTokenStore
     * Called by: buildGmailService
     */
    private static File resolveWritableTokenStoreDir(String tokenPath) throws IOException {
        File tokenPathFile = new File(tokenPath);
        File configuredTokenDir = tokenPathFile;
        if (tokenPathFile.isFile() || tokenPathFile.getName().equals("StoredCredential")) {
            configuredTokenDir = tokenPathFile.getParentFile();
        }

        if (configuredTokenDir == null) {
            throw new IOException("Invalid GMAIL_TOKEN_PATH: " + tokenPath);
        }

        // Cloud Run secret mounts are read-only; canWrite() may be unreliable, so force
        // /tmp.
        if (configuredTokenDir.getAbsolutePath().startsWith("/secrets/")) {
            return prepareFallbackTokenStore(tokenPathFile, configuredTokenDir);
        }

        if (isDirectoryWritable(configuredTokenDir)) {
            return configuredTokenDir;
        }

        return prepareFallbackTokenStore(tokenPathFile, configuredTokenDir);
    }

    /**
     * Inputs: directory (File) -- directory to test for write access
     * Outputs: boolean -- true if a temporary file can be created and deleted in
     * the directory
     * Functionality: Performs a real write probe instead of relying on canWrite(),
     * which can be
     * misleading on mounted or container-managed filesystems.
     * Dependencies: java.nio.file.Files
     * Called by: resolveWritableTokenStoreDir, prepareFallbackTokenStore
     */
    private static boolean isDirectoryWritable(File directory) {
        try {
            if (!directory.exists()) {
                Files.createDirectories(directory.toPath());
            }
            Path probe = Files.createTempFile(directory.toPath(), "gmail-token-probe-", ".tmp");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Inputs: tokenPathFile (File) -- the configured token path or StoredCredential
     * file
     * configuredTokenDir (File) -- directory derived from the configured token path
     * Outputs: File -- writable fallback directory under /tmp containing a copied
     * StoredCredential
     * Functionality: Creates and/or reuses a writable token datastore under /tmp
     * and seeds it
     * from the configured credential file when available.
     * Dependencies: isDirectoryWritable, java.nio.file.Files
     * Called by: resolveWritableTokenStoreDir
     */
    private static File prepareFallbackTokenStore(File tokenPathFile, File configuredTokenDir) throws IOException {
        File fallbackDir = new File("/tmp/gmail-token-store");
        if (!isDirectoryWritable(fallbackDir)) {
            throw new IOException("Unable to create writable token store directory: " + fallbackDir.getAbsolutePath());
        }

        File sourceCredentialFile = tokenPathFile.isFile()
                ? tokenPathFile
                : new File(configuredTokenDir, "StoredCredential");
        File targetCredentialFile = new File(fallbackDir, "StoredCredential");

        if (sourceCredentialFile.exists() && !targetCredentialFile.exists()) {
            Files.copy(sourceCredentialFile.toPath(), targetCredentialFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        System.out.println("[EmailProcessor] Using writable Gmail token store: " + fallbackDir.getAbsolutePath());
        return fallbackDir;
    }

    /**
     * Inputs: None
     * Outputs: void — side effects: uploads images to GCS, inserts DB records,
     * sends email replies,
     * marks emails as read
     * Functionality: Polls the Gmail inbox for unread emails with image
     * attachments, runs each image
     * through the full pipeline (EXIF parsing, GCS upload, DB insert,
     * AnimalDetect),
     * replies to the sender with elk counts, and marks messages as read.
     * Dependencies: buildGmailService, AnimalDetectAPI, db.loadMetadata,
     * db.connect, db.getImageByHash,
     * db.insertMeta, db.updateMetaWithDetection, GoogleCloudStorageAPI.uploadFile,
     * sendReply, markAsRead, collectImageAttachmentParts, SecretConfig
     * Called by: EventScheduler.runEmailPollingJob, TaskController.pollOnStartup
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
                    String replyToHeader = extractHeader(message, "Reply-To");
                    String replyToAddress = extractEmailAddress(replyToHeader);
                    String replyTarget = replyToAddress != null ? replyToAddress
                            : (fromAddress != null ? fromAddress : fromEmail);
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
                            sendReply(gmail, user, replyTarget, subject,
                                    "Hi! We received your email but couldn't find a valid photo attachment. " +
                                            "Please attach a JPG, PNG, or HEIC photo and try again.");
                        }
                        markAsRead(gmail, user, messageSummary.getId());
                        continue;
                    }

                    if (imageParts.size() > MAX_FILES_PER_EMAIL) {
                        if (!voiceGoogleSender) {
                            sendReply(gmail, user, replyTarget, subject,
                                    "Your email includes too many image attachments. "
                                            + "Please send no more than 10 images per email, "
                                            + "with each image under 10MB.");
                        }
                        markAsRead(gmail, user, messageSummary.getId());
                        continue;
                    }

                    int processedImages = 0;
                    int duplicateImages = 0;
                    int failedImages = 0;
                    boolean skipReply = voiceGoogleSender;
                    List<String> allImageStatusLines = new ArrayList<>();

                    for (MessagePart part : imageParts) {
                        String mimeType = part.getMimeType();
                        String attachmentId = part.getBody().getAttachmentId();
                        String filename = part.getFilename();
                        boolean assumeExifParsable = voiceGoogleSender;
                        long declaredAttachmentSize = part.getBody() != null && part.getBody().getSize() != null
                                ? part.getBody().getSize()
                                : -1L;
                        String attachmentDisplayName = resolveAttachmentDisplayName(filename,
                                mimeType.contains("png") ? ".png" : mimeType.contains("heic") ? ".heic" : ".jpg");

                        if (declaredAttachmentSize > MAX_FILE_SIZE_BYTES) {
                            failedImages++;
                            allImageStatusLines.add(attachmentDisplayName + ": skipped (over 10MB limit).");
                            continue;
                        }

                        // Download attachment
                        var attachment = gmail.users().messages().attachments()
                                .get(user, messageSummary.getId(), attachmentId)
                                .execute();

                        byte[] imageBytes = Base64.getUrlDecoder().decode(attachment.getData());

                        // Write to temp file
                        String ext = mimeType.contains("png") ? ".png" : mimeType.contains("heic") ? ".heic" : ".jpg";

                        if (imageBytes.length > MAX_FILE_SIZE_BYTES) {
                            failedImages++;
                            allImageStatusLines.add(attachmentDisplayName + ": skipped (over 10MB limit).");
                            continue;
                        }

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
                                    allImageStatusLines.add(attachmentDisplayName + ": already in database!");
                                    continue;
                                }

                                // Upload to GCS
                                GoogleCloudStorageAPI.uploadFile(tempFile.toString(), objectName);
                                meta.cloud_uri = "gs://" + SecretConfig.getRequired("GCS_BUCKET_NAME")
                                        + "/" + objectName;
                                meta.filename = attachmentDisplayName;

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

                            String elkStatus = meta.elk_count != null
                                    ? meta.elk_count + " elk found!"
                                    : "0 elk found";
                            allImageStatusLines.add(meta.filename + ": " + elkStatus);
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

                        if (!allImageStatusLines.isEmpty()) {
                            replyBuilder.append("\n\nImages:\n");
                            for (String line : allImageStatusLines) {
                                replyBuilder.append(line).append("\n");
                            }
                        }

                        sendReply(gmail, user, replyTarget, subject, replyBuilder.toString());
                    }

                    // Mark as read regardless of outcome
                    markAsRead(gmail, user, messageSummary.getId());

                } catch (Exception e) {
                    failed++;
                    logDetailedException("Failed to process message " + messageSummary.getId(), e);
                }
            }

        } catch (Exception e) {
            logDetailedException("Poll failed", e);
        }

        System.out.println("[EmailProcessor] Done. processed=" + processed + ", failed=" + failed);
    }

    /**
     * Inputs: gmail (Gmail) — authorized Gmail client; user (String) — Gmail user
     * ID ("me");
     * messageId (String) — ID of the message to mark as read
     * Outputs: void — removes the UNREAD label from the specified message
     * Functionality: Calls the Gmail API to remove the UNREAD label, silently
     * logging any errors.
     * Dependencies: com.google.api.services.gmail.Gmail
     * Called by: pollAndProcess
     */
    private static void markAsRead(Gmail gmail, String user, String messageId) {
        try {
            gmail.users().messages().modify(user, messageId,
                    new com.google.api.services.gmail.model.ModifyMessageRequest()
                            .setRemoveLabelIds(Collections.singletonList("UNREAD")))
                    .execute();
        } catch (Exception e) {
            logDetailedException("Failed to mark as read", e);
        }
    }

    /**
     * Inputs: gmail (Gmail) — authorized Gmail client; user (String) — Gmail user
     * ID ("me");
     * toEmail (String) — recipient address; originalSubject (String) — subject of
     * the original email;
     * bodyText (String) — plain-text reply body
     * Outputs: void — sends a reply email via the Gmail API
     * Functionality: Constructs a MimeMessage reply and sends it through the Gmail
     * API, prepending "Re: "
     * to the subject if not already present.
     * Dependencies: com.google.api.services.gmail.Gmail, jakarta.mail.Session,
     * jakarta.mail.internet.MimeMessage, java.util.Base64
     * Called by: pollAndProcess
     */
    private static void sendReply(Gmail gmail, String user, String toEmail,
            String originalSubject, String bodyText) {
        try {
            if (toEmail == null || toEmail.isBlank()) {
                System.err.println("[EmailProcessor] Failed to send reply: missing recipient email address");
                return;
            }

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
            logDetailedException("Failed to send reply to " + toEmail, e);
        }
    }

    /**
     * Inputs: filename (String) -- original attachment filename, may be blank
     * ext (String) -- fallback extension determined from MIME type
     * Outputs: String -- display name used in replies and logs
     * Functionality: Returns the original filename when available, otherwise
     * generates a stable
     * fallback name for email attachments.
     * Dependencies: None
     * Called by: pollAndProcess
     */
    private static String resolveAttachmentDisplayName(String filename, String ext) {
        if (filename != null && !filename.isBlank() && !"noname".equalsIgnoreCase(filename)) {
            return filename;
        }
        return "email-upload" + ext;
    }

    /**
     * Inputs: elkCount (Integer) -- detected elk count, may be null
     * Outputs: String -- count as text, or "0" when unknown/null
     * Functionality: Formats elk counts for status messages in a consistent
     * reply-friendly form.
     * Dependencies: None
     * Called by: pollAndProcess
     */
    private static String formatElkCount(Integer elkCount) {
        return elkCount != null ? String.valueOf(elkCount) : "0";
    }

    /**
     * Inputs: context (String) — short operation description; e (Exception) —
     * thrown exception
     * Outputs: void — writes detailed error diagnostics to stderr
     * Functionality: Logs exception type, Google API response metadata (status
     * code/body details),
     * HTTP error payload when available, and full stack trace for troubleshooting.
     * Dependencies: GoogleJsonResponseException, HttpResponseException
     * Called by: pollAndProcess, markAsRead, sendReply
     */
    private static void logDetailedException(String context, Exception e) {
        System.err.println("[EmailProcessor] " + context + " [" + e.getClass().getName() + "]: " + e.getMessage());

        if (e instanceof GoogleJsonResponseException gjre) {
            System.err.println("[EmailProcessor] Google API status: " + gjre.getStatusCode() + " "
                    + gjre.getStatusMessage());

            var details = gjre.getDetails();
            if (details != null) {
                System.err.println("[EmailProcessor] Google API details: code=" + details.getCode()
                        + ", message=" + details.getMessage());

                if (details.getErrors() != null) {
                    for (var error : details.getErrors()) {
                        System.err.println("[EmailProcessor] Google API error: reason=" + error.getReason()
                                + ", domain=" + error.getDomain()
                                + ", message=" + error.getMessage()
                                + ", location=" + error.getLocation()
                                + ", locationType=" + error.getLocationType());
                    }
                }
            }
        } else if (e instanceof HttpResponseException hre) {
            System.err.println("[EmailProcessor] HTTP status: " + hre.getStatusCode() + " " + hre.getStatusMessage());
            if (hre.getContent() != null && !hre.getContent().isBlank()) {
                System.err.println("[EmailProcessor] HTTP response content: " + hre.getContent());
            }
        }

        Throwable cause = e.getCause();
        if (cause != null) {
            System.err.println("[EmailProcessor] Root cause [" + cause.getClass().getName() + "]: "
                    + cause.getMessage());
        }

        e.printStackTrace(System.err);
    }

    /**
     * Inputs: message (Message) — full Gmail message object; headerName (String) —
     * header to look up
     * Outputs: String — header value, or null if the header is not present
     * Functionality: Searches the message payload headers for a case-insensitive
     * name match and returns its value.
     * Dependencies: com.google.api.services.gmail.model.Message,
     * com.google.api.services.gmail.model.MessagePartHeader
     * Called by: pollAndProcess
     */
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

    /**
     * Inputs: headerValue (String) — raw From/To header value (e.g. "Name
     * <addr@example.com>")
     * Outputs: String — bare email address, or the trimmed header value if no
     * angle-bracket format is found
     * Functionality: Extracts the email address from an RFC 2822 name-addr header
     * value.
     * Dependencies: None
     * Called by: pollAndProcess
     */
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

    /**
     * Inputs: part (MessagePart) — root or nested message part to inspect;
     * imageParts (List<MessagePart>) — accumulator list populated with matching
     * parts;
     * attachmentsOnly (boolean) — if true, only parts with an attachmentId are
     * included
     * Outputs: void — populates imageParts in place
     * Functionality: Recursively walks the MIME tree of a Gmail message, collecting
     * leaf parts whose
     * MIME type is an allowed image type and that have an attachment ID.
     * Dependencies: com.google.api.services.gmail.model.MessagePart,
     * isAllowedMimeType
     * Called by: pollAndProcess
     */
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

    /**
     * Inputs: mimeType (String) — MIME type string to check
     * Outputs: boolean — true if the MIME type is in the ALLOWED_MIME_TYPES list
     * Functionality: Case-insensitive check of whether a MIME type represents an
     * accepted image format.
     * Dependencies: None
     * Called by: collectImageAttachmentParts
     */
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

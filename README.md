# PERC Wildlife Observer — Code Reference

This document contains function-level documentation for all Java source files in the project. Each entry covers inputs, outputs, functionality, library/API dependencies, and which other components call the function.

---

## Table of Contents

- [AnimalDetectAPI.java](#animaldetectapijava)
- [App.java](#appjava)
- [Config.java](#configjava)
- [db.java](#dbjava)
- [EmailProcessor.java](#emailprocessorjava)
- [EventScheduler.java](#eventschedulerjava)
- [FileProcessor.java](#fileprocessorjava)
- [FileUploadController.java](#fileuploadcontrollerjava)
- [GoogleCloudStorageAPI.java](#googlecloudstorageapijava)
- [ImageStatsController.java](#imagestatscontrollerjava)
- [ImageUtils.java](#imageutilsjava)
- [MessagingController.java](#messagingcontrollerjava)
- [Messenger.java](#messengerjava)
- [Metadata.java](#metadatajava)
- [SecretConfig.java](#secretconfigjava)
- [TaskController.java](#taskcontrollerjava)
- [WebConfig.java](#webconfigjava)

---

## AnimalDetectAPI.java

HTTP client for the AnimalDetect wildlife detection API. Handles image upload, automatic compression/retry on oversized payloads, and elk count extraction from API responses.

---

### `AnimalDetectAPI(String apiKey)`

| Field | Detail |
|---|---|
| **Inputs** | `apiKey` (String) — AnimalDetect API key |
| **Outputs** | `AnimalDetectAPI` instance with `DEFAULT_TIMEOUT` (60s) |
| **Functionality** | Convenience constructor that delegates to the two-arg constructor with a default timeout. |
| **Dependencies** | None |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `EmailProcessor.pollAndProcess`, `MessagingController.sendGridEmailWebhook` |

---

### `AnimalDetectAPI(String apiKey, int timeout)`

| Field | Detail |
|---|---|
| **Inputs** | `apiKey` (String) — AnimalDetect API key; `timeout` (int) — HTTP timeout in seconds |
| **Outputs** | Fully initialized `AnimalDetectAPI` instance |
| **Functionality** | Initializes the HTTP client and JSON object mapper used for all API calls. |
| **Dependencies** | `java.net.http.HttpClient`, `com.fasterxml.jackson.databind.ObjectMapper` |
| **Called by** | `AnimalDetectAPI(String)` single-arg constructor; callers that need a custom timeout |

---

### `callAnimalDetectAPI(byte[] imageBytes, String filename, String country, double threshold)`

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw image data; `filename` (String) — original file name; `country` (String) — country code for detection context (e.g. "USA"); `threshold` (double) — minimum confidence score to include a detection |
| **Outputs** | `Map<String, Object>` — parsed JSON response from the AnimalDetect API |
| **Functionality** | Sends the image to the AnimalDetect REST API as a multipart/form-data POST and returns the parsed response. |
| **Dependencies** | `java.net.http.HttpClient`, `java.net.http.HttpRequest/HttpResponse`, `com.fasterxml.jackson.databind.ObjectMapper` |
| **Called by** | `callAnimalDetectAPIWithFallback` (retry loop) |

---

### `callAnimalDetectAPIWithFallback(byte[] imageBytes, String filename, String country, double threshold)`

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw image data; `filename` (String) — original file name; `country` (String) — country code; `threshold` (double) — confidence threshold |
| **Outputs** | `Map<String, Object>` — parsed API response after successful call |
| **Functionality** | Enforces a payload size limit before calling the API, then retries with progressively smaller/lower-quality JPEG compressions on HTTP 413 errors. |
| **Dependencies** | `compressImageForUpload`, `prepareImageForPayloadLimit`, `callAnimalDetectAPI` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `EmailProcessor.pollAndProcess`, `MessagingController.sendGridEmailWebhook` |

---

### `compressImageForUpload(byte[] imageBytes, String filename, int maxSide, int quality)`

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw image data; `filename` (String) — used for format hints; `maxSide` (int) — maximum pixel length for the longest side after resizing; `quality` (int) — JPEG compression quality (1–100) |
| **Outputs** | `byte[]` — JPEG-encoded bytes of the resized/compressed image |
| **Functionality** | Decodes, optionally downscales, converts to RGB, and JPEG-encodes the image to reduce its byte size for upload. |
| **Dependencies** | `javax.imageio.ImageIO`, `java.awt.image.BufferedImage`, `java.awt.Graphics2D` |
| **Called by** | `callAnimalDetectAPIWithFallback` (retry steps), `prepareImageForPayloadLimit` |

---

### `readScaledImageForCompression(byte[] imageBytes, int maxSide)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw image data; `maxSide` (int) — target maximum side length |
| **Outputs** | `BufferedImage` — decoded image, sub-sampled if source is larger than `maxSide` |
| **Functionality** | Uses an `ImageReader` with sub-sampling to decode only enough pixel data needed for the target size, avoiding full allocation of very large images. |
| **Dependencies** | `javax.imageio.ImageIO`, `javax.imageio.ImageReader`, `javax.imageio.ImageReadParam`, `javax.imageio.stream.ImageInputStream` |
| **Called by** | `compressImageForUpload` |

---

### `prepareImageForPayloadLimit(byte[] imageBytes, String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw image data; `filename` (String) — original file name |
| **Outputs** | `PreparedUploadImage` — wrapper containing (possibly compressed) bytes and a safe filename |
| **Functionality** | Returns the image as-is if under the payload limit; otherwise estimates a target scale and compresses the image to fit within `PRACTICAL_RAW_LIMIT_BYTES`. |
| **Dependencies** | `compressImageForUpload`, `readImageDimensions`, `toCompressedFilename` |
| **Called by** | `callAnimalDetectAPIWithFallback` |

---

### `readImageDimensions(byte[] imageBytes)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw image data |
| **Outputs** | `int[]` — two-element array `[width, height]`; defaults to `[1920, 1080]` if unreadable |
| **Functionality** | Reads only the image header to extract dimensions without decoding the full pixel buffer. |
| **Dependencies** | `javax.imageio.ImageIO`, `javax.imageio.ImageReader`, `javax.imageio.stream.ImageInputStream` |
| **Called by** | `prepareImageForPayloadLimit` |

---

### `toCompressedFilename(String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — original file name (may be null or blank) |
| **Outputs** | `String` — new filename with `_compressed.jpeg` suffix |
| **Functionality** | Strips the original extension and appends `_compressed.jpeg` to produce a storage-safe name. |
| **Dependencies** | None |
| **Called by** | `prepareImageForPayloadLimit`, `callAnimalDetectAPIWithFallback` |

---

### `convertToRGB(BufferedImage img)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `img` (BufferedImage) — source image, any color model |
| **Outputs** | `BufferedImage` — image guaranteed to be `TYPE_INT_RGB` |
| **Functionality** | Redraws the image onto a fresh RGB canvas to strip alpha channels or unusual color spaces before JPEG encoding. |
| **Dependencies** | `java.awt.image.BufferedImage`, `java.awt.Graphics2D` |
| **Called by** | `compressImageForUpload` |

---

### `isPayloadTooLargeError(Exception exc)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `exc` (Exception) — exception thrown by an API call |
| **Outputs** | `boolean` — `true` if the error indicates the payload was too large (HTTP 413) |
| **Functionality** | Checks the exception message for HTTP 413 or a known cloud-function payload error string. |
| **Dependencies** | None |
| **Called by** | `callAnimalDetectAPIWithFallback` |

---

### `detectImageContentType(String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — file name whose extension determines the MIME type |
| **Outputs** | `String` — MIME type string (e.g. `"image/jpeg"`, `"image/png"`) |
| **Functionality** | Maps common image file extensions to their corresponding MIME types, defaulting to `"image/jpeg"`. |
| **Dependencies** | None |
| **Called by** | `callAnimalDetectAPI` |

---

### `extractDetections(Map<String, Object> payload)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `payload` (Map<String, Object>) — parsed API JSON response |
| **Outputs** | `List<Map<String, Object>>` — list of detection objects; empty list if none found |
| **Functionality** | Searches common API response field names (`annotations`, `detections`, `results`, `predictions`, `data`) to extract the list of animal detections regardless of the exact response schema. |
| **Dependencies** | None |
| **Called by** | `countElkFromResponse`, `formatDetectionsForConsole` |

---

### `getDetectionLabel(Map<String, Object> det)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `det` (Map<String, Object>) — a single detection object from the API response |
| **Outputs** | `String` — the most specific taxonomy label available, lowercased; empty string if none found |
| **Functionality** | Walks the taxonomy hierarchy (species → genus → family → order → class) then falls back to top-level label fields to return the best available animal name. |
| **Dependencies** | None |
| **Called by** | `countElkFromResponse`, `formatDetectionsForConsole` |

---

### `getDetectionScore(Map<String, Object> det)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `det` (Map<String, Object>) — a single detection object from the API response |
| **Outputs** | `Double` — confidence score in [0,1] range, or `null` if no score field is present |
| **Functionality** | Checks common confidence field names (`confidence`, `score`, `probability`) and returns the first numeric value found. |
| **Dependencies** | None |
| **Called by** | `countElkFromResponse`, `formatDetectionsForConsole` |

---

### `countElkFromResponse(Map<String, Object> payload, double threshold)`

| Field | Detail |
|---|---|
| **Inputs** | `payload` (Map<String, Object>) — parsed API response; `threshold` (double) — minimum confidence score |
| **Outputs** | `int` — number of elk detections at or above the confidence threshold |
| **Functionality** | Filters detections to those labelled as elk/wapiti/cervus canadensis with a confidence score meeting the threshold, and returns the total count. |
| **Dependencies** | `extractDetections`, `getDetectionLabel`, `getDetectionScore` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `EmailProcessor.pollAndProcess`, `MessagingController.sendGridEmailWebhook` |

---

### `formatDetectionsForConsole(Map<String, Object> payload)`

| Field | Detail |
|---|---|
| **Inputs** | `payload` (Map<String, Object>) — parsed API response |
| **Outputs** | `List<String>` — human-readable lines, one per detection, e.g. `"prediction 0: elk (confidence=87.3%)"` |
| **Functionality** | Formats every detection in the API response as a labeled confidence string for console logging. |
| **Dependencies** | `extractDetections`, `getDetectionLabel`, `getDetectionScore` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `EmailProcessor.pollAndProcess` |

---

### `resolveApiKey(String cliKey)`

| Field | Detail |
|---|---|
| **Inputs** | `cliKey` (String) — API key passed via CLI argument (may be null) |
| **Outputs** | `String` — resolved, non-blank API key |
| **Functionality** | Returns the first non-blank key found across three sources in priority order: CLI argument → `ANIMALDETECT_API_KEY` env var → `SecretConfig` JSON file. |
| **Dependencies** | `SecretConfig` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `EmailProcessor.pollAndProcess`, `MessagingController.sendGridEmailWebhook` |

---

## App.java

Spring Boot application entry point. Bootstraps the server and optionally initializes the database schema on startup.

---

### `main(String[] args)`

| Field | Detail |
|---|---|
| **Inputs** | `args` (String[]) — command-line arguments passed to the JVM |
| **Outputs** | void — launches the Spring Boot application context |
| **Functionality** | Entry point; bootstraps the entire Spring Boot application. |
| **Dependencies** | `org.springframework.boot.SpringApplication` |
| **Called by** | JVM on startup |

---

### `initializeSchemaOnStartup()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | `CommandLineRunner` — a Spring bean that runs after the context is fully loaded |
| **Functionality** | Conditionally runs `db.initializeSchemaAtStartup()` if the `INIT_SCHEMA_ON_STARTUP` environment variable is set to `"true"`, `"1"`, or `"yes"`. |
| **Dependencies** | `org.springframework.boot.CommandLineRunner`, `db.initializeSchemaAtStartup` |
| **Called by** | Spring Boot framework after application context startup |

---

## Config.java

Holds application-wide configuration constants loaded from `SecretConfig` (environment variables or `app-secrets.json`). All fields are resolved once at class-load time. This class contains no methods — all members are `public static final` fields consumed primarily by `Messenger`.

---

## db.java

Data access layer for the `postgres.images` table on Cloud SQL. Handles schema setup, CRUD operations, metadata loading, and weather enrichment.

---

### `initializeSchemaAtStartup()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | void |
| **Functionality** | Opens a short-lived connection and calls `setupSchema` to create or migrate the images table; intended for use only at application startup. |
| **Dependencies** | `connect()`, `setupSchema(Connection, boolean)` |
| **Called by** | `App.initializeSchemaOnStartup` (via `CommandLineRunner` bean) |

---

### `setupSchema(Connection conn)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection |
| **Outputs** | void |
| **Functionality** | Delegates to `setupSchema(conn, true)`, which drops and recreates the images table. |
| **Dependencies** | `setupSchema(Connection, boolean)` |
| **Called by** | Test code and manual tooling that needs a fresh schema |

---

### `setupSchema(Connection conn, boolean resetTable)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `resetTable` (boolean) — if true, drops the images table before recreating it |
| **Outputs** | void |
| **Functionality** | Creates the postgres schema and images table if they do not exist, then runs `ALTER TABLE ADD COLUMN IF NOT EXISTS` statements to apply any new columns. |
| **Dependencies** | `java.sql.Statement`, `java.sql.Connection` |
| **Called by** | `initializeSchemaAtStartup`, `setupSchema(Connection)` |

---

### `connect()`

| Field | Detail |
|---|---|
| **Inputs** | None (reads all connection parameters from `SecretConfig`) |
| **Outputs** | `Connection` — an open JDBC connection to the Cloud SQL PostgreSQL instance |
| **Functionality** | Reads all connection parameters from `SecretConfig` and establishes a connection via the Cloud SQL socket factory. |
| **Dependencies** | `SecretConfig`, `java.sql.DriverManager`, `com.google.cloud.sql.postgres.SocketFactory` |
| **Called by** | `initializeSchemaAtStartup`, `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `FileProcessor.processAllUnprocessedWithPythonInference`, `EmailProcessor.pollAndProcess`, `MessagingController.smsWebhook`, `MessagingController.sendGridEmailWebhook`, `ImageStatsController.getImagesSummary`, `ImageStatsController.getImageLocations` |

---

### `loadMetadata(File f)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — image file on disk |
| **Outputs** | `Metadata` — populated metadata object (EXIF, GPS, weather, hash, dimensions) |
| **Functionality** | Delegates to `loadMetadata(f, false)`, meaning EXIF parse failures are not suppressed. |
| **Dependencies** | `loadMetadata(File, boolean)` |
| **Called by** | `EmailProcessor.pollAndProcess`, `MessagingController.smsWebhook`, `MessagingController.sendGridEmailWebhook`, `ImageUtils.main` |

---

### `loadMetadata(File f, boolean assumeExifParsable)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — image file on disk; `assumeExifParsable` (boolean) — if true, silently ignores EXIF parse failures |
| **Outputs** | `Metadata` — fully populated `Metadata` object including hash, dimensions, GPS, and weather |
| **Functionality** | Parses EXIF/GPS data from the file, computes the SHA-256 hash, reads image dimensions, and fetches historical weather data for the GPS location and photo timestamp. |
| **Dependencies** | `ImageUtils.parse`, `ImageUtils.parseExifFromPng`, `ImageUtils.sha256`, `ImageUtils.getWidth`, `ImageUtils.getHeight`, `populateWeather` |
| **Called by** | `loadMetadata(File)`, `EmailProcessor.pollAndProcess`, `MessagingController.smsWebhook`, `MessagingController.sendGridEmailWebhook`, `ImageUtils.main` |

---

### `weatherCodeToString(int code)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `code` (int) — WMO weather interpretation code |
| **Outputs** | `String` — human-readable weather description (e.g. `"Clear sky"`, `"Rain"`) |
| **Functionality** | Maps Open-Meteo WMO weather codes to descriptive strings; returns `"Unknown"` for unrecognized codes. |
| **Dependencies** | None |
| **Called by** | `populateWeather` |

---

### `populateWeather(Metadata meta)`

| Field | Detail |
|---|---|
| **Inputs** | `meta` (Metadata) — partially populated metadata with latitude, longitude, and datetime fields |
| **Outputs** | void — sets `meta.temperature_c`, `meta.humidity`, and `meta.weather_desc` in place |
| **Functionality** | Calls the Open-Meteo archive API to fetch hourly temperature, humidity, and weather code for the photo's GPS location and timestamp; silently ignores any errors. |
| **Dependencies** | `java.net.http.HttpClient`, `java.net.http.HttpRequest/HttpResponse`, `java.time.LocalDateTime`, `weatherCodeToString` |
| **Called by** | `loadMetadata(File, boolean)` |

---

### `insertMeta(Connection conn, Metadata meta)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `meta` (Metadata) — fully populated metadata object |
| **Outputs** | void — inserts one row into `postgres.images` |
| **Functionality** | Inserts all metadata fields for a newly uploaded image, including GPS, weather, and elk count. |
| **Dependencies** | `java.sql.PreparedStatement`, `java.sql.Types` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `EmailProcessor.pollAndProcess`, `MessagingController.smsWebhook`, `MessagingController.sendGridEmailWebhook` |

---

### `getUnprocessedImages(Connection conn, int batchSize)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `batchSize` (int) — maximum number of rows to return |
| **Outputs** | `List<Metadata>` — unprocessed image records ordered by upload time (oldest first) |
| **Functionality** | Queries `postgres.images` for rows where `processed_status = false`, up to `batchSize` rows. |
| **Dependencies** | `java.sql.PreparedStatement`, `java.sql.ResultSet`, `buildMetadataFromResultSet` |
| **Called by** | `FileProcessor.processAllUnprocessedWithAnimalDetect`, `FileProcessor.processAllUnprocessedWithPythonInference` |

---

### `buildMetadataFromResultSet(ResultSet rs)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `rs` (ResultSet) — positioned on a row from `postgres.images` |
| **Outputs** | `Metadata` — object populated from the current ResultSet row |
| **Functionality** | Maps all columns of the images table to their corresponding `Metadata` fields, handling nullable numeric columns correctly. |
| **Dependencies** | `java.sql.ResultSet`, `java.sql.Timestamp` |
| **Called by** | `getUnprocessedImages`, `getImageByHash`, `getImagesByDateRange`, `getImagesByLocation` |

---

### `getImageByHash(Connection conn, String hash)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `hash` (String) — SHA-256 image hash |
| **Outputs** | `Metadata` — matching row if found and `processed_status = true`, otherwise `null` |
| **Functionality** | Looks up a single processed image record by its content hash for duplicate detection. |
| **Dependencies** | `java.sql.PreparedStatement`, `java.sql.ResultSet`, `buildMetadataFromResultSet` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `EmailProcessor.pollAndProcess`, `MessagingController.smsWebhook`, `MessagingController.sendGridEmailWebhook` |

---

### `getImagesByDateRange(Connection conn, String startDate, String endDate)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `startDate` (String) — inclusive start date (`yyyy-MM-dd`); `endDate` (String) — inclusive end date (`yyyy-MM-dd`) |
| **Outputs** | `List<Metadata>` — processed images taken within the date range, ordered by `datetime_taken DESC` |
| **Functionality** | Retrieves all processed image records whose `datetime_taken` falls within the given date range. |
| **Dependencies** | `java.sql.PreparedStatement`, `java.sql.ResultSet`, `buildMetadataFromResultSet` |
| **Called by** | Available for reporting or query endpoints; not currently wired to a controller |

---

### `getImagesByLocation(Connection conn, double centerLat, double centerLon, double radiusKm)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `centerLat` (double) — center latitude in decimal degrees; `centerLon` (double) — center longitude in decimal degrees; `radiusKm` (double) — search radius in kilometers |
| **Outputs** | `List<Metadata>` — processed images with GPS within the radius, ordered by distance ASC |
| **Functionality** | Uses the Haversine formula in SQL to find all processed images within a given radius of a GPS coordinate. |
| **Dependencies** | `java.sql.PreparedStatement`, `java.sql.ResultSet`, `buildMetadataFromResultSet` |
| **Called by** | Available for location-based query endpoints; not currently wired to a controller |

---

### `updateMetaWithDetection(Connection conn, String sha256Hash, Integer elkCount, boolean processedStatus)`

| Field | Detail |
|---|---|
| **Inputs** | `conn` (Connection) — active database connection; `sha256Hash` (String) — SHA-256 hash identifying the image row; `elkCount` (Integer) — detected elk count (may be null if detection failed); `processedStatus` (boolean) — true if detection completed successfully |
| **Outputs** | void — updates `elk_count` and `processed_status` for the matching row |
| **Functionality** | Writes animal detection results back to the database after the AnimalDetect API call completes; commits the transaction if auto-commit is disabled. |
| **Dependencies** | `java.sql.PreparedStatement`, `java.sql.Types` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `FileProcessor.processAllUnprocessedWithPythonInference`, `EmailProcessor.pollAndProcess`, `MessagingController.sendGridEmailWebhook` |

---

## EmailProcessor.java

Polls a Gmail inbox for unread emails containing image attachments, runs each image through the upload pipeline, and replies to the sender with elk count results.

---

### `buildGmailService()` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | None (reads `GMAIL_CREDENTIALS_PATH` and `GMAIL_TOKEN_PATH` from `SecretConfig`) |
| **Outputs** | `Gmail` — authorized Gmail API client |
| **Functionality** | Builds an OAuth2-authorized Gmail service client, opening a local browser for first-run authorization and caching the token for subsequent runs. |
| **Dependencies** | `com.google.api.services.gmail.Gmail`, `GoogleAuthorizationCodeFlow`, `GoogleNetHttpTransport`, `FileDataStoreFactory`, `LocalServerReceiver`, `SecretConfig` |
| **Called by** | `pollAndProcess` |

---

### `pollAndProcess()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | void — side effects: uploads images to GCS, inserts DB records, sends email replies, marks emails as read |
| **Functionality** | Polls the Gmail inbox for unread emails with image attachments, runs each image through the full pipeline (EXIF parsing, GCS upload, DB insert, AnimalDetect), replies to the sender with elk counts, and marks messages as read. |
| **Dependencies** | `buildGmailService`, `AnimalDetectAPI`, `db.loadMetadata`, `db.connect`, `db.getImageByHash`, `db.insertMeta`, `db.updateMetaWithDetection`, `GoogleCloudStorageAPI.uploadFile`, `sendReply`, `markAsRead`, `collectImageAttachmentParts`, `SecretConfig` |
| **Called by** | `EventScheduler.runEmailPollingJob`, `TaskController.pollOnStartup` |

---

### `markAsRead(Gmail gmail, String user, String messageId)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `gmail` (Gmail) — authorized Gmail client; `user` (String) — Gmail user ID (`"me"`); `messageId` (String) — ID of the message to mark as read |
| **Outputs** | void — removes the UNREAD label from the specified message |
| **Functionality** | Calls the Gmail API to remove the UNREAD label, silently logging any errors. |
| **Dependencies** | `com.google.api.services.gmail.Gmail` |
| **Called by** | `pollAndProcess` |

---

### `sendReply(Gmail gmail, String user, String toEmail, String originalSubject, String bodyText)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `gmail` (Gmail) — authorized Gmail client; `user` (String) — Gmail user ID (`"me"`); `toEmail` (String) — recipient address; `originalSubject` (String) — subject of the original email; `bodyText` (String) — plain-text reply body |
| **Outputs** | void — sends a reply email via the Gmail API |
| **Functionality** | Constructs a `MimeMessage` reply and sends it through the Gmail API, prepending `"Re: "` to the subject if not already present. |
| **Dependencies** | `com.google.api.services.gmail.Gmail`, `jakarta.mail.Session`, `jakarta.mail.internet.MimeMessage`, `java.util.Base64` |
| **Called by** | `pollAndProcess` |

---

### `extractHeader(Message message, String headerName)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `message` (Message) — full Gmail message object; `headerName` (String) — header to look up |
| **Outputs** | `String` — header value, or `null` if the header is not present |
| **Functionality** | Searches the message payload headers for a case-insensitive name match and returns its value. |
| **Dependencies** | `com.google.api.services.gmail.model.Message`, `com.google.api.services.gmail.model.MessagePartHeader` |
| **Called by** | `pollAndProcess` |

---

### `extractEmailAddress(String headerValue)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `headerValue` (String) — raw From/To header value (e.g. `"Name <addr@example.com>"`) |
| **Outputs** | `String` — bare email address, or the trimmed header value if no angle-bracket format is found |
| **Functionality** | Extracts the email address from an RFC 2822 name-addr header value. |
| **Dependencies** | None |
| **Called by** | `pollAndProcess` |

---

### `collectImageAttachmentParts(MessagePart part, List<MessagePart> imageParts, boolean attachmentsOnly)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `part` (MessagePart) — root or nested message part to inspect; `imageParts` (List\<MessagePart\>) — accumulator list populated with matching parts; `attachmentsOnly` (boolean) — if true, only parts with an `attachmentId` are included |
| **Outputs** | void — populates `imageParts` in place |
| **Functionality** | Recursively walks the MIME tree of a Gmail message, collecting leaf parts whose MIME type is an allowed image type and that have an attachment ID. |
| **Dependencies** | `com.google.api.services.gmail.model.MessagePart`, `isAllowedMimeType` |
| **Called by** | `pollAndProcess` |

---

### `isAllowedMimeType(String mimeType)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `mimeType` (String) — MIME type string to check |
| **Outputs** | `boolean` — `true` if the MIME type is in the `ALLOWED_MIME_TYPES` list |
| **Functionality** | Case-insensitive check of whether a MIME type represents an accepted image format. |
| **Dependencies** | None |
| **Called by** | `collectImageAttachmentParts` |

---

## EventScheduler.java

Spring component that acts as the trigger layer for background jobs. Currently wires into the Gmail polling job; a weekly AnimalDetect batch job is stubbed but commented out.

---

### `runEmailPollingJob()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | void — delegates entirely to `EmailProcessor.pollAndProcess` |
| **Functionality** | Entry point for the hourly Gmail polling job; intended to be triggered by a Cloud Scheduler HTTP call to `/internal/tasks/poll-email` or on application startup. |
| **Dependencies** | `EmailProcessor.pollAndProcess` |
| **Called by** | `TaskController.pollOnStartup` (on startup event), `TaskController.runEmailPollingTask` (via HTTP POST `/internal/tasks/poll-email`) |

---

## FileProcessor.java

Core processing pipeline. Handles multipart file uploads, GCS storage, database insertion, AnimalDetect inference, and batch reprocessing of backlogged images.

---

### `PythonInferenceClient(RestClient.Builder builder)` *(private inner constructor)*

| Field | Detail |
|---|---|
| **Inputs** | `builder` (RestClient.Builder) — Spring RestClient builder |
| **Outputs** | `PythonInferenceClient` instance pointing at `http://localhost:8000` |
| **Functionality** | Constructs a `RestClient` forced to HTTP/1.1 to avoid h2c upgrade issues with the local Uvicorn inference server. |
| **Dependencies** | `org.springframework.web.client.RestClient`, `org.springframework.http.client.JdkClientHttpRequestFactory`, `java.net.http.HttpClient` |
| **Called by** | `processAllUnprocessedWithPythonInference` |

---

### `inferCount(byte[] imageBytes)` *(private inner)*

| Field | Detail |
|---|---|
| **Inputs** | `imageBytes` (byte[]) — raw JPEG image bytes |
| **Outputs** | `Integer` — elk count returned by the inference server, or `null` on failure |
| **Functionality** | POSTs image bytes to the local Python inference server at `/infer` and parses the integer count from the plain-text response. |
| **Dependencies** | `org.springframework.web.client.RestClient` |
| **Called by** | `inferCounts` |

---

### `inferCounts(List<ImagePayload> images)` *(private inner)*

| Field | Detail |
|---|---|
| **Inputs** | `images` (List\<ImagePayload\>) — list of filename + bytes pairs |
| **Outputs** | `List<Integer>` — elk counts in the same order as the input list (`null` for failures) |
| **Functionality** | Sequentially calls `inferCount` for each image and collects the results. |
| **Dependencies** | `inferCount` |
| **Called by** | `processAllUnprocessedWithPythonInference` |

---

### `uploadAndProcessFiles(MultipartFile[] files, String metadataJson)`

| Field | Detail |
|---|---|
| **Inputs** | `files` (MultipartFile[]) — uploaded image files; `metadataJson` (String) — optional JSON array of per-file metadata (may be null) |
| **Outputs** | `List<Map<String, Object>>` — one entry per file with upload status, cloud URI, SHA-256 hash, elk count, and metadata fields |
| **Functionality** | Validates files, parses optional metadata, uploads each image to GCS, inserts a DB record, runs AnimalDetect, and updates the elk count — all in one synchronous pass. |
| **Dependencies** | `validateUploadedFiles`, `parseUploadMetadata`, `buildMetadataForUpload`, `db.connect`, `db.getImageByHash`, `db.insertMeta`, `db.updateMetaWithDetection`, `GoogleCloudStorageAPI.uploadFile`, `AnimalDetectAPI`, `ImageUtils` |
| **Called by** | `FileUploadController.uploadFileInstantProcessed`, `MessagingController.sendImageTest` |

---

### `processUnprocessedBatch()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | `BatchResult` — counts of attempted, processed, and error messages |
| **Functionality** | Public alias for `processAllUnprocessedWithAnimalDetect`; processes the full backlog of unprocessed images using the AnimalDetect API. |
| **Dependencies** | `processAllUnprocessedWithAnimalDetect` |
| **Called by** | External callers that prefer the generic "batch" naming |

---

### `processAllUnprocessedWithPythonInference()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | `BatchResult` — counts of attempted, processed, and per-image error messages |
| **Functionality** | Fetches all unprocessed images from GCS, sends them to the local Python inference server in batch, and writes elk counts back to the database. |
| **Dependencies** | `db.connect`, `db.getUnprocessedImages`, `db.updateMetaWithDetection`, `PythonInferenceClient`, `downloadFromCloudUri`, `ImageUtils`, `SecretConfig` |
| **Called by** | Not currently wired to a scheduled trigger; available for manual invocation |

---

### `validateUploadedFiles(MultipartFile[] files)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `files` (MultipartFile[]) — array of uploaded files to validate |
| **Outputs** | void — throws `IllegalArgumentException` if any file is missing or has a disallowed extension |
| **Functionality** | Ensures at least one file is present and that every file has an allowed image extension (`.png`, `.jpg`, `.jpeg`, `.heic`). |
| **Dependencies** | `isAllowedImageName` |
| **Called by** | `uploadAndProcessFiles` |

---

### `parseUploadMetadata(MultipartFile[] files, String metadataJson)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `files` (MultipartFile[]) — uploaded files (used only for length validation); `metadataJson` (String) — JSON array of `UploadMetadataData` objects (may be null or blank) |
| **Outputs** | `List<UploadMetadataData>` — parsed metadata list, or an empty list if no JSON was provided |
| **Functionality** | Deserializes the optional per-file metadata JSON and validates that its length matches the number of uploaded files. |
| **Dependencies** | `com.fasterxml.jackson.databind.ObjectMapper` |
| **Called by** | `uploadAndProcessFiles` |

---

### `buildMetadataForUpload(Path tempFile, String originalName, UploadMetadataData uploadData, int fileIndex)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `tempFile` (Path) — path to the temporary file on disk; `originalName` (String) — original client-provided filename; `uploadData` (UploadMetadataData) — optional parsed metadata (may be null); `fileIndex` (int) — zero-based index used in error messages for mismatched filenames |
| **Outputs** | `Metadata` — partially populated `Metadata` object (`cloud_uri` is empty; `elk_count` is null) |
| **Functionality** | Combines file-derived values (size, SHA-256) with caller-supplied metadata (GPS, dimensions, datetime, weather) into a `Metadata` object ready for DB insertion. |
| **Dependencies** | `ImageUtils.sha256`, `ImageUtils.getExtension`, `java.nio.file.Files` |
| **Called by** | `uploadAndProcessFiles` |

---

### `isAllowedImageName(String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — file name to check (may be null) |
| **Outputs** | `boolean` — `true` if the filename ends with an allowed image extension |
| **Functionality** | Case-insensitive suffix check against the `ALLOWED_EXTENSIONS` list. |
| **Dependencies** | None |
| **Called by** | `validateUploadedFiles` |

---

### `normalizedStorageExtension(String ext)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `ext` (String) — raw file extension without a leading dot (e.g. `"jpg"`, `"png"`) |
| **Outputs** | `String` — normalized extension with a leading dot (e.g. `".jpeg"`, `".png"`, `".bin"`) |
| **Functionality** | Normalizes `"jpg"` and `"jpeg"` to `".jpeg"` and prepends `"."` to other extensions; returns `".bin"` for null or blank input. |
| **Dependencies** | None |
| **Called by** | `uploadAndProcessFiles` |

---

### `addMetadataToFileInfo(Map<String, Object> fileInfo, Metadata meta)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `fileInfo` (Map\<String, Object\>) — map to populate in place; `meta` (Metadata) — source of metadata values to copy into the map |
| **Outputs** | void — adds metadata fields (filename, size, dimensions, GPS, datetime, weather, elkCount) to the map |
| **Functionality** | Copies human-facing metadata fields from a `Metadata` object into a response map for the upload API JSON response. |
| **Dependencies** | None |
| **Called by** | `uploadAndProcessFiles` |

---

### `processAllUnprocessedWithAnimalDetect()`

| Field | Detail |
|---|---|
| **Inputs** | None |
| **Outputs** | `BatchResult` — counts of attempted, processed, and per-image error messages |
| **Functionality** | Fetches all unprocessed images from GCS, runs each through AnimalDetect, and writes elk counts back to the database; used by the weekly batch job and manual runs. |
| **Dependencies** | `db.connect`, `db.getUnprocessedImages`, `db.updateMetaWithDetection`, `AnimalDetectAPI`, `downloadFromCloudUri`, `ImageUtils` |
| **Called by** | `processUnprocessedBatch`, `EventScheduler.runWeeklyInferenceBatch` (commented out), `FileProcessor.main` |

---

### `downloadFromCloudUri(String cloudUri, Path destinationPath)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `cloudUri` (String) — `gs://` URI of the object to download (e.g. `"gs://bucket/object.jpeg"`); `destinationPath` (Path) — local file path to write the downloaded bytes to |
| **Outputs** | void — writes the GCS object to `destinationPath` |
| **Functionality** | Parses the `gs://` URI to extract bucket and object name, authenticates with GCS using credentials from `SecretConfig`, and downloads the blob to the given path. |
| **Dependencies** | `com.google.cloud.storage.Storage`, `com.google.auth.oauth2.GoogleCredentials`, `SecretConfig`, `java.nio.file.Files` |
| **Called by** | `processAllUnprocessedWithAnimalDetect`, `processAllUnprocessedWithPythonInference` |

---

### `main(String[] args)`

| Field | Detail |
|---|---|
| **Inputs** | `args` (String[]) — unused command-line arguments |
| **Outputs** | void — prints batch results and errors to stdout/stderr |
| **Functionality** | Standalone entry point for running the AnimalDetect batch processor from the command line without starting the full Spring Boot server. |
| **Dependencies** | `processAllUnprocessedWithAnimalDetect` |
| **Called by** | JVM when run directly (e.g. `mvn exec:java -Dexec.mainClass=com.example.FileProcessor`) |

---

## FileUploadController.java

Spring REST controller exposing the `POST /api/upload` endpoint for multipart image uploads.

---

### `uploadFileInstantProcessed(MultipartFile[] files, String metadataJson)`

| Field | Detail |
|---|---|
| **Inputs** | `files` (MultipartFile[]) — one or more image files from the multipart request; `metadataJson` (String, optional) — JSON array of per-file metadata (GPS, datetime, etc.) |
| **Outputs** | `ResponseEntity<?>` — 200 OK with upload results map on success; 400 Bad Request for invalid input; 500 Internal Server Error for unexpected failures |
| **Functionality** | HTTP `POST /api/upload` handler that delegates to `FileProcessor.uploadAndProcessFiles` for GCS upload, DB insertion, and live AnimalDetect inference. |
| **Dependencies** | `FileProcessor.uploadAndProcessFiles`, `org.springframework.web.multipart.MultipartFile`, `org.springframework.http.ResponseEntity` |
| **Called by** | HTTP clients (frontend dashboard, mobile apps, curl) via `POST /api/upload` |

---

## GoogleCloudStorageAPI.java

Wrapper around the Google Cloud Storage Java SDK for uploading and downloading image files to/from the configured GCS bucket.

---

### `buildStorage()` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | None (reads `GCS_PROJECT_ID` and `GCS_CREDENTIALS_PATH` from `SecretConfig`) |
| **Outputs** | `Storage` — authenticated Google Cloud Storage client |
| **Functionality** | Builds a GCS `Storage` client, loading service-account credentials from the path in `SecretConfig` if the file exists, otherwise falling back to application-default credentials. |
| **Dependencies** | `com.google.cloud.storage.StorageOptions`, `com.google.auth.oauth2.GoogleCredentials`, `SecretConfig`, `java.nio.file.Files` |
| **Called by** | `uploadFile(MultipartFile)`, `uploadFile(String, String)`, `downloadFile` |

---

### `main(String[] args)`

| Field | Detail |
|---|---|
| **Inputs** | `args` (String[]) — unused |
| **Outputs** | void — uploads a hardcoded test image and downloads it to a local path |
| **Functionality** | Manual smoke-test entry point for verifying GCS upload/download connectivity. |
| **Dependencies** | `uploadFile(String, String)`, `downloadFile` |
| **Called by** | JVM when run directly for testing |

---

### `uploadFile(MultipartFile file)`

| Field | Detail |
|---|---|
| **Inputs** | `file` (MultipartFile) — image file from a multipart HTTP request |
| **Outputs** | `String` — GCS object name (SHA-256 hash of the file bytes) |
| **Functionality** | Hashes the file bytes to derive a unique object name, then uploads the file to the configured GCS bucket under that name. |
| **Dependencies** | `buildStorage`, `ImageUtils.sha256`, `com.google.cloud.storage.Storage`, `com.google.cloud.storage.BlobId`, `com.google.cloud.storage.BlobInfo` |
| **Called by** | Not currently called from production paths (superseded by `uploadFile(String, String)`); available for direct multipart upload use cases |

---

### `uploadFile(String localFilePath, String objectName)`

| Field | Detail |
|---|---|
| **Inputs** | `localFilePath` (String) — absolute or relative path to the file on disk; `objectName` (String) — GCS object key to store the file under |
| **Outputs** | void — uploads the file to the configured GCS bucket |
| **Functionality** | Streams a local file directly to GCS using `Storage.createFrom`, avoiding loading the entire file into heap memory. |
| **Dependencies** | `buildStorage`, `com.google.cloud.storage.Storage`, `com.google.cloud.storage.BlobId`, `com.google.cloud.storage.BlobInfo` |
| **Called by** | `FileProcessor.uploadAndProcessFiles`, `EmailProcessor.pollAndProcess`, `MessagingController.smsWebhook`, `MessagingController.sendGridEmailWebhook` |

---

### `downloadFile(String objectName, String destinationPath)`

| Field | Detail |
|---|---|
| **Inputs** | `objectName` (String) — GCS object key to download; `destinationPath` (String) — local file path to write the downloaded bytes to |
| **Outputs** | void — writes the GCS object contents to the specified local path |
| **Functionality** | Downloads a blob from the configured GCS bucket to a local file path. |
| **Dependencies** | `buildStorage`, `com.google.cloud.storage.Storage`, `com.google.cloud.storage.BlobId` |
| **Called by** | `GoogleCloudStorageAPI.main` (manual testing) |

---

## ImageStatsController.java

Spring REST controller exposing dashboard aggregation endpoints consumed by the frontend.

---

### `getImagesSummary()`

| Field | Detail |
|---|---|
| **Inputs** | None (HTTP `GET /api/images/summary`) |
| **Outputs** | `ResponseEntity<?>` — 200 OK with a JSON object containing `uploadsByDate` (list of `{date, count, elkTotal}`), `total`, `withGps`, `withoutGps`, `totalElk`, `processedCount`; 500 on DB failure |
| **Functionality** | Aggregates three DB queries — daily upload counts with elk totals, GPS coverage counts, and processed image count — into a single summary response for the frontend dashboard. |
| **Dependencies** | `db.connect`, `java.sql.PreparedStatement`, `java.sql.ResultSet`, `org.springframework.http.ResponseEntity` |
| **Called by** | HTTP clients (frontend dashboard) via `GET /api/images/summary` |

---

### `getImageLocations()`

| Field | Detail |
|---|---|
| **Inputs** | None (HTTP `GET /api/images/locations`) |
| **Outputs** | `ResponseEntity<?>` — 200 OK with JSON `{"locations": [{filename, latitude, longitude, altitude, datetimeTaken, elkCount}, ...]}` ordered by `datetime_taken DESC`; 500 on DB failure |
| **Functionality** | Returns all images that have GPS coordinates so the frontend map can plot observation locations with elk count markers. |
| **Dependencies** | `db.connect`, `java.sql.PreparedStatement`, `java.sql.ResultSet`, `org.springframework.http.ResponseEntity` |
| **Called by** | HTTP clients (frontend dashboard map view) via `GET /api/images/locations` |

---

## ImageUtils.java

Low-level image utility library. Handles JPEG/PNG conversion, EXIF parsing (date, GPS, altitude), SHA-256 hashing, and metadata comparison.

---

### `convertToJpg(File f)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — source image file (any supported format) |
| **Outputs** | `File` — the original file if already JPEG; otherwise a new `.jpeg` file in the same directory |
| **Functionality** | Converts non-JPEG images to JPEG: uses macOS `sips` for HEIC/HEIF files and `ImageIO` for other formats. |
| **Dependencies** | `javax.imageio.ImageIO`, `java.lang.ProcessBuilder` (sips command) |
| **Called by** | Legacy/manual tooling; not currently wired into the main upload pipeline |

---

### `getWidth(File f)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — image file on disk |
| **Outputs** | `int` — pixel width of the image |
| **Functionality** | Reads the image fully into memory via `ImageIO` and returns its width. |
| **Dependencies** | `javax.imageio.ImageIO` |
| **Called by** | `db.loadMetadata` |

---

### `getHeight(File f)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — image file on disk |
| **Outputs** | `int` — pixel height of the image |
| **Functionality** | Reads the image fully into memory via `ImageIO` and returns its height. |
| **Dependencies** | `javax.imageio.ImageIO` |
| **Called by** | `db.loadMetadata` |

---

### `getExtension(String filename)`

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — file name, possibly including a path |
| **Outputs** | `String` — extension without leading dot (e.g. `"jpg"`), or `""` if none |
| **Functionality** | Extracts the file extension by finding the last `'.'` in the filename. |
| **Dependencies** | None |
| **Called by** | `db.loadMetadata`, `FileProcessor.uploadAndProcessFiles`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `FileProcessor.processAllUnprocessedWithPythonInference`, `FileProcessor.downloadFromCloudUri`, `AnimalDetectAPI.detectImageContentType`, `convertToJpg`, `convertPngToJpg` |

---

### `removeExtension(String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — file name with or without extension |
| **Outputs** | `String` — filename without the trailing `.ext` portion |
| **Functionality** | Strips the last dot-separated extension from a filename. |
| **Dependencies** | None |
| **Called by** | `convertToJpg`, `convertPngToJpg` |

---

### `convertPngToJpg(File f)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — PNG source file |
| **Outputs** | `File` — JPEG file in the same directory with EXIF data preserved |
| **Functionality** | Converts a PNG to JPEG, extracts any embedded EXIF chunk from the PNG, composites alpha onto white, and re-injects the EXIF data into the JPEG. |
| **Dependencies** | `javax.imageio.ImageIO`, `java.awt.Graphics2D`, `extractExifFromPng`, `insertExifIntoJpeg`, `java.io.BufferedOutputStream` |
| **Called by** | `ImageUtils.main` (test/manual conversion) |

---

### `extractExifFromPng(byte[] pngBytes)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `pngBytes` (byte[]) — raw PNG file bytes |
| **Outputs** | `byte[]` — raw EXIF bytes from the PNG `eXIf` chunk, or `null` if no such chunk exists |
| **Functionality** | Walks the PNG chunk list to find and extract the `eXIf` chunk payload. |
| **Dependencies** | None (manual byte parsing) |
| **Called by** | `convertPngToJpg`, `parseExifFromPng` |

---

### `parseExifFromPng(File f)`

| Field | Detail |
|---|---|
| **Inputs** | `f` (File) — PNG image file |
| **Outputs** | `ExifData` — parsed EXIF fields (date, lat, lon, alt), or `null` if no EXIF chunk found |
| **Functionality** | Reads the PNG file, extracts the `eXIf` chunk, normalizes the EXIF header, and delegates to `parseExif` for field extraction. |
| **Dependencies** | `extractExifFromPng`, `ensureExifHeader`, `parseExif`, `java.nio.file.Files` |
| **Called by** | `db.loadMetadata` |

---

### `parse(String file)`

| Field | Detail |
|---|---|
| **Inputs** | `file` (String) — absolute path to a JPEG image file |
| **Outputs** | `ExifData` — parsed EXIF fields (date, lat, lon, alt); empty `ExifData` if no APP1 segment found |
| **Functionality** | Reads the JPEG binary, locates the APP1 (0xFFE1) marker, extracts the EXIF segment, and delegates to `parseExif`. |
| **Dependencies** | `java.io.RandomAccessFile`, `parseExif` |
| **Called by** | `db.loadMetadata` |

---

### `parseExif(byte[] buf)`

| Field | Detail |
|---|---|
| **Inputs** | `buf` (byte[]) — raw EXIF APP1 segment bytes (starting with `"Exif\0\0"`) |
| **Outputs** | `ExifData` — parsed EXIF fields including date, GPS coordinates, and altitude |
| **Functionality** | Parses the TIFF header to determine byte order, then delegates to `parseIFD` to extract date and GPS data from the IFD chain. |
| **Dependencies** | `parseIFD`, `java.nio.ByteBuffer`, `java.nio.ByteOrder` |
| **Called by** | `parse`, `parseExifFromPng` |

---

### `parseIFD(ByteBuffer bb, int offset, ExifData d)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bb` (ByteBuffer) — buffer positioned at the TIFF data; `offset` (int) — absolute byte offset of IFD0; `d` (ExifData) — accumulator for parsed fields |
| **Outputs** | void — populates `d` in place via `parseExifIFD` and `parseGPSIFD` |
| **Functionality** | Reads IFD0 entries to find the Exif sub-IFD (tag `0x8769`) and GPS IFD (tag `0x8825`) offsets, then dispatches to the appropriate sub-parsers. |
| **Dependencies** | `parseExifIFD`, `parseGPSIFD`, `java.nio.ByteBuffer` |
| **Called by** | `parseExif` |

---

### `parseExifIFD(ByteBuffer bb, int offset, ExifData d)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bb` (ByteBuffer) — buffer positioned at the TIFF data; `offset` (int) — absolute byte offset of the Exif sub-IFD; `d` (ExifData) — accumulator for parsed fields |
| **Outputs** | void — sets `d.date` from `DateTimeOriginal` tag (`0x9003`) if present |
| **Functionality** | Scans the Exif sub-IFD for the `DateTimeOriginal` tag and reads the ASCII date string. |
| **Dependencies** | `java.nio.ByteBuffer` |
| **Called by** | `parseIFD` |

---

### `charRef(ByteBuffer bb, int type, int count, int value)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bb` (ByteBuffer) — buffer positioned at the TIFF data; `type` (int) — TIFF field type (2 = ASCII); `count` (int) — number of values; `value` (int) — inline value or offset to the data |
| **Outputs** | `char` — first character of the ASCII string, or `0` if not applicable |
| **Functionality** | Reads a single ASCII character from an inline TIFF value or from an offset, used to decode GPS reference direction tags (N/S/E/W). |
| **Dependencies** | `java.nio.ByteBuffer` |
| **Called by** | `parseGPSIFD` |

---

### `parseGPSIFD(ByteBuffer bb, int offset, ExifData d)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bb` (ByteBuffer) — buffer positioned at the TIFF data; `offset` (int) — absolute byte offset of the GPS IFD; `d` (ExifData) — accumulator for parsed fields |
| **Outputs** | void — sets `d.lat`, `d.lon`, `d.alt` with sign applied from reference direction tags |
| **Functionality** | Reads the GPS IFD to extract latitude, longitude, and altitude as rational triplets, then applies N/S and E/W reference directions as sign flips. |
| **Dependencies** | `charRef`, `readRationalTriplet`, `readRational`, `java.nio.ByteBuffer` |
| **Called by** | `parseIFD` |

---

### `readRationalTriplet(ByteBuffer bb, int offset)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bb` (ByteBuffer) — buffer with correct byte order set; `offset` (int) — absolute byte offset of three consecutive TIFF RATIONAL values |
| **Outputs** | `double` — decimal degrees computed as `degrees + minutes/60 + seconds/3600` |
| **Functionality** | Reads three numerator/denominator pairs (degrees, minutes, seconds) and converts them to a single decimal-degree value. |
| **Dependencies** | `java.nio.ByteBuffer` |
| **Called by** | `parseGPSIFD` |

---

### `readRational(ByteBuffer bb, int offset)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bb` (ByteBuffer) — buffer with correct byte order set; `offset` (int) — absolute byte offset of a single TIFF RATIONAL value |
| **Outputs** | `double` — numerator / denominator |
| **Functionality** | Reads one TIFF RATIONAL (two 4-byte unsigned integers) and returns the quotient. |
| **Dependencies** | `java.nio.ByteBuffer` |
| **Called by** | `parseGPSIFD` |

---

### `sha256(File file)`

| Field | Detail |
|---|---|
| **Inputs** | `file` (File) — file to hash |
| **Outputs** | `String` — lowercase hex SHA-256 digest of the file contents |
| **Functionality** | Streams the file through a `DigestInputStream` to compute the SHA-256 hash without loading the entire file into memory. |
| **Dependencies** | `java.security.MessageDigest`, `java.security.DigestInputStream`, `java.io.FileInputStream` |
| **Called by** | `db.loadMetadata`, `FileProcessor.buildMetadataForUpload`, `FileProcessor.processAllUnprocessedWithAnimalDetect`, `FileProcessor.processAllUnprocessedWithPythonInference` |

---

### `sha256(byte[] data)`

| Field | Detail |
|---|---|
| **Inputs** | `data` (byte[]) — bytes to hash |
| **Outputs** | `String` — lowercase hex SHA-256 digest |
| **Functionality** | Computes SHA-256 of an in-memory byte array in a single digest call. |
| **Dependencies** | `java.security.MessageDigest` |
| **Called by** | `GoogleCloudStorageAPI.uploadFile(MultipartFile)` |

---

### `bytesToHex(byte[] bytes)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `bytes` (byte[]) — raw byte array |
| **Outputs** | `String` — lowercase two-character-per-byte hex string |
| **Functionality** | Converts a byte array to its lowercase hexadecimal string representation. |
| **Dependencies** | None |
| **Called by** | `sha256(File)`, `sha256(byte[])` |

---

### `insertExifIntoJpeg(byte[] jpegBytes, byte[] exifBytes)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `jpegBytes` (byte[]) — valid JPEG bytes (must start with `0xFFD8`); `exifBytes` (byte[]) — raw EXIF bytes to embed (may be null) |
| **Outputs** | `byte[]` — JPEG bytes with the EXIF APP1 segment inserted after the SOI marker |
| **Functionality** | Injects an EXIF APP1 segment into a JPEG immediately after the SOI marker, preserving all original image data. |
| **Dependencies** | `ensureExifHeader` |
| **Called by** | `convertPngToJpg` |

---

### `ensureExifHeader(byte[] exifBytes)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `exifBytes` (byte[]) — raw EXIF bytes that may or may not include the `"Exif\0\0"` header |
| **Outputs** | `byte[]` — EXIF bytes guaranteed to start with the six-byte `"Exif\0\0"` header |
| **Functionality** | Prepends the six-byte `"Exif\0\0"` header if it is not already present, so that the bytes are valid for embedding in a JPEG APP1 segment. |
| **Dependencies** | None |
| **Called by** | `insertExifIntoJpeg`, `parseExifFromPng` |

---

### `readIntBE(byte[] buf, int offset)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `buf` (byte[]) — byte array containing a big-endian 32-bit integer; `offset` (int) — byte offset within `buf` to read from |
| **Outputs** | `int` — big-endian 32-bit integer at the given offset |
| **Functionality** | Reads four bytes from `buf` at `offset` and assembles them as a big-endian int. |
| **Dependencies** | None |
| **Called by** | `extractExifFromPng` |

---

### `printMetadataComparison(String label, Metadata pngMeta, Metadata jpgMeta)`

| Field | Detail |
|---|---|
| **Inputs** | `label` (String) — display label for the comparison; `pngMeta` (Metadata) — metadata extracted from the PNG version; `jpgMeta` (Metadata) — metadata extracted from the converted JPEG version |
| **Outputs** | void — prints a match/mismatch comparison table to stdout |
| **Functionality** | Compares datetime, GPS flag, lat/lon/alt between the PNG and JPEG metadata objects and prints a human-readable result to help verify EXIF preservation during conversion. |
| **Dependencies** | `parsePngMetadata` |
| **Called by** | `ImageUtils.main` |

---

### `isMetadataEmpty(Metadata meta)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `meta` (Metadata) — metadata object to inspect |
| **Outputs** | `boolean` — `true` if both datetime and GPS fields are absent/empty |
| **Functionality** | Checks whether a `Metadata` object carries no useful EXIF data, used to decide whether to attempt a re-parse from the original PNG file. |
| **Dependencies** | None |
| **Called by** | `printMetadataComparison` |

---

### `parsePngMetadata(String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — name of the PNG file to locate and parse |
| **Outputs** | `Metadata` — parsed EXIF metadata from the PNG file, or `null` if the file cannot be found or parsed |
| **Functionality** | Resolves the file by name across several known directories, extracts the EXIF chunk, and returns a `Metadata` object; used as a fallback when in-memory metadata is empty. |
| **Dependencies** | `resolveFileByName`, `extractExifFromPng`, `ensureExifHeader`, `parseExif`, `java.nio.file.Files` |
| **Called by** | `printMetadataComparison` |

---

### `resolveFileByName(String filename)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `filename` (String) — file name to look up (without a guaranteed path) |
| **Outputs** | `File` — the located `File` object, or `null` if not found in any search path |
| **Functionality** | Tries to locate a file by checking: the exact path, the test resources directory, and the main resources directory. |
| **Dependencies** | `java.io.File` |
| **Called by** | `parsePngMetadata` |

---

### `doubleEquals(Double a, Double b, double eps)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `a` (Double) — first value (may be null); `b` (Double) — second value (may be null); `eps` (double) — maximum allowed absolute difference |
| **Outputs** | `boolean` — `true` if both are null, or if `|a - b| <= eps` |
| **Functionality** | Null-safe floating-point equality check within a given tolerance. |
| **Dependencies** | None |
| **Called by** | `printMetadataComparison` |

---

### `main(String[] args)`

| Field | Detail |
|---|---|
| **Inputs** | `args` (String[]) — unused |
| **Outputs** | void — prints metadata comparison results to stdout |
| **Functionality** | Standalone test runner that converts two test PNG images to JPEG and prints a metadata comparison to verify that EXIF data is preserved through conversion. |
| **Dependencies** | `convertPngToJpg`, `db.loadMetadata`, `printMetadataComparison`, `java.nio.file.Files` |
| **Called by** | JVM when run directly for manual testing |

---

## MessagingController.java

Spring REST controller handling three inbound image submission channels: a local test endpoint, a Twilio SMS/MMS webhook, and a SendGrid Inbound Parse webhook.

---

### `sendImageTest(MultipartFile file, String phoneNumber)`

| Field | Detail |
|---|---|
| **Inputs** | `file` (MultipartFile) — image file to process; `phone_number` (String, optional) — phone number to send the result notification to |
| **Outputs** | `ResponseEntity<?>` — 200 OK with status/metadata map on success; 400 Bad Request if file is missing or not an image; 500 on processing failure |
| **Functionality** | Local test endpoint (`POST /test/send-image`) that runs a single image through the full upload pipeline and sends an SMS-style notification via `Messenger`. |
| **Dependencies** | `FileProcessor.uploadAndProcessFiles`, `Messenger.sendReply`, `org.springframework.web.multipart.MultipartFile` |
| **Called by** | HTTP clients during local development/testing via `POST /test/send-image` |

---

### `smsWebhook(String fromPhone, String mediaUrl, String mediaContentType)`

| Field | Detail |
|---|---|
| **Inputs** | `fromPhone` (String) — sender's phone number (Twilio "From" param); `mediaUrl` (String) — URL of the attached image hosted by Twilio; `mediaContentType` (String) — MIME type of the attached image |
| **Outputs** | `ResponseEntity<String>` — always 200 OK with TwiML `"<Response></Response>"` (Twilio requires a 200 even on errors) |
| **Functionality** | Twilio webhook handler (`POST /sms`) that downloads an MMS image from Twilio, runs it through the pipeline (EXIF, GCS upload, DB insert), and replies to the landowner with GPS/weather info or a duplicate notice. |
| **Dependencies** | `db.loadMetadata`, `db.connect`, `db.getImageByHash`, `db.insertMeta`, `GoogleCloudStorageAPI.uploadFile`, `Messenger.sendReply`, `java.net.URI`, `java.nio.file.Files` |
| **Called by** | Twilio platform via `POST /sms` when a landowner texts a photo to the PERC number |

---

### `sendGridEmailWebhook(String fromEmail, String subject)`

| Field | Detail |
|---|---|
| **Inputs** | `fromEmail` (String) — sender's email address; `subject` (String) — email subject line; attachments delivered as multipart fields `attachment1`…`attachment10` in the raw HTTP request |
| **Outputs** | `ResponseEntity<String>` — always 200 OK with an empty body (SendGrid requires a 200 to stop retrying) |
| **Functionality** | SendGrid Inbound Parse webhook handler (`POST /webhook/inbound-email`) that iterates up to 10 numbered attachment fields, runs each valid image through the full pipeline (EXIF, GCS, DB, AnimalDetect), and logs results; skips duplicates. |
| **Dependencies** | `db.loadMetadata`, `db.connect`, `db.getImageByHash`, `db.insertMeta`, `db.updateMetaWithDetection`, `GoogleCloudStorageAPI.uploadFile`, `AnimalDetectAPI`, `isAllowedImageType`, `SecretConfig`, `org.springframework.web.context.request.RequestContextHolder`, `jakarta.servlet.http.HttpServletRequest` |
| **Called by** | SendGrid platform via `POST /webhook/inbound-email` when an email arrives at the configured inbound parse address |

---

### `isAllowedImageType(String contentType)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `contentType` (String) — MIME type string to check |
| **Outputs** | `boolean` — `true` if the content type is one of: `image/jpeg`, `image/jpg`, `image/png`, `image/heic` |
| **Functionality** | Guards the SendGrid webhook from processing non-image attachments. |
| **Dependencies** | None |
| **Called by** | `sendGridEmailWebhook` |

---

## Messenger.java

Thin routing layer that dispatches outbound messages to the correct channel (local stdout, Twilio SMS, or Telegram stub) based on `Config.MESSAGING_MODE`.

---

### `sendReply(String toPhone, String messageText)`

| Field | Detail |
|---|---|
| **Inputs** | `toPhone` (String) — destination phone number (E.164 format); `messageText` (String) — message body to send |
| **Outputs** | void — sends or logs the message depending on `MESSAGING_MODE` |
| **Functionality** | Routes an outbound message to the appropriate channel based on the `MESSAGING_MODE` config value: `"local"` prints to stdout, `"twilio"` sends an SMS via the Twilio API, `"telegram"` logs a stub, and any other mode logs an unknown-mode warning. Falls back to `Config.DEFAULT_PHONE_NUMBER` if `toPhone` is null or blank. |
| **Dependencies** | `Config` (`MESSAGING_MODE`, `DEFAULT_PHONE_NUMBER`, `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_PHONE_NUMBER`), `com.twilio.Twilio`, `com.twilio.rest.api.v2010.account.Message`, `com.twilio.type.PhoneNumber` |
| **Called by** | `MessagingController.sendImageTest`, `MessagingController.smsWebhook` |

---

## Metadata.java

Plain data object (POJO) holding all fields for a single image record. No methods — fields are set directly by `db.loadMetadata`, `FileProcessor.buildMetadataForUpload`, and their callers.

| Field | Type | Description |
|---|---|---|
| `sha256` | String | SHA-256 hash of the raw image bytes |
| `cloud_uri` | String | GCS object URI (`gs://bucket/object`) |
| `humidity` | Double | Relative humidity (%) at photo time/location |
| `temperature_c` | Double | Temperature in Celsius at photo time/location |
| `weather_desc` | String | Human-readable weather description |
| `filename` | String | Original filename |
| `filesize` | long | File size in bytes |
| `width` | int | Image width in pixels |
| `height` | int | Image height in pixels |
| `gps_flag` | boolean | `true` if EXIF GPS coordinates were extracted |
| `latitude` | Double | Decimal latitude (null if no GPS) |
| `longitude` | Double | Decimal longitude (null if no GPS) |
| `altitude` | Double | Altitude in meters (null if no GPS) |
| `datetime` | String | EXIF DateTimeOriginal string |
| `elk_count` | Integer | AnimalDetect elk count (null if not yet processed) |
| `processed_status` | boolean | `true` if AnimalDetect has run successfully |

---

## SecretConfig.java

Centralized secret resolution. Checks environment variables first, then falls back to a JSON file at the path given by `APP_SECRETS_PATH`. All other classes call this to retrieve credentials and configuration.

---

### `get(String key)`

| Field | Detail |
|---|---|
| **Inputs** | `key` (String) — secret name to look up |
| **Outputs** | `String` — trimmed secret value, or `null` if not found in either source |
| **Functionality** | Returns the secret value by checking environment variables first, then the JSON secrets file; returns `null` if the key is absent or blank in both. |
| **Dependencies** | `loadFileSecrets`, `normalize` |
| **Called by** | `getRequired`, `getOrDefault`, and all callers throughout the application that need optional secrets |

---

### `getOrDefault(String key, String defaultValue)`

| Field | Detail |
|---|---|
| **Inputs** | `key` (String) — secret name to look up; `defaultValue` (String) — fallback value |
| **Outputs** | `String` — the resolved secret value if present, otherwise `defaultValue` |
| **Functionality** | Wraps `get(key)` with a caller-supplied default for optional secrets that have a sensible fallback. |
| **Dependencies** | `get` |
| **Called by** | Any caller that needs a secret with a fallback (currently unused in production paths) |

---

### `getRequired(String key)`

| Field | Detail |
|---|---|
| **Inputs** | `key` (String) — secret name to look up |
| **Outputs** | `String` — trimmed non-blank secret value |
| **Functionality** | Delegates to `get(key)` and throws `IllegalStateException` if the value is null, enforcing that required secrets are present before the application proceeds. |
| **Dependencies** | `get` |
| **Called by** | `Config` (all fields), `db.connect`, `GoogleCloudStorageAPI.buildStorage`, `FileProcessor.downloadFromCloudUri`, `EmailProcessor.buildGmailService`, `MessagingController.sendGridEmailWebhook`, `TaskController.runEmailPollingTask`, and all other callers that require a mandatory secret |

---

### `loadFileSecrets()` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | None (reads `APP_SECRETS_PATH` environment variable) |
| **Outputs** | `Map<String, String>` — key-value pairs loaded from the JSON file, or an empty map if `APP_SECRETS_PATH` is unset or the file does not exist |
| **Functionality** | Reads the JSON secrets file at the path given by `APP_SECRETS_PATH`, converts all values to strings, and returns a normalized map; logs a warning on parse failure. |
| **Dependencies** | `com.fasterxml.jackson.databind.ObjectMapper`, `java.nio.file.Files`, `java.nio.file.Path` |
| **Called by** | Static initializer (class load time) |

---

### `normalize(String value)` *(private)*

| Field | Detail |
|---|---|
| **Inputs** | `value` (String) — raw string value (may be null) |
| **Outputs** | `String` — trimmed value, or `null` if the input is null or blank after trimming |
| **Functionality** | Trims whitespace and converts blank strings to null to ensure consistent absent-value semantics throughout `SecretConfig` lookups. |
| **Dependencies** | None |
| **Called by** | `get`, `loadFileSecrets` |

---

## TaskController.java

Spring REST controller that manages scheduled job triggers. Runs one email poll immediately on startup and exposes a token-protected HTTP endpoint for external schedulers.

---

### `pollOnStartup()`

| Field | Detail |
|---|---|
| **Inputs** | None (triggered automatically by Spring after the application context is ready) |
| **Outputs** | void — runs one email polling pass on startup; logs errors but does not rethrow |
| **Functionality** | Listens for the `ApplicationReadyEvent` and immediately triggers the Gmail polling job so any emails received while the server was down are processed on boot. |
| **Dependencies** | `EventScheduler.runEmailPollingJob`, `org.springframework.boot.context.event.ApplicationReadyEvent` |
| **Called by** | Spring framework after `ApplicationReadyEvent` is published |

---

### `runEmailPollingTask(String taskTokenHeader)`

| Field | Detail |
|---|---|
| **Inputs** | `taskTokenHeader` (String, optional header `X-Task-Token`) — shared secret to authenticate the caller |
| **Outputs** | `ResponseEntity<?>` — 200 OK `{"status":"ok","job":"poll-email"}` on success; 503 if `TASK_TOKEN` is not configured; 401 if the token does not match |
| **Functionality** | HTTP `POST /internal/tasks/poll-email` handler that allows an external scheduler (e.g. Google Cloud Scheduler) to trigger the Gmail polling job; protected by a shared-secret token check. |
| **Dependencies** | `SecretConfig`, `EventScheduler.runEmailPollingJob`, `org.springframework.http.ResponseEntity` |
| **Called by** | External HTTP scheduler (e.g. Cloud Scheduler cron job) via `POST /internal/tasks/poll-email` |

---

## WebConfig.java

Spring MVC CORS configuration. Allows the frontend (local dev and Firebase production) to make cross-origin requests to `/api/**`.

---

### `addCorsMappings(CorsRegistry registry)`

| Field | Detail |
|---|---|
| **Inputs** | `registry` (CorsRegistry) — Spring MVC CORS registry to configure |
| **Outputs** | void — registers CORS rules for all `/api/**` endpoints |
| **Functionality** | Allows cross-origin requests to `/api/**` from the local dev servers and the two Firebase-hosted production origins, permitting standard HTTP methods and all headers. |
| **Dependencies** | `org.springframework.web.servlet.config.annotation.CorsRegistry`, `org.springframework.web.servlet.config.annotation.WebMvcConfigurer` |
| **Called by** | Spring MVC framework during application context initialization |

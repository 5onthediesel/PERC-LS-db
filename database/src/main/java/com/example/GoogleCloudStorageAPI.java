package com.example;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GoogleCloudStorageAPI {

    private static final String PROJECT_ID = SecretConfig.getRequired("GCS_PROJECT_ID");
    private static final String BUCKET_NAME = SecretConfig.getRequired("GCS_BUCKET_NAME");

    /**
     * Inputs:      None (reads GCS_PROJECT_ID and GCS_CREDENTIALS_PATH from SecretConfig)
     * Outputs:     Storage — authenticated Google Cloud Storage client
     * Functionality: Builds a GCS Storage client, loading service-account credentials from the path
     *               in SecretConfig if the file exists, otherwise falling back to application-default credentials.
     * Dependencies: com.google.cloud.storage.StorageOptions, com.google.auth.oauth2.GoogleCredentials,
     *               SecretConfig, java.nio.file.Files
     * Called by:   uploadFile(MultipartFile), uploadFile(String, String), downloadFile
     */
    private static Storage buildStorage() throws IOException {
        StorageOptions.Builder builder = StorageOptions.newBuilder().setProjectId(PROJECT_ID);

        String credentialsPath = SecretConfig.getRequired("GCS_CREDENTIALS_PATH");

        if (credentialsPath != null && Files.exists(Path.of(credentialsPath))) {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(new FileInputStream(credentialsPath))
                    .createScoped("https://www.googleapis.com/auth/cloud-platform");
            builder.setCredentials(credentials);
        }

        return builder.build().getService();
    }

    /**
     * Inputs:      args (String[]) — unused
     * Outputs:     void — uploads a hardcoded test image and downloads it to a local path
     * Functionality: Manual smoke-test entry point for verifying GCS upload/download connectivity.
     * Dependencies: uploadFile(String, String), downloadFile
     * Called by:   JVM when run directly for testing
     */
    public static void main(String[] args) throws IOException {
        uploadFile("src/main/java/com/example/images/IMG_3141.jpg", "IMG_3141.jpg");
        downloadFile("IMG_3141.jpg", "src/main/java/com/example/images/downloaded_IMG_3141.jpg");
    }

    /**
     * Inputs:      file (MultipartFile) — image file from a multipart HTTP request
     * Outputs:     String — GCS object name (SHA-256 hash of the file bytes)
     * Functionality: Hashes the file bytes to derive a unique object name, then uploads the file
     *               to the configured GCS bucket under that name.
     * Dependencies: buildStorage, ImageUtils.sha256, com.google.cloud.storage.Storage,
     *               com.google.cloud.storage.BlobId, com.google.cloud.storage.BlobInfo
     * Called by:   Not currently called from production paths (superseded by uploadFile(String, String));
     *              available for direct multipart upload use cases
     */
    public static String uploadFile(MultipartFile file) throws IOException, java.security.NoSuchAlgorithmException {
        String objectName = ImageUtils.sha256(file.getBytes());
        Storage storage = buildStorage();

        BlobId blobId = BlobId.of(BUCKET_NAME, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

        storage.create(blobInfo, file.getBytes());

        System.out.println("File " + file.getOriginalFilename() + " uploaded to bucket " + BUCKET_NAME + " as "
                + blobId.getName());
        return objectName;
    }

    /**
     * Inputs:      localFilePath (String) — absolute or relative path to the file on disk;
     *              objectName (String) — GCS object key to store the file under
     * Outputs:     void — uploads the file to the configured GCS bucket
     * Functionality: Streams a local file directly to GCS using Storage.createFrom, avoiding
     *               loading the entire file into heap memory.
     * Dependencies: buildStorage, com.google.cloud.storage.Storage,
     *               com.google.cloud.storage.BlobId, com.google.cloud.storage.BlobInfo
     * Called by:   FileProcessor.uploadAndProcessFiles, EmailProcessor.pollAndProcess,
     *              MessagingController.smsWebhook, MessagingController.sendGridEmailWebhook
     */
    public static void uploadFile(String localFilePath, String objectName) throws IOException {
        Storage storage = buildStorage();

        BlobId blobId = BlobId.of(BUCKET_NAME, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

        storage.createFrom(blobInfo, Paths.get(localFilePath));

        System.out.println("File " + localFilePath + " uploaded to bucket " + BUCKET_NAME + " as " + blobId.getName());
    }

    /**
     * Inputs:      objectName (String) — GCS object key to download;
     *              destinationPath (String) — local file path to write the downloaded bytes to
     * Outputs:     void — writes the GCS object contents to the specified local path
     * Functionality: Downloads a blob from the configured GCS bucket to a local file path.
     * Dependencies: buildStorage, com.google.cloud.storage.Storage,
     *               com.google.cloud.storage.BlobId
     * Called by:   GoogleCloudStorageAPI.main (manual testing)
     */
    public static void downloadFile(String objectName, String destinationPath) throws IOException {
        Storage storage = buildStorage();

        BlobId blobId = BlobId.of(BUCKET_NAME, objectName);
        storage.get(blobId).downloadTo(Paths.get(destinationPath));

        System.out.println("File " + objectName + " downloaded from bucket " + BUCKET_NAME + " to " + destinationPath);
    }
}

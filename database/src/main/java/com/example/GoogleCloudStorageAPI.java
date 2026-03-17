package com.example;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GoogleCloudStorageAPI {

    private static final String PROJECT_ID = "cs370perc";
    private static final String BUCKET_NAME = "cs370perc-bucket";
    private static final String CREDENTIALS_PATH = "/secrets/gcs/cs370perc.key.json";

    private static Storage buildStorage() throws IOException {
        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(CREDENTIALS_PATH))
                .createScoped("https://www.googleapis.com/auth/cloud-platform");
        return StorageOptions.newBuilder()
                .setProjectId(PROJECT_ID)
                .setCredentials(credentials)
                .build()
                .getService();
    }

    public static void main(String[] args) throws IOException {
        uploadFile("src/main/java/com/example/images/IMG_3141.jpg", "IMG_3141.jpg");
        downloadFile("IMG_3141.jpg", "src/main/java/com/example/images/downloaded_IMG_3141.jpg");
    }

    public static String uploadFile(MultipartFile file) throws IOException, java.security.NoSuchAlgorithmException {
        String objectName = ImgHash.sha256(file.getBytes());
        Storage storage = buildStorage();

        BlobId blobId = BlobId.of(BUCKET_NAME, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

        storage.create(blobInfo, file.getBytes());

        System.out.println("File " + file.getOriginalFilename() + " uploaded to bucket " + BUCKET_NAME + " as "
                + blobId.getName());
        return objectName;
    }

    public static void uploadFile(String localFilePath, String objectName) throws IOException {
        Storage storage = buildStorage();

        BlobId blobId = BlobId.of(BUCKET_NAME, objectName);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId).build();

        storage.createFrom(blobInfo, Paths.get(localFilePath));

        System.out.println("File " + localFilePath + " uploaded to bucket " + BUCKET_NAME + " as " + blobId.getName());
    }

    public static void downloadFile(String objectName, String destinationPath) throws IOException {
        Storage storage = buildStorage();

        BlobId blobId = BlobId.of(BUCKET_NAME, objectName);
        storage.get(blobId).downloadTo(Paths.get(destinationPath));

        System.out.println("File " + objectName + " downloaded from bucket " + BUCKET_NAME + " to " + destinationPath);
    }
}

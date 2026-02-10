package com.example;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// mvn-Dtest=PipelineIntegrationTest test-Dsurefire.useFile=false
// CLEANUP=1 mvn -Dtest=PipelineIntegrationTest test -Dsurefire.useFile=false
public class PipelineIntegrationTest {

    private static final String PROJECT_ID = "cs370perc";
    private static final String BUCKET_NAME = "cs370perc-bucket";

    private static final Path TEST_DIR = Paths.get("src", "test", "java", "com", "example");
    private static final Path ORIGINAL_HEIC = TEST_DIR.resolve("test_image.heic");

    @Test
    void endToEnd_withSpecificHeic_copyInTestDir_uploadAsHashDotJpg_insertToDb_andVerify() throws Exception {
        System.out.println("=== BEGIN PipelineIntegrationTest ===");
        System.out.println("Project root (user.dir): " + System.getProperty("user.dir"));
        System.out.println("Original HEIC path: " + ORIGINAL_HEIC.toAbsolutePath());

        assertTrue(Files.exists(ORIGINAL_HEIC) && Files.isRegularFile(ORIGINAL_HEIC),
                "Missing test image at: " + ORIGINAL_HEIC.toAbsolutePath());

        Files.createDirectories(TEST_DIR);
        String tmpHeicName = "test_image_copy_" + UUID.randomUUID() + ".heic";
        Path tmpHeic = TEST_DIR.resolve(tmpHeicName);

        System.out.println("[STEP 1] Copy original -> temp: " + tmpHeic.toAbsolutePath());
        Files.copy(ORIGINAL_HEIC, tmpHeic, StandardCopyOption.REPLACE_EXISTING);

        File heicCopy = tmpHeic.toFile();
        File jpg = null;

        Metadata meta = null;
        Storage storage = null;
        BlobId blobId = null;
        String gsUri = null;

        try {
            System.out.println("[STEP 2] Convert HEIC copy -> JPG...");
            jpg = ImgDet.convertToJpg(heicCopy);
            assertNotNull(jpg, "convertToJpg returned null");
            assertTrue(jpg.exists() && jpg.isFile(), "JPG conversion output missing: " + jpg.getAbsolutePath());
            System.out.println("Converted JPG: " + jpg.getAbsolutePath());

            System.out.println("[STEP 3] Compute hash + metadata...");
            meta = db.loadMetadata(jpg);
            assertNotNull(meta, "db.loadMetadata returned null");
            assertNotNull(meta.sha256, "Metadata.sha256 is null");
            assertEquals(64, meta.sha256.length(), "SHA-256 hex should be 64 chars");
            System.out.println("sha256: " + meta.sha256);

            String objectName = meta.sha256 + ".jpg";
            gsUri = "gs://" + BUCKET_NAME + "/" + objectName;
            meta.cloud_uri = gsUri;

            System.out.println("[INFO] Upload objectName: " + objectName);
            System.out.println("[INFO] Final cloud URI: " + gsUri);

            System.out.println("[STEP 4] Connect to Postgres...");
            try (Connection conn = db.connect()) {
                System.out.println("[OK] Connected to Postgres.");

                ensureSchemaAndTable(conn);

                System.out.println("[STEP 5] Connect to GCS...");
                storage = StorageOptions.newBuilder().setProjectId(PROJECT_ID).build().getService();
                storage.list(BUCKET_NAME, Storage.BlobListOption.pageSize(1));
                System.out.println("[OK] GCS access ok.");

                blobId = BlobId.of(BUCKET_NAME, objectName);

                System.out.println("[STEP 6] Pre-clean (best-effort)...");
                try {
                    deleteRow(conn, meta.sha256);
                } catch (Exception ignored) {
                }
                try {
                    storage.delete(blobId);
                } catch (Exception ignored) {
                }

                System.out.println("[STEP 7] Upload to GCS...");
                GoogleCloudStorageAPI.uploadFile(jpg.getAbsolutePath(), objectName);
                System.out.println("[OK] Uploaded to: " + gsUri);

                System.out.println("[STEP 8] Verify object exists...");
                Blob blob = storage.get(blobId);
                assertNotNull(blob, "Expected GCS object to exist after upload: " + gsUri);

                System.out.println("[STEP 9] Insert into DB...");
                db.insertMeta(conn, meta);

                System.out.println("[STEP 10] Verify DB record...");
                assertTrue(rowExists(conn, meta.sha256), "Expected DB row for hash=" + meta.sha256);
                assertEquals(gsUri, fetchCloudUri(conn, meta.sha256), "DB cloud_uri mismatch");

                System.out.println("=== PASS ===");
                System.out.println("FINAL_URI=" + gsUri);

                if ("1".equals(System.getenv("CLEANUP"))) {
                    System.out.println("[CLEANUP] CLEANUP=1 set, deleting DB row + GCS object...");
                    deleteRow(conn, meta.sha256);
                    storage.delete(blobId);
                } else {
                    System.out.println("[INFO] Keeping DB row + GCS object (set CLEANUP=1 to delete).");
                }
            }
        } finally {
            System.out.println("[LOCAL CLEANUP] Deleting temp HEIC copy and generated JPG (best-effort)...");
            try {
                Files.deleteIfExists(tmpHeic);
            } catch (Exception ignored) {
            }
            try {
                if (jpg != null)
                    Files.deleteIfExists(jpg.toPath());
            } catch (Exception ignored) {
            }
        }
    }

    /* ----------------------- helpers ----------------------- */

    private static void ensureSchemaAndTable(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("create schema if not exists cs370");
            s.execute("set search_path to cs370");

            String ddl = "create table if not exists images ("
                    + "id serial primary key, "
                    + "img_hash varchar(64) unique, "
                    + "cloud_uri text not null, "
                    + "filename text, "
                    + "filesize_bytes bigint, "
                    + "width int, "
                    + "height int, "
                    + "gps_flag boolean, "
                    + "latitude double precision, "
                    + "longitude double precision, "
                    + "altitude double precision, "
                    + "datetime_taken timestamptz, "
                    + "datetime_uploaded timestamptz default now()"
                    + ")";
            s.execute(ddl);
        }
    }

    private static boolean rowExists(Connection conn, String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "select 1 from cs370.images where img_hash = ? limit 1")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String fetchCloudUri(Connection conn, String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "select cloud_uri from cs370.images where img_hash = ? limit 1")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static int deleteRow(Connection conn, String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "delete from cs370.images where img_hash = ?")) {
            ps.setString(1, hash);
            return ps.executeUpdate();
        }
    }
}

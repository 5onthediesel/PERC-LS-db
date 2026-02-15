package com.example;

import org.junit.jupiter.api.*;

import java.io.File;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ImageQueryMethodsTest {

    // ---- Test images (as you specified) ----
    private static final String IMG_3141 = "src/main/java/com/example/images/IMG_3141.jpg";
    private static final String IMG_3142 = "src/main/java/com/example/images/IMG_3142.jpg";
    private static final String IMG_3143 = "src/main/java/com/example/images/IMG_3143.jpg";
    private static final String IMG_5585 = "src/main/java/com/example/images/IMG_5585.jpg";
    private static final String IMG_7804 = "src/main/java/com/example/images/IMG_7804.jpg";

    private static final List<String> IMAGE_PATHS = Arrays.asList(
            IMG_3141, IMG_3142, IMG_3143, IMG_5585, IMG_7804);

    private static final String START_DATE = "2026-02-07";
    private static final String END_DATE = "2026-02-11";

    private static final double RADIUS_SMALL_KM = 0.2;
    private static final double RADIUS_LARGE_KM = 50.0;

    private Connection conn;
    private Metadata meta3141FromFile;

    @BeforeAll
    void setupDbAndLoadImages() throws Exception {
        System.out.println("=== BEGIN ImageQueryMethodsTest setup ===");
        System.out.println("Using date range: " + START_DATE + " -> " + END_DATE);
        System.out.println("Radii: small=" + RADIUS_SMALL_KM + "km, large=" + RADIUS_LARGE_KM + "km");

        for (String p : IMAGE_PATHS) {
            File f = new File(p);
            assertTrue(f.exists() && f.isFile(),
                    "Missing test image at: " + f.getAbsolutePath());
        }

        conn = db.connect();
        db.setupSchema(conn);

        for (String p : IMAGE_PATHS) {
            File f = new File(p);

            Metadata meta = db.loadMetadata(f);

            // For presentation/debugging
            System.out.println("[INSERT] " + meta.filename
                    + " sha=" + meta.sha256
                    + " gps=" + meta.gps_flag
                    + " lat=" + meta.latitude
                    + " lon=" + meta.longitude
                    + " datetime=" + meta.datetime);

            if (meta.cloud_uri == null)
                meta.cloud_uri = "";
            if (meta.cloud_uri.isEmpty())
                meta.cloud_uri = "gs://dummy/" + meta.sha256 + ".jpg";

            db.insertMeta(conn, meta);

            if (p.equals(IMG_3141)) {
                meta3141FromFile = meta;
            }
        }

        assertNotNull(meta3141FromFile, "Could not load metadata for IMG-3141.jpg");
        System.out.println("=== END setup ===");
    }

    @AfterAll
    void closeDb() throws Exception {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    void test_getImagesByDateRange_dateRangeSettable() throws Exception {
        System.out.println("=== test_getImagesByDateRange_dateRangeSettable ===");

        List<Metadata> results = db.getImagesByDateRange(conn, START_DATE, END_DATE);

        System.out.println("Returned rows: " + results.size());
        for (Metadata m : results) {
            System.out.println("  - " + m.filename + " datetime_taken=" + m.datetime);
        }

        assertTrue(results.size() == 4,
                "getImagesByDateRange should return exactly 1 image in the specified date range, but got "
                        + results.size());

        Set<String> expectedFilenames = Set.of("IMG_3141.jpg", "IMG_3142.jpg", "IMG_3143.jpg", "IMG_7804.jpg");
        for (Metadata m : results) {
            assertTrue(expectedFilenames.contains(m.filename),
                    "Expected filename to be one of " + expectedFilenames + ", but got " + m.filename);
        }
    }

    @Test
    void test_getImagesByLocation_relativeToIMG3141() throws Exception {
        System.out.println("=== test_getImagesByLocation_relativeToIMG3141 ===");

        double centerLat = meta3141FromFile.latitude;
        double centerLon = meta3141FromFile.longitude;

        List<Metadata> nearby_small = db.getImagesByLocation(conn, centerLat, centerLon, RADIUS_SMALL_KM);

        System.out.println("Center (from IMG-3141): lat=" + centerLat + " lon=" + centerLon);
        System.out.println("Nearby Small Radius (<= " + RADIUS_SMALL_KM + " km): " + nearby_small.size());
        for (Metadata m : nearby_small) {
            System.out.println("  - " + m.filename + " lat=" + m.latitude + " lon=" + m.longitude);
        }

        List<Metadata> nearby_large = db.getImagesByLocation(conn, centerLat, centerLon, RADIUS_LARGE_KM);

        System.out.println("Center (from IMG-3141): lat=" + centerLat + " lon=" + centerLon);
        System.out.println("Nearby Large Radius (<= " + RADIUS_LARGE_KM + " km): " + nearby_large.size());
        for (Metadata m : nearby_large) {
            System.out.println("  - " + m.filename + " lat=" + m.latitude + " lon=" + m.longitude);
        }

        Set<String> small_names = Set.of("IMG_3141.jpg", "IMG_3142.jpg", "IMG_3143.jpg");
        assertTrue(nearby_small.size() == 3,
                "Expected 3 images within " + RADIUS_SMALL_KM + " km of IMG-3141.jpg, but got " + nearby_small.size());
        for (Metadata m : nearby_small) {
            assertTrue(small_names.contains(m.filename),
                    "Expected nearby image to be one of " + small_names + ", but got " + m.filename);
        }

        Set<String> large_names = Set.of("IMG_3141.jpg", "IMG_3142.jpg", "IMG_3143.jpg", "IMG_5585.jpg");
        assertTrue(nearby_large.size() == 4,
                "Expected 4 images within " + RADIUS_LARGE_KM + " km of IMG-3141.jpg, but got " + nearby_large.size());
        for (Metadata m : nearby_large) {
            assertTrue(large_names.contains(m.filename),
                    "Expected nearby image to be one of " + large_names + ", but got " + m.filename);
        }
    }
}

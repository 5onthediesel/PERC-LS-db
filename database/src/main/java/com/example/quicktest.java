package com.example;

import java.io.File;
import java.sql.*;

public class quicktest {
    private static final String BUCKET_NAME = "cs370perc-bucket";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GcsDbQuickTest <path/to/image>");
            System.exit(2);
        }

        File input = new File(args[0]);
        if (!input.exists() || !input.isFile()) {
            throw new IllegalArgumentException("Not a file: " + input.getAbsolutePath());
        }

        // 1) Convert to jpg if needed
        File jpg = ImgDet.convertToJpg(input);

        // 2) Load metadata + compute SHA256 (db.loadMetadata already does sha256)
        Metadata meta = db.loadMetadata(jpg);

        // 3) Check DB first (skip upload/insert if already present)
        try (Connection conn = db.connect()) {
            ensureSchema(conn);

            String existingUri = lookupCloudUriByHash(conn, meta.sha256);
            if (existingUri != null) {
                System.out.println("Already ingested. hash=" + meta.sha256);
                System.out.println("cloud_uri=" + existingUri);
                return;
            }

            // 4) Upload to GCS as <hash>.jpg
            String objectName = meta.sha256 + ".jpg";
            GoogleCloudStorageAPI.uploadFile(jpg.getAbsolutePath(), objectName);

            // 5) Insert metadata + cloud_uri
            meta.cloud_uri = "gs://" + BUCKET_NAME + "/" + objectName;
            try {
                db.insertMeta(conn, meta);
            } catch (SQLException e) {
                // If you race/duplicate, show a clean message
                if ("23505".equals(e.getSQLState())) {
                    System.out.println("Duplicate hash skipped: " + meta.sha256);
                } else {
                    throw e;
                }
            }

            System.out.println("✅ Ingest complete");
            System.out.println("hash=" + meta.sha256);
            System.out.println("cloud_uri=" + meta.cloud_uri);
            System.out.println("filename=" + meta.filename);
        }
    }

    /** Like db.setupSchema, but doesn't DROP the table. */
    private static void ensureSchema(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("create schema if not exists cs370");
            s.execute("set search_path to cs370");

            s.execute("""
                        create table if not exists images (
                            id serial primary key,
                            img_hash varchar(64) unique,
                            cloud_uri text not null,

                            filename text,
                            filesize_bytes bigint,
                            width int,
                            height int,

                            gps_flag boolean,
                            latitude double precision,
                            longitude double precision,
                            altitude double precision,
                            datetime_taken timestamptz,
                            datetime_uploaded timestamptz default now()
                        )
                    """);
        }
    }

    private static String lookupCloudUriByHash(Connection conn, String hash) throws SQLException {
        String sql = "select cloud_uri from images where img_hash = ? limit 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}

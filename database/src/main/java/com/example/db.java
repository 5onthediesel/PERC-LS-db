package com.example;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

class db {
    private static final Logger logger = Logger.getLogger(db.class.getName());

    /**
     * Inputs:      None
     * Outputs:     void
     * Functionality: Opens a short-lived connection and calls setupSchema to create or migrate
     *               the images table; intended for use only at application startup.
     * Dependencies: connect(), setupSchema(Connection, boolean)
     * Called by:   App.initializeSchemaOnStartup (via CommandLineRunner bean)
     */
    static void initializeSchemaAtStartup() throws SQLException {
        try (Connection conn = connect()) {
            setupSchema(conn, false);
        }
    }

    /**
     * Inputs:      conn (Connection) — active database connection
     * Outputs:     void
     * Functionality: Delegates to setupSchema(conn, true), which drops and recreates the images table.
     * Dependencies: setupSchema(Connection, boolean)
     * Called by:   Test code and manual tooling that needs a fresh schema
     */
    static void setupSchema(Connection conn) throws SQLException {
        setupSchema(conn, true);
    }

    /**
     * Inputs:      conn (Connection) — active database connection;
     *              resetTable (boolean) — if true, drops the images table before recreating it
     * Outputs:     void
     * Functionality: Creates the postgres schema and images table if they do not exist, then
     *               runs ALTER TABLE ADD COLUMN IF NOT EXISTS statements to apply any new columns.
     * Dependencies: java.sql.Statement, java.sql.Connection
     * Called by:   initializeSchemaAtStartup, setupSchema(Connection)
     */
    static void setupSchema(Connection conn, boolean resetTable) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("create schema if not exists postgres");
            s.execute("set search_path to postgres");

            if (resetTable) {
                s.execute("drop table if exists images");
            }

            s.execute("create table if not exists images ("
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
                    + "datetime_uploaded timestamptz default now(), "
                    + "temperature_c double precision, "
                    + "humidity double precision, "
                    + "weather_desc text, "
                    + "processed_status boolean"
                    + ")");
            s.execute("alter table images add column if not exists filename text");
            s.execute("alter table images add column if not exists filesize_bytes bigint");
            s.execute("alter table images add column if not exists width int");
            s.execute("alter table images add column if not exists height int");
            s.execute("alter table images add column if not exists gps_flag boolean");
            s.execute("alter table images add column if not exists latitude double precision");
            s.execute("alter table images add column if not exists longitude double precision");
            s.execute("alter table images add column if not exists altitude double precision");
            s.execute("alter table images add column if not exists datetime_taken timestamptz");
            s.execute("alter table images add column if not exists datetime_uploaded timestamptz default now()");
            s.execute("alter table images add column if not exists temperature_c double precision");
            s.execute("alter table images add column if not exists humidity double precision");
            s.execute("alter table images add column if not exists weather_desc text");
            s.execute("alter table images add column if not exists elk_count integer");
            s.execute("alter table images add column if not exists processed_status boolean default false");
        }
    }

    /**
     * Inputs:      None
     * Outputs:     Connection — an open JDBC connection to the Cloud SQL PostgreSQL instance
     * Functionality: Reads all connection parameters from SecretConfig and establishes a connection
     *               via the Cloud SQL socket factory.
     * Dependencies: SecretConfig, java.sql.DriverManager, com.google.cloud.sql.postgres.SocketFactory
     * Called by:   initializeSchemaAtStartup, FileProcessor.uploadAndProcessFiles,
     *              FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              FileProcessor.processAllUnprocessedWithPythonInference,
     *              EmailProcessor.pollAndProcess, MessagingController.smsWebhook,
     *              MessagingController.sendGridEmailWebhook,
     *              ImageStatsController.getImagesSummary, ImageStatsController.getImageLocations
     */
    static Connection connect() throws SQLException {
        String instanceConnectionName = SecretConfig.getRequired("CLOUD_SQL_INSTANCE");
        String dbName = SecretConfig.getRequired("CLOUD_SQL_DB_NAME");
        String url = "jdbc:postgresql:///" + dbName;

        Properties props = new Properties();
        props.setProperty("user", SecretConfig.getRequired("DB_USER"));

        String dbPassword = SecretConfig.get("DB_PASSWORD");
        if (dbPassword == null) {
            throw new SQLException(
                    "Missing DB_PASSWORD secret. Provide it via environment variables or APP_SECRETS_PATH JSON.");
        }
        props.setProperty("password", dbPassword);

        props.setProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");
        props.setProperty("cloudSqlInstance", instanceConnectionName);
        props.setProperty("cloudSqlAdminQuotaProject", SecretConfig.getRequired("CLOUD_SQL_QUOTA_PROJECT"));

        String credentialsPath = SecretConfig.getRequired("CLOUD_SQL_CREDENTIALS_PATH");
        if (credentialsPath != null && !credentialsPath.isEmpty()) {
            props.setProperty("cloudSqlGoogleCredentialsPath", credentialsPath);
        }

        try {
            return DriverManager.getConnection(url, props);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to connect to Cloud SQL: " + e.getMessage(), e);
            throw e; // re-throw so the calling endpoint can return a proper error response
        }
    }

    /**
     * Inputs:      f (File) — image file on disk
     * Outputs:     Metadata — populated metadata object (EXIF, GPS, weather, hash, dimensions)
     * Functionality: Delegates to loadMetadata(f, false), meaning EXIF parse failures are not suppressed.
     * Dependencies: loadMetadata(File, boolean)
     * Called by:   EmailProcessor.pollAndProcess, MessagingController.smsWebhook,
     *              MessagingController.sendGridEmailWebhook, ImageUtils.main
     */
    static Metadata loadMetadata(File f) throws Exception {
        return loadMetadata(f, false);
    }

    /**
     * Inputs:      f (File) — image file on disk;
     *              assumeExifParsable (boolean) — if true, silently ignores EXIF parse failures
     * Outputs:     Metadata — fully populated Metadata object including hash, dimensions, GPS, and weather
     * Functionality: Parses EXIF/GPS data from the file, computes the SHA-256 hash, reads image dimensions,
     *               and fetches historical weather data for the GPS location and photo timestamp.
     * Dependencies: ImageUtils.parse, ImageUtils.parseExifFromPng, ImageUtils.sha256,
     *               ImageUtils.getWidth, ImageUtils.getHeight, populateWeather
     * Called by:   loadMetadata(File), EmailProcessor.pollAndProcess, MessagingController.smsWebhook,
     *              MessagingController.sendGridEmailWebhook, ImageUtils.main
     */
    static Metadata loadMetadata(File f, boolean assumeExifParsable) throws Exception {
        Metadata meta = new Metadata();
        ImageUtils.ExifData d;
        String ext = ImageUtils.getExtension(f.getName()).toLowerCase();
        if (ext.equals("png")) {
            d = ImageUtils.parseExifFromPng(f);
            if (d == null)
                d = new ImageUtils.ExifData();
        } else {
            try {
                d = ImageUtils.parse(f.getAbsolutePath());
            } catch (Exception e) {
                if (!assumeExifParsable) {
                    throw e;
                }
                logger.log(Level.FINE, "Ignoring EXIF parse failure for " + f.getName() + ": " + e.getMessage());
                d = new ImageUtils.ExifData();
            }
        }

        meta.filename = f.getName();
        meta.filesize = f.length();
        meta.datetime = d.date;

        if (d.lat != null && d.lon != null) {
            meta.latitude = d.lat;
            meta.longitude = d.lon;
            meta.altitude = d.alt;
            meta.gps_flag = true;
        } else {
            meta.latitude = null;
            meta.longitude = null;
            meta.altitude = null;
            meta.gps_flag = false;
        }

        meta.sha256 = ImageUtils.sha256(f);
        meta.width = ImageUtils.getWidth(f);
        meta.height = ImageUtils.getHeight(f);
        meta.cloud_uri = "";
        populateWeather(meta);
        meta.processed_status = true;

        return meta;
    }

    /**
     * Inputs:      code (int) — WMO weather interpretation code
     * Outputs:     String — human-readable weather description (e.g. "Clear sky", "Rain")
     * Functionality: Maps Open-Meteo WMO weather codes to descriptive strings; returns "Unknown" for unrecognized codes.
     * Dependencies: None
     * Called by:   populateWeather
     */
    private static String weatherCodeToString(int code) {
        switch (code) {
            case 0:
                return "Clear sky";
            case 1:
            case 2:
            case 3:
                return "Partly cloudy";
            case 45:
            case 48:
                return "Fog";
            case 51:
            case 53:
            case 55:
                return "Drizzle";
            case 61:
            case 63:
            case 65:
                return "Rain";
            case 66:
            case 67:
                return "Freezing rain";
            case 71:
            case 73:
            case 75:
                return "Snow";
            case 77:
                return "Snow grains";
            case 80:
            case 81:
            case 82:
                return "Rain showers";
            case 85:
            case 86:
                return "Snow showers";
            case 95:
            case 96:
            case 99:
                return "Thunderstorm";
            default:
                return "Unknown";
        }
    }

    /**
     * Inputs:      meta (Metadata) — partially populated metadata with latitude, longitude, and datetime fields
     * Outputs:     void — sets meta.temperature_c, meta.humidity, and meta.weather_desc in place
     * Functionality: Calls the Open-Meteo archive API to fetch hourly temperature, humidity, and weather
     *               code for the photo's GPS location and timestamp; silently ignores any errors.
     * Dependencies: java.net.http.HttpClient, java.net.http.HttpRequest/HttpResponse,
     *               java.time.LocalDateTime, weatherCodeToString
     * Called by:   loadMetadata(File, boolean)
     */
    public static void populateWeather(Metadata meta) {
        try {
            if (meta == null || meta.latitude == null || meta.longitude == null || meta.datetime == null)
                return;

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss");

            LocalDateTime photoTime = LocalDateTime.parse(meta.datetime, fmt);

            String date = photoTime.toLocalDate().toString();
            int hour = photoTime.getHour();

            String url = "https://archive-api.open-meteo.com/v1/archive"
                    + "?latitude=" + meta.latitude
                    + "&longitude=" + meta.longitude
                    + "&start_date=" + date
                    + "&end_date=" + date
                    + "&hourly=temperature_2m,relative_humidity_2m,weathercode"
                    + "&timezone=auto";

            HttpClient client = HttpClient.newHttpClient();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200)
                return;

            String body = response.body();

            // VERY simple parsing (safe for this API format)

            String temps = body.split("\"temperature_2m\":\\[")[1].split("\\]")[0];
            String hums = body.split("\"relative_humidity_2m\":\\[")[1].split("\\]")[0];
            String wcodes = body.split("\"weathercode\":\\[")[1].split("\\]")[0];
            String codeStr = wcodes.split(",")[hour].trim();
            int weathercode = Integer.parseInt(codeStr);

            String tempStr = temps.split(",")[hour].trim();
            String humStr = hums.split(",")[hour].trim();

            meta.temperature_c = Double.parseDouble(tempStr);
            meta.humidity = Double.parseDouble(humStr);
            meta.weather_desc = weatherCodeToString(weathercode);

        } catch (Exception ignored) {
            // Never crash metadata loading
        }
    }

    /**
     * Inputs:      conn (Connection) — active database connection; meta (Metadata) — fully populated metadata object
     * Outputs:     void — inserts one row into postgres.images
     * Functionality: Inserts all metadata fields for a newly uploaded image, including GPS, weather, and elk count.
     * Dependencies: java.sql.PreparedStatement, java.sql.Types
     * Called by:   FileProcessor.uploadAndProcessFiles, EmailProcessor.pollAndProcess,
     *              MessagingController.smsWebhook, MessagingController.sendGridEmailWebhook
     */
    static void insertMeta(Connection conn, Metadata meta) throws SQLException {
        String sql = "insert into postgres.images (" +
                "img_hash, filename, gps_flag, latitude, longitude, altitude, datetime_taken, " +
                "cloud_uri, width, height, filesize_bytes, temperature_c, humidity, weather_desc, elk_count, processed_status) "
                +
                "values (?, ?, ?, ?, ?, ?, to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS'), " +
                "?, ?, ?, ?, ?, ?, ?, ?, ?)";

        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setString(1, meta.sha256);
        ps.setString(2, meta.filename);
        ps.setBoolean(3, meta.gps_flag);

        if (meta.gps_flag) {
            ps.setDouble(4, meta.latitude);
            ps.setDouble(5, meta.longitude);
            if (meta.altitude != null) {
                ps.setDouble(6, meta.altitude);
            } else {
                ps.setNull(6, java.sql.Types.DOUBLE);
            }
        } else {
            ps.setNull(4, Types.DOUBLE);
            ps.setNull(5, Types.DOUBLE);
            ps.setNull(6, Types.DOUBLE);
        }

        ps.setString(7, meta.datetime);
        ps.setString(8, meta.cloud_uri);
        ps.setInt(9, meta.width);
        ps.setInt(10, meta.height);
        ps.setLong(11, meta.filesize);
        ps.setObject(12, meta.temperature_c, java.sql.Types.DOUBLE);
        ps.setObject(13, meta.humidity, java.sql.Types.DOUBLE);
        ps.setString(14, meta.weather_desc);
        ps.setObject(15, meta.elk_count, Types.INTEGER);
        ps.setBoolean(16, meta.processed_status);
        ps.executeUpdate();
    }

    /**
     * Inputs:      conn (Connection) — active database connection;
     *              batchSize (int) — maximum number of rows to return
     * Outputs:     List<Metadata> — unprocessed image records ordered by upload time (oldest first)
     * Functionality: Queries postgres.images for rows where processed_status = false, up to batchSize rows.
     * Dependencies: java.sql.PreparedStatement, java.sql.ResultSet, buildMetadataFromResultSet
     * Called by:   FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              FileProcessor.processAllUnprocessedWithPythonInference
     */
    static List<Metadata> getUnprocessedImages(Connection conn, int batchSize) throws SQLException {
        List<Metadata> results = new ArrayList<>();

        String sql = "SELECT * FROM postgres.images " +
                "WHERE processed_status = false " +
                "ORDER BY datetime_uploaded ASC " +
                "LIMIT ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, batchSize);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(buildMetadataFromResultSet(rs));
                }
            }
        }

        return results;
    }

    /**
     * Inputs:      rs (ResultSet) — positioned on a row from postgres.images
     * Outputs:     Metadata — object populated from the current ResultSet row
     * Functionality: Maps all columns of the images table to their corresponding Metadata fields,
     *               handling nullable numeric columns correctly.
     * Dependencies: java.sql.ResultSet, java.sql.Timestamp
     * Called by:   getUnprocessedImages, getImageByHash, getImagesByDateRange, getImagesByLocation
     */
    private static Metadata buildMetadataFromResultSet(ResultSet rs) throws SQLException {
        Metadata meta = new Metadata();

        meta.sha256 = rs.getString("img_hash");
        meta.cloud_uri = rs.getString("cloud_uri");
        meta.filename = rs.getString("filename");
        meta.filesize = rs.getLong("filesize_bytes");
        meta.width = rs.getInt("width");
        meta.height = rs.getInt("height");
        meta.gps_flag = rs.getBoolean("gps_flag");

        Double lat = (Double) rs.getObject("latitude");
        Double lon = (Double) rs.getObject("longitude");
        Double alt = (Double) rs.getObject("altitude");

        meta.latitude = lat;
        meta.longitude = lon;
        meta.altitude = alt;

        Timestamp ts = rs.getTimestamp("datetime_taken");
        meta.datetime = (ts != null) ? ts.toString() : null;

        meta.temperature_c = rs.getDouble("temperature_c");
        meta.humidity = rs.getDouble("humidity");
        meta.weather_desc = rs.getString("weather_desc");
        meta.processed_status = rs.getBoolean("processed_status");

        // elk_count: null if YOLO hasn't processed yet
        int elkCount = rs.getInt("elk_count");
        meta.elk_count = rs.wasNull() ? null : elkCount;

        return meta;
    }

    /**
     * Inputs:      conn (Connection) — active database connection; hash (String) — SHA-256 image hash
     * Outputs:     Metadata — matching row if found and processed_status = true, otherwise null
     * Functionality: Looks up a single processed image record by its content hash for duplicate detection.
     * Dependencies: java.sql.PreparedStatement, java.sql.ResultSet, buildMetadataFromResultSet
     * Called by:   FileProcessor.uploadAndProcessFiles, EmailProcessor.pollAndProcess,
     *              MessagingController.smsWebhook, MessagingController.sendGridEmailWebhook
     */
    static Metadata getImageByHash(Connection conn, String hash) throws SQLException {
        String sql = "SELECT * FROM postgres.images WHERE img_hash = ? AND processed_status = true";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return buildMetadataFromResultSet(rs);
                }
                return null;
            }
        }
    }

    /**
     * Inputs:      conn (Connection) — active database connection;
     *              startDate (String) — inclusive start date (yyyy-MM-dd);
     *              endDate (String) — inclusive end date (yyyy-MM-dd)
     * Outputs:     List<Metadata> — processed images taken within the date range, ordered by datetime_taken DESC
     * Functionality: Retrieves all processed image records whose datetime_taken falls within the given date range.
     * Dependencies: java.sql.PreparedStatement, java.sql.ResultSet, buildMetadataFromResultSet
     * Called by:   Available for use by reporting or query endpoints; not currently wired to a controller
     */
    static List<Metadata> getImagesByDateRange(Connection conn, String startDate, String endDate)
            throws SQLException {
        List<Metadata> results = new ArrayList<>();

        String sql = "SELECT * FROM postgres.images " +
                "WHERE datetime_taken >= ?::date " +
                "AND datetime_taken < (?::date + interval '1 day') " +
                "AND processed_status = true " +
                "ORDER BY datetime_taken DESC";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startDate);
            ps.setString(2, endDate);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(buildMetadataFromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Inputs:      conn (Connection) — active database connection;
     *              centerLat (double) — center latitude in decimal degrees;
     *              centerLon (double) — center longitude in decimal degrees;
     *              radiusKm (double) — search radius in kilometers
     * Outputs:     List<Metadata> — processed images with GPS within the radius, ordered by distance ASC
     * Functionality: Uses the Haversine formula in SQL to find all processed images within a given
     *               radius of a GPS coordinate.
     * Dependencies: java.sql.PreparedStatement, java.sql.ResultSet, buildMetadataFromResultSet
     * Called by:   Available for use by location-based query endpoints; not currently wired to a controller
     */
    static List<Metadata> getImagesByLocation(Connection conn, double centerLat, double centerLon,
            double radiusKm) throws SQLException {
        List<Metadata> results = new ArrayList<>();

        // Haversine formula in SQL to calculate distance
        String sql = "SELECT * FROM ( " +
                "  SELECT *, " +
                "    (6371 * acos( " +
                "      cos(radians(?)) * cos(radians(latitude)) * " +
                "      cos(radians(longitude) - radians(?)) + " +
                "      sin(radians(?)) * sin(radians(latitude)) " +
                "    )) AS distance_km " +
                "  FROM postgres.images " +
                "  WHERE gps_flag = true " +
                "    AND latitude IS NOT NULL " +
                "    AND longitude IS NOT NULL " +
                "    AND processed_status = true " +
                ") AS subquery " +
                "WHERE distance_km <= ? " +
                "ORDER BY distance_km";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDouble(1, centerLat);
            ps.setDouble(2, centerLon);
            ps.setDouble(3, centerLat);
            ps.setDouble(4, radiusKm);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(buildMetadataFromResultSet(rs));
                }
            }
        }
        return results;
    }

    /**
     * Inputs:      conn (Connection) — active database connection;
     *              sha256Hash (String) — SHA-256 hash identifying the image row;
     *              elkCount (Integer) — detected elk count (may be null if detection failed);
     *              processedStatus (boolean) — true if detection completed successfully
     * Outputs:     void — updates elk_count and processed_status for the matching row
     * Functionality: Writes animal detection results back to the database after the AnimalDetect API
     *               call completes; commits the transaction if auto-commit is disabled.
     * Dependencies: java.sql.PreparedStatement, java.sql.Types
     * Called by:   FileProcessor.uploadAndProcessFiles, FileProcessor.processAllUnprocessedWithAnimalDetect,
     *              FileProcessor.processAllUnprocessedWithPythonInference,
     *              EmailProcessor.pollAndProcess, MessagingController.sendGridEmailWebhook
     */
    static void updateMetaWithDetection(Connection conn, String sha256Hash, Integer elkCount, boolean processedStatus)
            throws SQLException {
        String sql = "UPDATE postgres.images SET elk_count = ?, processed_status = ? WHERE img_hash = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, elkCount, Types.INTEGER);
            ps.setBoolean(2, processedStatus);
            ps.setString(3, sha256Hash);
            ps.executeUpdate();
            if (!conn.getAutoCommit()) {
                conn.commit();
            }
        }
    }
}

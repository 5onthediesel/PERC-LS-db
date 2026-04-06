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

    static void initializeSchemaAtStartup() throws SQLException {
        try (Connection conn = connect()) {
            setupSchema(conn, false);
        }
    }

    static void setupSchema(Connection conn) throws SQLException {
        setupSchema(conn, true);
    }

    static void setupSchema(Connection conn, boolean resetTable) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("create schema if not exists cs370");
            s.execute("set search_path to cs370");

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

    static Metadata loadMetadata(File f) throws Exception {
        Metadata meta = new Metadata();
        ImageUtils.ExifData d;
        String ext = ImageUtils.getExtension(f.getName()).toLowerCase();
        if (ext.equals("png")) {
            d = ImageUtils.parseExifFromPng(f);
            if (d == null)
                d = new ImageUtils.ExifData();
        } else {
            d = ImageUtils.parse(f.getAbsolutePath());
        }

        meta.filename = f.getName();
        meta.filesize = f.length();
        meta.datetime = d.date;

        // // DEBUG (Print EXIF)
        // System.out.println("DEBUG " + f.getName() + ":");
        // System.out.println(" date: " + d.date);
        // System.out.println(" lat: " + d.lat);
        // System.out.println(" lon: " + d.lon);
        // System.out.println(" alt: " + d.alt);

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

    static void insertMeta(Connection conn, Metadata meta) throws SQLException {
        String sql = "insert into cs370.images (" +
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

    static List<Metadata> getUnprocessedImages(Connection conn, int batchSize) throws SQLException {
        List<Metadata> results = new ArrayList<>();

        String sql = "SELECT * FROM cs370.images " +
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

    /*
     * Legacy full metadata update method (unused).
     * Current processing paths update only detection fields via
     * updateMetaWithDetection.
     *
     * static int updateProcessedMetadata(Connection conn, String hash, Metadata
     * meta)
     * throws SQLException {
     * String sql = "UPDATE cs370.images SET " +
     * "filename = ?, " +
     * "filesize_bytes = ?, " +
     * "width = ?, " +
     * "height = ?, " +
     * "gps_flag = ?, " +
     * "latitude = ?, " +
     * "longitude = ?, " +
     * "altitude = ?, " +
     * "datetime_taken = CASE WHEN ? IS NULL THEN NULL " +
     * "ELSE to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') END, " +
     * "temperature_c = ?, " +
     * "humidity = ?, " +
     * "weather_desc = ?, " +
     * "elk_count = ?, " +
     * "processed_status = true " +
     * "WHERE img_hash = ? AND processed_status = false";
     *
     * try (PreparedStatement ps = conn.prepareStatement(sql)) {
     * ps.setString(1, meta.filename);
     * ps.setLong(2, meta.filesize);
     * ps.setInt(3, meta.width);
     * ps.setInt(4, meta.height);
     * ps.setBoolean(5, meta.gps_flag);
     * ps.setObject(6, meta.latitude, Types.DOUBLE);
     * ps.setObject(7, meta.longitude, Types.DOUBLE);
     * ps.setObject(8, meta.altitude, Types.DOUBLE);
     * ps.setString(9, meta.datetime);
     * ps.setString(10, meta.datetime);
     * ps.setObject(11, meta.temperature_c, Types.DOUBLE);
     * ps.setObject(12, meta.humidity, Types.DOUBLE);
     * ps.setString(13, meta.weather_desc);
     * ps.setObject(14, meta.elk_count, Types.INTEGER);
     * ps.setString(15, hash);
     * return ps.executeUpdate();
     * }
     * }
     */

    // Builds a Metadata object from a SQL ResultSet row
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

    // Retrieve a specific image's metadata by its SHA-256 hash
    // Example: Metadata img = getImageByHash(conn, "a3f2b9c8d1e5...");
    static Metadata getImageByHash(Connection conn, String hash) throws SQLException {
        String sql = "SELECT * FROM cs370.images WHERE img_hash = ? AND processed_status = true";

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

    // Retrieve all images taken within a date range
    // Example: Get all November 2024 images
    static List<Metadata> getImagesByDateRange(Connection conn, String startDate, String endDate)
            throws SQLException {
        List<Metadata> results = new ArrayList<>();

        String sql = "SELECT * FROM cs370.images " +
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

    // Retrieve all images within a radius of a GPS coordinate
    // Example: Get all images within 5km of Paradise Valley Ranch
    static List<Metadata> getImagesByLocation(Connection conn, double centerLat, double centerLon,
            double radiusKm) throws SQLException {
        List<Metadata> results = new ArrayList<>();

        // Haversine formula in SQL to calculate distance
        // Formula: distance = 2 * R * asin(sqrt(sin²((lat2-lat1)/2) +
        // cos(lat1)*cos(lat2)*sin²((lon2-lon1)/2)))
        // Where R = Earth's radius in km (6371)

        String sql = "SELECT * FROM ( " +
                "  SELECT *, " +
                "    (6371 * acos( " +
                "      cos(radians(?)) * cos(radians(latitude)) * " +
                "      cos(radians(longitude) - radians(?)) + " +
                "      sin(radians(?)) * sin(radians(latitude)) " +
                "    )) AS distance_km " +
                "  FROM cs370.images " +
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

    /*
     * Legacy/manual-only helpers currently unused by runtime and tests.
     *
     * // Retrieve the most recently uploaded images
     * static List<Metadata> getRecentImages(Connection conn, int limit) throws
     * SQLException {
     * List<Metadata> results = new ArrayList<>();
     *
     * String sql = "SELECT * FROM cs370.images " +
     * "WHERE processed_status = true " +
     * "ORDER BY datetime_uploaded DESC " +
     * "LIMIT ?";
     *
     * try (PreparedStatement ps = conn.prepareStatement(sql)) {
     * ps.setInt(1, limit);
     *
     * try (ResultSet rs = ps.executeQuery()) {
     * while (rs.next()) {
     * results.add(buildMetadataFromResultSet(rs));
     * }
     * }
     * }
     *
     * return results;
     * }
     *
     * static void shipImgs(Metadata meta, Connection conn, List<File> jpgFiles)
     * throws Exception {
     * for (File jpg : jpgFiles) {
     * try {
     * meta = loadMetadata(jpg);
     * insertMeta(conn, meta);
     *
     * } catch (SQLException e) {
     * if (e.getSQLState().equals("23505")) {
     * System.out.println("Duplicate image skipped: " + (meta != null ?
     * meta.filename :
     * jpg.getName()));
     * } else {
     * throw e;
     * }
     * } catch (Exception e) {
     * e.printStackTrace();
     * }
     * }
     * }
     *
     * static List<File> prepareImages(File folder) {
     * List<File> jpgFiles = new ArrayList<>();
     *
     * for (File f : folder.listFiles()) {
     * if (!f.isFile() || f.getName().startsWith("."))
     * continue;
     * try {
     * File fileToProcess = ImageUtils.convertToJpg(f);
     * jpgFiles.add(fileToProcess);
     * } catch (Exception e) {
     * e.printStackTrace();
     * }
     * }
     * return jpgFiles;
     * }
     */

    /**
     * Update image metadata with animal detection results (elk_count and
     * processed_status).
     * Called after AnimalDetect API completes detection on a newly uploaded image.
     */
    static void updateMetaWithDetection(Connection conn, String sha256Hash, Integer elkCount, boolean processedStatus)
            throws SQLException {
        String sql = "UPDATE cs370.images SET elk_count = ?, processed_status = ? WHERE img_hash = ?";

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

    /* -------------------------------------------------------------------------- */

    /*
     * Legacy manual runner (unused).
     *
     * public static void main(String[] args) throws Exception {
     * File folder = new File("images");
     * List<File> jpgs = prepareImages(folder);
     * Metadata meta = new Metadata();
     *
     * try (Connection conn = connect()) {
     * setupSchema(conn);
     * shipImgs(meta, conn, jpgs);
     * }
     * }
     */
}

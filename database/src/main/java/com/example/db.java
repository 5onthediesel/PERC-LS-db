package com.example;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.checkerframework.checker.units.qual.m;

import java.net.URI;
import java.net.http.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

class db {

    static Connection connect() throws SQLException {

        String url = "jdbc:postgresql://localhost:5432/postgres";

        // BRIAN'S POSTGRES USER & PASS
        // String user = "postgres";
        // String pass = "rubiks";

        // VICTOR'S POSTGRES USER & PASS
        String user = "victorli";
        String pass = "rubix";

        // CARSON's POSTGRES USER & PASS
        // String user = "postgres";
        // String pass = "postgres";

        Connection conn = DriverManager.getConnection(url, user, pass);
        return conn;
    }

    static Metadata loadMetadata(File f) throws Exception {
        Metadata meta = new Metadata();
        EXIFParser.ExifData d;
        String ext = ImgDet.getExtension(f.getName()).toLowerCase();
        if (ext.equals("png")) {
            d = ImgDet.parseExifFromPng(f);
            if (d == null)
                d = new EXIFParser.ExifData();
        } else {
            d = EXIFParser.parse(f.getAbsolutePath());
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

        meta.sha256 = ImgHash.sha256(f);
        meta.width = ImgDet.getWidth(f);
        meta.height = ImgDet.getHeight(f);
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

    static void setupSchema(Connection conn) throws SQLException {
        Statement s = conn.createStatement();
        s.execute("set search_path to cs370");
        s.execute("drop table if exists images");
        s.execute("""
                    create table if not exists images (
                    id serial primary key,
                    img_hash varchar(64) unique,
                    cloud_uri text not null,

                    humidity double precision,
                    temperature_c double precision,
                    weather_desc text,

                    filename text,
                    filesize_bytes bigint,
                    width int,
                    height int,

                    gps_flag boolean,
                    latitude double precision,
                    longitude double precision,
                    altitude double precision,
                    datetime_taken timestamptz,
                    datetime_uploaded timestamptz default now(),
                    processed_status boolean
                    )
                """);
    }

    static void insertMeta(Connection conn, Metadata meta) throws SQLException {
        String sql = "insert into images (" +
                "img_hash, filename, gps_flag, latitude, longitude, altitude, datetime_taken, " +
                "cloud_uri, width, height, filesize_bytes, temperature_c, humidity, weather_desc, processed_status) " +
                "values (?, ?, ?, ?, ?, ?, to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS'), " +
                "?, ?, ?, ?, ?, ?, ?, ?)";

        PreparedStatement ps = conn.prepareStatement(sql);
        ps.setString(1, meta.sha256);
        ps.setString(2, meta.filename);
        ps.setBoolean(3, meta.gps_flag);

        if (meta.gps_flag) {
            ps.setDouble(4, meta.latitude);
            ps.setDouble(5, meta.longitude);
            ps.setDouble(6, meta.altitude);
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
        ps.setDouble(12, meta.temperature_c);
        ps.setDouble(13, meta.humidity);
        ps.setString(14, meta.weather_desc);
        ps.setBoolean(15, meta.processed_status);
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

    static int updateProcessedMetadata(Connection conn, String hash, Metadata meta) throws SQLException {
        String sql = "UPDATE cs370.images SET " +
                "filename = ?, " +
                "filesize_bytes = ?, " +
                "width = ?, " +
                "height = ?, " +
                "gps_flag = ?, " +
                "latitude = ?, " +
                "longitude = ?, " +
                "altitude = ?, " +
                "datetime_taken = CASE WHEN ? IS NULL THEN NULL " +
                "ELSE to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') END, " +
                "temperature_c = ?, " +
                "humidity = ?, " +
                "weather_desc = ?, " +
                "processed_status = true " +
                "WHERE img_hash = ? AND processed_status = false";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, meta.filename);
            ps.setLong(2, meta.filesize);
            ps.setInt(3, meta.width);
            ps.setInt(4, meta.height);
            ps.setBoolean(5, meta.gps_flag);
            ps.setObject(6, meta.latitude, Types.DOUBLE);
            ps.setObject(7, meta.longitude, Types.DOUBLE);
            ps.setObject(8, meta.altitude, Types.DOUBLE);
            ps.setString(9, meta.datetime);
            ps.setString(10, meta.datetime);
            ps.setObject(11, meta.temperature_c, Types.DOUBLE);
            ps.setObject(12, meta.humidity, Types.DOUBLE);
            ps.setString(13, meta.weather_desc);
            ps.setString(14, hash);
            return ps.executeUpdate();
        }
    }

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

    // Retrieve the most recently uploaded images
    static List<Metadata> getRecentImages(Connection conn, int limit) throws SQLException {
        List<Metadata> results = new ArrayList<>();

        String sql = "SELECT * FROM cs370.images " +
                "WHERE processed_status = true " +
                "ORDER BY datetime_uploaded DESC " +
                "LIMIT ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(buildMetadataFromResultSet(rs));
                }
            }
        }

        return results;
    }

    static void shipImgs(Metadata meta, Connection conn, List<File> jpgFiles) throws Exception {
        for (File jpg : jpgFiles) {
            try {
                meta = loadMetadata(jpg);
                insertMeta(conn, meta);

            } catch (SQLException e) {
                if (e.getSQLState().equals("23505")) {
                    System.out.println("Duplicate image skipped: " + (meta != null ? meta.filename : jpg.getName()));
                } else {
                    throw e;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static List<File> prepareImages(File folder) {
        List<File> jpgFiles = new ArrayList<>();

        for (File f : folder.listFiles()) {
            if (!f.isFile() || f.getName().startsWith("."))
                continue;
            try {
                File fileToProcess = ImgDet.convertToJpg(f);
                jpgFiles.add(fileToProcess);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return jpgFiles;
    }

    /* -------------------------------------------------------------------------- */

    public static void main(String[] args) throws Exception {
        File folder = new File("images");
        List<File> jpgs = prepareImages(folder);
        Metadata meta = new Metadata();

        try (Connection conn = connect()) {
            setupSchema(conn);
            shipImgs(meta, conn, jpgs);

            // System.out.println("\n=== Testing Query Methods ===");
            //
            // // TEST: Get recent images
            // List<Metadata> recent = getRecentImages(conn, 3);
            // System.out.println("Recent images: " + recent.size());
            // for (Metadata m : recent) {
            // System.out.println(" - " + m.datetime);
            // }

            // // TEST: Get by date range
            // List<Metadata> dated = getImagesByDateRange(conn, "2026-01-01",
            // "2026-12-31");
            // System.out.println("\nImages from 2026: " + dated.size());

            // // TEST: Get & print imageas within 10km of first image
            // if (!recent.isEmpty() && recent.get(0).gps_flag) {
            // Metadata first = recent.get(0); // First image metadata
            // List<Metadata> nearby = getImagesByLocation(conn, first.latitude,
            // first.longitude, 10.0);
            // System.out.println("\nImages within 10km: " + nearby.size());
            // for (Metadata m : nearby) {
            // System.out.println(" - " + m.filename);
            // }
            // }
        }
    }
}

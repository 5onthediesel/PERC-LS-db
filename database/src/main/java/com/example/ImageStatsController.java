package com.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ImageStatsController {

    /**
     * Inputs:      None (HTTP GET /api/images/summary)
     * Outputs:     ResponseEntity<?> — 200 OK with a JSON object containing:
     *                "uploadsByDate" (list of {date, count, elkTotal}),
     *                "total" (int), "withGps" (int), "withoutGps" (int),
     *                "totalElk" (int), "processedCount" (int);
     *              500 Internal Server Error on DB failure
     * Functionality: Aggregates three DB queries — daily upload counts with elk totals, GPS coverage
     *               counts, and processed image count — into a single summary response for the frontend dashboard.
     * Dependencies: db.connect, java.sql.PreparedStatement, java.sql.ResultSet,
     *               org.springframework.http.ResponseEntity
     * Called by:   HTTP clients (frontend dashboard) via GET /api/images/summary
     */
    @GetMapping("/images/summary")
    public ResponseEntity<?> getImagesSummary() {
        try (Connection conn = db.connect()) {

            try (var s = conn.createStatement()) {
                s.execute("set search_path to postgres");
            }

            // Uploads by date — also include elk count per day
            String dateSql = "SELECT datetime_uploaded::date AS upload_date, " +
                    "COUNT(*) AS count, " +
                    "SUM(CASE WHEN elk_count IS NOT NULL THEN elk_count ELSE 0 END) AS elk_total " +
                    "FROM postgres.images " +
                    "GROUP BY datetime_uploaded::date " +
                    "ORDER BY datetime_uploaded::date ASC";

            List<Map<String, Object>> uploadsByDate = new ArrayList<>();
            int total = 0;
            int totalElk = 0;

            try (PreparedStatement ps = conn.prepareStatement(dateSql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("date", rs.getDate("upload_date").toString());
                    row.put("count", rs.getInt("count"));
                    row.put("elkTotal", rs.getInt("elk_total"));
                    uploadsByDate.add(row);
                    total += rs.getInt("count");
                    totalElk += rs.getInt("elk_total");
                }
            }

            // GPS coverage stats
            String gpsSql = "SELECT " +
                    "COUNT(*) FILTER (WHERE gps_flag = true) AS with_gps, " +
                    "COUNT(*) FILTER (WHERE gps_flag IS DISTINCT FROM true) AS without_gps " +
                    "FROM postgres.images";

            int withGps = 0, withoutGps = 0;
            try (PreparedStatement ps = conn.prepareStatement(gpsSql);
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    withGps = rs.getInt("with_gps");
                    withoutGps = rs.getInt("without_gps");
                }
            }

            // How many images have been processed by the model
            String processedSql = "SELECT COUNT(*) AS processed FROM postgres.images WHERE processed_status = true";
            int processedCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(processedSql);
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next())
                    processedCount = rs.getInt("processed");
            }

            Map<String, Object> response = new HashMap<>();
            response.put("uploadsByDate", uploadsByDate);
            response.put("total", total);
            response.put("withGps", withGps);
            response.put("withoutGps", withoutGps);
            response.put("totalElk", totalElk);
            response.put("processedCount", processedCount);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Inputs:      None (HTTP GET /api/images/locations)
     * Outputs:     ResponseEntity<?> — 200 OK with JSON {"locations": [{filename, latitude, longitude,
     *              altitude, datetimeTaken, elkCount}, ...]}, ordered by datetime_taken DESC;
     *              500 Internal Server Error on DB failure
     * Functionality: Returns all images that have GPS coordinates so the frontend map can plot
     *               observation locations with elk count markers.
     * Dependencies: db.connect, java.sql.PreparedStatement, java.sql.ResultSet,
     *               org.springframework.http.ResponseEntity
     * Called by:   HTTP clients (frontend dashboard map view) via GET /api/images/locations
     */
    @GetMapping("/images/locations")
    public ResponseEntity<?> getImageLocations() {
        try (Connection conn = db.connect()) {

            try (var s = conn.createStatement()) {
                s.execute("set search_path to postgres");
            }

            String sql = "SELECT filename, latitude, longitude, altitude, datetime_taken, elk_count " +
                    "FROM postgres.images " +
                    "WHERE gps_flag = true " +
                    "AND latitude IS NOT NULL " +
                    "AND longitude IS NOT NULL " +
                    "ORDER BY datetime_taken DESC";

            List<Map<String, Object>> locations = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> loc = new HashMap<>();
                    loc.put("filename", rs.getString("filename"));
                    loc.put("latitude", rs.getDouble("latitude"));
                    loc.put("longitude", rs.getDouble("longitude"));
                    double alt = rs.getDouble("altitude");
                    loc.put("altitude", rs.wasNull() ? null : alt);
                    var ts = rs.getTimestamp("datetime_taken");
                    loc.put("datetimeTaken", ts != null ? ts.toString() : null);
                    loc.put("elkCount", rs.getObject("elk_count"));
                    locations.add(loc);
                }
            }

            return ResponseEntity.ok(Map.of("locations", locations));

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}

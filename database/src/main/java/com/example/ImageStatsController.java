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
// @CrossOrigin(origins = "http://localhost:3000") // Switched to global CORS
public class ImageStatsController {

    /**
     * Returns image upload counts grouped by date.
     * Used by the frontend Dashboard page to render the bar chart.
     *
     * Response format:
     * {
     *   "uploadsByDate": [
     *     { "date": "2026-02-07", "count": 3 },
     *     { "date": "2026-02-10", "count": 5 },
     *     ...
     *   ],
     *   "total": 8
     * }
     */
    /**
     * Returns image upload counts grouped by date, plus GPS coverage stats.
     *
     * Response format:
     * {
     *   "uploadsByDate": [
     *     { "date": "2026-02-07", "count": 3 },
     *     ...
     *   ],
     *   "total": 8,
     *   "withGps": 5,
     *   "withoutGps": 3
     * }
     */
    @GetMapping("/images/summary")
    public ResponseEntity<?> getImagesSummary() {
        try (Connection conn = db.connect()) {

            try (var s = conn.createStatement()) {
                s.execute("set search_path to cs370");
            }

            // Uploads by date — also include elk count per day
            String dateSql = "SELECT datetime_uploaded::date AS upload_date, " +
                             "COUNT(*) AS count, " +
                             "SUM(CASE WHEN elk_count IS NOT NULL THEN elk_count ELSE 0 END) AS elk_total " +
                             "FROM cs370.images " +
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
                            "FROM cs370.images";

            int withGps = 0, withoutGps = 0;
            try (PreparedStatement ps = conn.prepareStatement(gpsSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    withGps = rs.getInt("with_gps");
                    withoutGps = rs.getInt("without_gps");
                }
            }

            // How many images have been processed by the model
            String processedSql = "SELECT COUNT(*) AS processed FROM cs370.images WHERE processed_status = true";
            int processedCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(processedSql);
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) processedCount = rs.getInt("processed");
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
     * Returns all images that have GPS coordinates.
     * Used by the frontend Dashboard page to plot observation locations on the map.
     *
     * Response format:
     * {
     *   "locations": [
     *     { "filename": "IMG_3141.jpg", "latitude": 33.79, "longitude": -84.33,
     *       "altitude": 958.6, "datetimeTaken": "2026-02-07T21:18:06" },
     *     ...
     *   ]
     * }
     */
    @GetMapping("/images/locations")
    public ResponseEntity<?> getImageLocations() {
        try (Connection conn = db.connect()) {

            try (var s = conn.createStatement()) {
                s.execute("set search_path to cs370");
            }

            String sql = "SELECT filename, latitude, longitude, altitude, datetime_taken, elk_count " +
                        "FROM cs370.images " +
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
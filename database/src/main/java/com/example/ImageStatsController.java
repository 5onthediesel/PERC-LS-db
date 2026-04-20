package com.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ImageStatsController {

    /**
     * Inputs: None (HTTP GET /api/images/summary)
     * Outputs: ResponseEntity<?> — 200 OK with a JSON object containing:
     * "uploadsByDate" (list of {date, count, elkTotal}),
     * "total" (int), "withGps" (int), "withoutGps" (int),
     * "totalElk" (int), "processedCount" (int);
     * 500 Internal Server Error on DB failure
     * Functionality: Aggregates three DB queries — daily upload counts with elk
     * totals, GPS coverage
     * counts, and processed image count — into a single summary response for the
     * frontend dashboard.
     * Dependencies: db.connect, java.sql.PreparedStatement, java.sql.ResultSet,
     * org.springframework.http.ResponseEntity
     * Called by: HTTP clients (frontend dashboard) via GET /api/images/summary
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

            // GPS coverage counts
            String gpsSql = "SELECT " +
                    "COUNT(*) FILTER (WHERE gps_flag = true) AS with_gps, " +
                    "COUNT(*) FILTER (WHERE gps_flag = false OR gps_flag IS NULL) AS without_gps " +
                    "FROM postgres.images";

            int withGps = 0, withoutGps = 0;
            try (PreparedStatement ps = conn.prepareStatement(gpsSql);
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    withGps = rs.getInt("with_gps");
                    withoutGps = rs.getInt("without_gps");
                }
            }

            // Processed count
            String processedSql = "SELECT COUNT(*) AS processed_count FROM postgres.images WHERE processed_status = true";
            int processedCount = 0;
            try (PreparedStatement ps = conn.prepareStatement(processedSql);
                    ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    processedCount = rs.getInt("processed_count");
                }
            }

            // Taken by date
            String takenSql = "SELECT datetime_taken::date AS taken_date, " +
                    "COUNT(*) AS count, " +
                    "SUM(CASE WHEN elk_count IS NOT NULL THEN elk_count ELSE 0 END) AS elk_total " +
                    "FROM postgres.images " +
                    "WHERE datetime_taken IS NOT NULL " +
                    "GROUP BY datetime_taken::date " +
                    "ORDER BY datetime_taken::date ASC";

            List<Map<String, Object>> takenByDate = new ArrayList<>();
            int imagesWithoutDate = 0;
            int elkWithoutDate = 0;

            try (PreparedStatement ps = conn.prepareStatement(takenSql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("date", rs.getDate("taken_date").toString());
                    row.put("count", rs.getInt("count"));
                    row.put("elkTotal", rs.getInt("elk_total"));
                    takenByDate.add(row);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("uploadsByDate", uploadsByDate);
            response.put("takenByDate", takenByDate);
            response.put("total", total);
            response.put("withGps", withGps);
            response.put("withoutGps", withoutGps);
            response.put("totalElk", totalElk);
            response.put("processedCount", processedCount);
            response.put("imagesWithoutDate", imagesWithoutDate);
            response.put("elkWithoutDate", elkWithoutDate);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Inputs: None (HTTP GET /api/images/locations)
     * Outputs: ResponseEntity<?> — 200 OK with JSON {"locations": [{filename,
     * latitude, longitude,
     * altitude, datetimeTaken, elkCount}, ...]}, ordered by datetime_taken DESC;
     * 500 Internal Server Error on DB failure
     * Functionality: Returns all images that have GPS coordinates so the frontend
     * map can plot
     * observation locations with elk count markers.
     */
    @GetMapping("/images/locations")
    public ResponseEntity<?> getImageLocations() {
        try (Connection conn = db.connect()) {

            try (var s = conn.createStatement()) {
                s.execute("set search_path to postgres");
            }

            String sql = "SELECT filename, latitude, longitude, altitude, datetime_taken, elk_count " +
                    "FROM postgres.images " +
                    "WHERE gps_flag = true AND latitude IS NOT NULL AND longitude IS NOT NULL AND elk_count > 0 " +
                    "ORDER BY datetime_taken DESC";

            List<Map<String, Object>> locations = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(sql);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> loc = new HashMap<>();
                    loc.put("filename", rs.getString("filename"));
                    loc.put("latitude", rs.getDouble("latitude"));
                    loc.put("longitude", rs.getDouble("longitude"));
                    loc.put("altitude", rs.getObject("altitude"));
                    Timestamp ts = rs.getTimestamp("datetime_taken");
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

    /**
     * Inputs: body (Map) — JSON body with "sha256" (String) and "elkCount"
     * (Integer)
     * Outputs: ResponseEntity<?> — 200 OK {"status":"updated","elkCount":N} on
     * success;
     * 400 Bad Request if fields are missing/invalid; 500 on DB failure
     * Functionality: Persists a user-corrected elk count for an uploaded image,
     * overwriting the AI-detected value in the database.
     * Dependencies: db.connect, db.updateMetaWithDetection
     * Called by: Frontend upload results elk count editor via POST
     * /api/images/update-elk-count
     */
    @PostMapping("/images/update-elk-count")
    public ResponseEntity<?> updateElkCount(@RequestBody Map<String, Object> body) {
        Object rawSha256 = body.get("sha256");
        Object rawCount = body.get("elkCount");
        if (rawSha256 == null || rawCount == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sha256 and elkCount are required"));
        }
        String sha256 = rawSha256.toString();
        int elkCount;
        try {
            elkCount = ((Number) rawCount).intValue();
        } catch (ClassCastException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "elkCount must be a number"));
        }
        if (elkCount < 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "elkCount must be non-negative"));
        }
        try (Connection conn = db.connect()) {
            db.updateMetaWithDetection(conn, sha256, elkCount, true);
            return ResponseEntity.ok(Map.of("status", "updated", "elkCount", elkCount));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Inputs: body (Map) — JSON with "sha256" (String), "latitude" (Double),
     * "longitude" (Double)
     * Outputs: ResponseEntity<?> — 200 OK on success; 400/500 on error
     * Functionality: Saves a manually pinned GPS location for an image that had no
     * EXIF GPS data.
     * Called by: Frontend LocationPicker via POST /api/images/update-location
     */
    @PostMapping("/images/update-location")
    public ResponseEntity<?> updateLocation(@RequestBody Map<String, Object> body) {
        Object rawSha256 = body.get("sha256");
        Object rawLat = body.get("latitude");
        Object rawLng = body.get("longitude");
        if (rawSha256 == null || rawLat == null || rawLng == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sha256, latitude, and longitude are required"));
        }
        String sha256 = rawSha256.toString();
        double latitude, longitude;
        try {
            latitude = ((Number) rawLat).doubleValue();
            longitude = ((Number) rawLng).doubleValue();
        } catch (ClassCastException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "latitude and longitude must be numbers"));
        }
        try (Connection conn = db.connect()) {
            String sql = "UPDATE postgres.images SET latitude = ?, longitude = ?, gps_flag = true WHERE img_hash = ?";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setDouble(1, latitude);
                ps.setDouble(2, longitude);
                ps.setString(3, sha256);
                ps.executeUpdate();
                if (!conn.getAutoCommit())
                    conn.commit();
            }
            return ResponseEntity.ok(Map.of("status", "updated", "latitude", latitude, "longitude", longitude));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Inputs: body (Map) — JSON with "sha256" (String) and "datetimeTaken" (String,
     * "YYYY-MM-DD HH:MM:SS")
     * Outputs: ResponseEntity<?> — 200 OK on success; 400/500 on error
     * Functionality: Updates the datetime_taken field for an image that had no EXIF
     * date, or corrects it.
     * Called by: Frontend DateTakenEditor via POST /api/images/update-datetime
     */
    @PostMapping("/images/update-datetime")
    public ResponseEntity<?> updateDatetime(@RequestBody Map<String, Object> body) {
        Object rawSha256 = body.get("sha256");
        Object rawDatetime = body.get("datetimeTaken");
        if (rawSha256 == null || rawDatetime == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "sha256 and datetimeTaken are required"));
        }
        String sha256 = rawSha256.toString();
        String datetimeStr = rawDatetime.toString();
        try (Connection conn = db.connect()) {
            String sql = "UPDATE postgres.images SET datetime_taken = to_timestamp(?, 'YYYY-MM-DD HH24:MI:SS') WHERE img_hash = ?";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, datetimeStr);
                ps.setString(2, sha256);
                ps.executeUpdate();
                if (!conn.getAutoCommit())
                    conn.commit();
            }
            return ResponseEntity.ok(Map.of("status", "updated", "datetimeTaken", datetimeStr));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
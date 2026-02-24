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
    @GetMapping("/images/summary")
    public ResponseEntity<?> getImagesSummary() {
        try (Connection conn = db.connect()) {

            // Ensure schema search path is set
            try (var s = conn.createStatement()) {
                s.execute("set search_path to cs370");
            }

            String sql = "SELECT datetime_uploaded::date AS upload_date, COUNT(*) AS count " +
                         "FROM cs370.images " +
                         "GROUP BY datetime_uploaded::date " +
                         "ORDER BY datetime_uploaded::date ASC";

            List<Map<String, Object>> uploadsByDate = new ArrayList<>();
            int total = 0;

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    row.put("date", rs.getDate("upload_date").toString());
                    row.put("count", rs.getInt("count"));
                    uploadsByDate.add(row);
                    total += rs.getInt("count");
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("uploadsByDate", uploadsByDate);
            response.put("total", total);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
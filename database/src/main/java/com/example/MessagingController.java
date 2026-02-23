package com.example;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
public class MessagingController {

    /**
     * Endpoint for local testing: POST /test/send-image
     * Accepts an image file, processes it through the pipeline, and returns metadata.
     * Logs result to console via Messenger.
     */
    @PostMapping("/test/send-image")
    public ResponseEntity<?> sendImageTest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "phone_number", required = false) String phoneNumber) {
        try {
            // Validate that we received an image file
            if (file == null || file.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "File is required"));
            }

            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith("image/")) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Only image files are accepted"));
            }

            // Process image through pipeline: extract metadata, save locally, store in H2
            ImagePipeline.Result result = ImagePipeline.processImage(file);

            if (!result.isSuccess()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Pipeline processing failed",
                                "details", result.error.getMessage()));
            }

            // Log the result via Messenger
            String message = "Image processed: " + result.meta.filename +
                    " | Hash: " + result.meta.sha256 +
                    " | Saved to: " + result.filePath;
            Messenger.sendReply(phoneNumber, message);

            // Return metadata as JSON
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "Image metadata extracted and stored in Postgres");
            response.put("metadata", new HashMap<String, Object>() {{
                put("filename", result.meta.filename);
                put("filesize_bytes", result.meta.filesize);
                put("sha256", result.meta.sha256);
                put("width", result.meta.width);
                put("height", result.meta.height);
                put("datetime_taken", result.meta.datetime);
                put("gps_flag", result.meta.gps_flag);
                if (result.meta.gps_flag) {
                    put("latitude", result.meta.latitude);
                    put("longitude", result.meta.longitude);
                    put("altitude", result.meta.altitude);
                }
                put("temperature_c", result.meta.temperature_c);
                put("humidity", result.meta.humidity);
            }});
            response.put("file_path", result.filePath);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Internal server error",
                            "details", e.getMessage()));
        }
    }

    /**
     * Twilio webhook endpoint: POST /sms
     * Activate when switching to Twilio messaging mode.
     * Currently stubbed out for local development.
     */
    @PostMapping("/sms")
    public ResponseEntity<String> smsWebhook() {
        // Future implementation:
        // - Extract 'From' and 'MediaUrl0' from Twilio request params
        // - Download MMS image from MediaUrl0
        // - Run through ImagePipeline.processImage()
        // - Reply via Messenger.sendReply()
        // - Return TwiML response

        return ResponseEntity.ok("<Response></Response>");
    }
}

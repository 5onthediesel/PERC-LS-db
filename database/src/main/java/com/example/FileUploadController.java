package com.example;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// mvn spring-boot:run
@RestController
@RequestMapping("/api")
public class FileUploadController {
    private static final Logger logger = Logger.getLogger(FileUploadController.class.getName());

    /**
     * Inputs:      files (MultipartFile[]) — one or more image files from the multipart request;
     *              metadataJson (String, optional) — JSON array of per-file metadata (GPS, datetime, etc.)
     * Outputs:     ResponseEntity<?> — 200 OK with upload results map on success;
     *              400 Bad Request for invalid input; 500 Internal Server Error for unexpected failures
     * Functionality: HTTP POST /api/upload handler that delegates to FileProcessor.uploadAndProcessFiles
     *               for GCS upload, DB insertion, and live AnimalDetect inference.
     * Dependencies: FileProcessor.uploadAndProcessFiles, org.springframework.web.multipart.MultipartFile,
     *               org.springframework.http.ResponseEntity
     * Called by:   HTTP clients (frontend dashboard, mobile apps, curl) via POST /api/upload
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFileInstantProcessed(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "metadata", required = false) String metadataJson) {
        try {
            var uploadedFiles = FileProcessor.uploadAndProcessFiles(files, metadataJson);

            return ResponseEntity
                    .ok(Map.of("message", "Files uploaded successfully (with live detection)", "files", uploadedFiles));
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Invalid upload request", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            logger.log(Level.SEVERE, "Upload failed in uploadFileInstantProcessed", e);
            System.err.print(sw.toString());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "stackTrace", sw.toString()));
        }
    }
}

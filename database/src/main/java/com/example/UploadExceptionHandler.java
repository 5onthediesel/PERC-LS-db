package com.example;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

@RestControllerAdvice
public class UploadExceptionHandler {

    /**
     * Inputs: e (MaxUploadSizeExceededException) -- multipart upload exception from
     * Spring
     * Outputs: ResponseEntity<?> -- 400 response containing a user-facing upload
     * size message
     * Functionality: Converts oversized multipart request exceptions into a
     * consistent JSON error
     * for the frontend.
     * Dependencies:
     * org.springframework.web.multipart.MaxUploadSizeExceededException
     * Called by: Spring MVC exception handling
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error",
                        "Upload too large. Keep each file under 10MB and send no more than 10 files per upload."));
    }

    /**
     * Inputs: e (MultipartException) -- multipart parsing exception from Spring
     * Outputs: ResponseEntity<?> -- 400 response containing a user-facing multipart
     * error message
     * Functionality: Converts malformed or oversized multipart requests into a
     * consistent JSON
     * error for the frontend.
     * Dependencies: org.springframework.web.multipart.MultipartException
     * Called by: Spring MVC exception handling
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipartException(MultipartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error",
                        "Upload request is too large or malformed. Keep each file under 10MB and send no more than 10 files per upload."));
    }
}

package com.example;

import java.util.Map;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {
    private static final Logger logger = Logger.getLogger(TaskController.class.getName());

    private final EventScheduler eventScheduler;

    public TaskController(EventScheduler eventScheduler) {
        this.eventScheduler = eventScheduler;
    }

    /**
     * Inputs: taskTokenHeader (String, optional header X-Task-Token) — shared
     * secret to authenticate the caller
     * Outputs: ResponseEntity<?> — 200 OK {"status":"ok","job":"poll-email"} on
     * success;
     * 503 Service Unavailable if TASK_TOKEN is not configured;
     * 401 Unauthorized if the token does not match
     * Functionality: HTTP POST /internal/tasks/poll-email handler that allows an
     * external scheduler
     * (e.g. Google Cloud Scheduler) to trigger the Gmail polling job; protected by
     * a
     * shared-secret token check.
     * Dependencies: SecretConfig, EventScheduler.runEmailPollingJob,
     * org.springframework.http.ResponseEntity
     * Called by: External HTTP scheduler (e.g. Cloud Scheduler cron job) via POST
     * /internal/tasks/poll-email
     */
    @PostMapping("/internal/tasks/poll-email")
    public ResponseEntity<?> runEmailPollingTask(
            @RequestHeader(value = "X-Task-Token", required = false) String taskTokenHeader) {

        String expectedToken = SecretConfig.get("TASK_TOKEN");
        if (expectedToken == null || expectedToken.isBlank()) {
            expectedToken = System.getenv("TASK_TOKEN");
        }

        if (expectedToken == null || expectedToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "TASK_TOKEN is not configured in app-secrets.json or env"));
        }

        if (!expectedToken.equals(taskTokenHeader)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized task trigger"));
        }

        eventScheduler.runEmailPollingJob();
        return ResponseEntity.ok(Map.of("status", "ok", "job", "poll-email"));
    }
}

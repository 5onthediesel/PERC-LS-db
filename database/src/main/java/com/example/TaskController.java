package com.example;

import java.util.Map;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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

    @EventListener(ApplicationReadyEvent.class)
    public void pollOnStartup() {
        logger.info("Application started; running one email polling pass.");
        try {
            eventScheduler.runEmailPollingJob();
        } catch (Exception e) {
            logger.severe("Startup email polling failed: " + e.getMessage());
        }
    }

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

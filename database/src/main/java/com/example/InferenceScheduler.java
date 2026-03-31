package com.example;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InferenceScheduler {

    private final ImgInference imgInference;

    public InferenceScheduler(ImgInference imgInference) {
        this.imgInference = imgInference;
    }

    /*
     * Runs every Sunday at 2:00 AM.
     * Pulls 16 unprocessed images from DB, sends to Python inference server,
     * writes elk counts back to DB.
     * Amount of images pulled can be changed in FileProcessor.java: [private static final int DEFAULT_BATCH_SIZE = 16]
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void runWeeklyInferenceBatch() {
        System.out.println("[Scheduler] Starting weekly inference batch...");
        FileProcessor.BatchResult result =
            FileProcessor.processUnprocessedBatchWithInference(imgInference);
        System.out.println("[Scheduler] Done. attempted=" + result.attempted +
            ", processed=" + result.processed);
        if (!result.errors.isEmpty()) {
            for (String error : result.errors) {
                System.err.println("[Scheduler] Error: " + error);
            }
        }
    }
}
package com.example;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EventScheduler {

    /*
     * Runs every Sunday at 2:00 AM.
     * Pulls 16 unprocessed images from DB, sends to Python inference server,
     * writes elk counts back to DB.
     * Amount of images pulled can be changed in FileProcessor.java: [private static
     * final int DEFAULT_BATCH_SIZE = 16]
     */
    @Scheduled(cron = "0 0 2 * * SUN")
    public void runWeeklyInferenceBatch() {
        System.out.println("[Scheduler] Starting weekly inference batch...");
        FileProcessor.BatchResult result = FileProcessor.processAllUnprocessedWithAnimalDetect();
        System.out.println("[Scheduler] Done. attempted=" + result.attempted +
                ", processed=" + result.processed);
        if (!result.errors.isEmpty()) {
            for (String error : result.errors) {
                System.err.println("[Scheduler] Error: " + error);
            }
        }
    }

    /*
     * Polls Gmail inbox every hour for new image submissions from landowners.
     * TODO: Replace with SendGrid Inbound Parse webhook when PERC provides a
     * domain.
     */
    @Scheduled(fixedDelay = 3600000)
    public void pollEmailInbox() {
        EmailProcessor.pollAndProcess();
    }
}
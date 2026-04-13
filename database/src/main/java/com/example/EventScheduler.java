package com.example;

import org.springframework.stereotype.Component;

@Component
public class EventScheduler {

    /*
     * Runs every Sunday at 2:00 AM.
     * Pulls all unprocessed images from DB, runs AnimalDetect labeling,
     * and writes elk counts back to DB.
     */
    // @Scheduled(cron = "0 0 2 * * SUN")
    // public void runWeeklyInferenceBatch() { ... }

    /**
     * Inputs:      None
     * Outputs:     void — delegates entirely to EmailProcessor.pollAndProcess
     * Functionality: Entry point for the hourly Gmail polling job; intended to be triggered by a
     *               Cloud Scheduler HTTP call to /internal/tasks/poll-email or on application startup.
     * Dependencies: EmailProcessor.pollAndProcess
     * Called by:   TaskController.pollOnStartup (on startup event),
     *              TaskController.runEmailPollingTask (via HTTP POST /internal/tasks/poll-email)
     */
    public void runEmailPollingJob() {
        EmailProcessor.pollAndProcess();
    }
}

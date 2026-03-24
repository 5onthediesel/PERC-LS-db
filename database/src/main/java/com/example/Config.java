package com.example;

public class Config {
    // Messaging mode: "local" (default) | "twilio" | "telegram"
    public static final String MESSAGING_MODE = "twilio";

    public static final String DEFAULT_PHONE_NUMBER = "+19432600741";

    public static final String GCS_BUCKET = "cs370perc-bucket";

    // TWILIO CREDENTIALS
    public static final String TWILIO_ACCOUNT_SID = System.getenv("TWILIO_ACCOUNT_SID");
    public static final String TWILIO_AUTH_TOKEN  = System.getenv("TWILIO_AUTH_TOKEN");
    public static final String TWILIO_PHONE_NUMBER = System.getenv("TWILIO_PHONE_NUMBER");
}
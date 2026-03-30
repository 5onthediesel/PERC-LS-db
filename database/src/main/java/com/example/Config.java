package com.example;

public class Config {
    // Messaging mode: "local" (default) | "twilio" | "telegram"
    public static final String MESSAGING_MODE = SecretConfig.getRequired("MESSAGING_MODE");

    public static final String DEFAULT_PHONE_NUMBER = SecretConfig.getRequired("DEFAULT_PHONE_NUMBER");

    public static final String GCS_BUCKET = SecretConfig.getRequired("GCS_BUCKET_NAME");

    // TWILIO CREDENTIALS
    public static final String TWILIO_ACCOUNT_SID = SecretConfig.getRequired("TWILIO_ACCOUNT_SID");
    public static final String TWILIO_AUTH_TOKEN = SecretConfig.getRequired("TWILIO_AUTH_TOKEN");
    public static final String TWILIO_PHONE_NUMBER = SecretConfig.getRequired("TWILIO_PHONE_NUMBER");
}
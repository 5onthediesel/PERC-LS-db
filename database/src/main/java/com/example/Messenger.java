package com.example;

public class Messenger {

    // Single entry point used by controllers and pipeline
    public static void sendReply(String toPhone, String messageText) {
        if (toPhone == null || toPhone.isBlank())
            toPhone = Config.DEFAULT_PHONE_NUMBER;

        String mode = (Config.MESSAGING_MODE == null) ? "local" : Config.MESSAGING_MODE;

        if ("local".equalsIgnoreCase(mode)) {
            System.out.println("[LOCAL MESSAGE] To: " + toPhone + "\n" + messageText);
            return;
        }

        if ("twilio".equalsIgnoreCase(mode)) {
            // TWILIO integration placeholder: uncomment and add Twilio SDK in pom.xml when ready.
            /*
            import com.twilio.Twilio;
            import com.twilio.rest.api.v2010.account.Message;
            import com.twilio.type.PhoneNumber;

            Twilio.init(Config.TWILIO_ACCOUNT_SID, Config.TWILIO_AUTH_TOKEN);
            Message.creator(new PhoneNumber(toPhone), new PhoneNumber(Config.TWILIO_PHONE_NUMBER), messageText).create();
            */
            System.out.println("[TWILIO MODE - NOT ACTIVE] To: " + toPhone + "\n" + messageText);
            return;
        }

        if ("telegram".equalsIgnoreCase(mode)) {
            // TELEGRAM integration placeholder: implement bot API call here when enabled.
            System.out.println("[TELEGRAM MODE - NOT ACTIVE] To: " + toPhone + "\n" + messageText);
            return;
        }

        System.out.println("[UNKNOWN MODE: " + mode + "] To: " + toPhone + "\n" + messageText);
    }
}

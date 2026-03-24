package com.example;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

public class Messenger {

    public static void sendReply(String toPhone, String messageText) {
        if (toPhone == null || toPhone.isBlank())
            toPhone = Config.DEFAULT_PHONE_NUMBER;

        String mode = (Config.MESSAGING_MODE == null) ? "local" : Config.MESSAGING_MODE;

        if ("local".equalsIgnoreCase(mode)) {
            System.out.println("[LOCAL MESSAGE] To: " + toPhone + "\n" + messageText);
            return;
        }

        if ("twilio".equalsIgnoreCase(mode)) {
            Twilio.init(Config.TWILIO_ACCOUNT_SID, Config.TWILIO_AUTH_TOKEN);
            Message.creator(
                new PhoneNumber(toPhone),
                new PhoneNumber(Config.TWILIO_PHONE_NUMBER),
                messageText
            ).create();
            return;
        }

        if ("telegram".equalsIgnoreCase(mode)) {
            System.out.println("[TELEGRAM MODE - NOT ACTIVE] To: " + toPhone + "\n" + messageText);
            return;
        }

        System.out.println("[UNKNOWN MODE: " + mode + "] To: " + toPhone + "\n" + messageText);
    }
}
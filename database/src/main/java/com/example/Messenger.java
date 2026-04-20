package com.example;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;

public class Messenger {

    /**
     * Inputs:      toPhone (String) — destination phone number (E.164 format);
     *              messageText (String) — message body to send
     * Outputs:     void — sends or logs the message depending on MESSAGING_MODE
     * Functionality: Routes an outbound message to the appropriate channel based on the MESSAGING_MODE
     *               config value: "local" prints to stdout, "twilio" sends an SMS via the Twilio API,
     *               "telegram" logs a stub, and any other mode logs an unknown-mode warning.
     *               Falls back to Config.DEFAULT_PHONE_NUMBER if toPhone is null or blank.
     * Dependencies: Config (MESSAGING_MODE, DEFAULT_PHONE_NUMBER, TWILIO_ACCOUNT_SID,
     *               TWILIO_AUTH_TOKEN, TWILIO_PHONE_NUMBER),
     *               com.twilio.Twilio, com.twilio.rest.api.v2010.account.Message,
     *               com.twilio.type.PhoneNumber
     * Called by:   MessagingController.sendImageTest, MessagingController.smsWebhook
     */
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

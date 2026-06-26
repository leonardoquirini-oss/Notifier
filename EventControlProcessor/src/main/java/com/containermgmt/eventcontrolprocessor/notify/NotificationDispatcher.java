package com.containermgmt.eventcontrolprocessor.notify;

import com.containermgmt.eventcontrolprocessor.engine.Channel;
import com.containermgmt.eventcontrolprocessor.engine.Situation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fans a situation out to every channel it declares (BERLink in-app, WhatsApp, Email).
 * Each channel failure is isolated so one broken channel does not block the others.
 */
@Service
@Slf4j
public class NotificationDispatcher {

    private final NotificationClient berlinkClient;
    private final WhatsAppNotifier whatsAppNotifier;
    private final EmailNotifier emailNotifier;

    public NotificationDispatcher(NotificationClient berlinkClient,
                                  WhatsAppNotifier whatsAppNotifier,
                                  EmailNotifier emailNotifier) {
        this.berlinkClient = berlinkClient;
        this.whatsAppNotifier = whatsAppNotifier;
        this.emailNotifier = emailNotifier;
    }

    public void dispatch(Situation s) {
        List<Channel> channels = s.getChannels();
        if (channels == null || channels.isEmpty()) {
            log.warn("Situation {}/{} has no channels configured; not notifying",
                    s.getRuleCode(), s.getDedupKey());
            return;
        }
        for (Channel channel : channels) {
            try {
                switch (channel) {
                    case BERLINK -> berlinkClient.send(
                            s.getGroupCode(), s.getNotificationType(), s.getTitle(), s.getMessage(), null);
                    case WHATSAPP -> whatsAppNotifier.notifyGroup(
                            s.getGroupCode(), s.getTitle() + "\n" + s.getMessage());
                    case EMAIL -> emailNotifier.send(
                            s.getEmailRecipients(), s.getTitle(), s.getMessage());
                }
            } catch (Exception e) {
                log.error("Dispatch failed on channel {} for {}/{}: {}",
                        channel, s.getRuleCode(), s.getDedupKey(), e.getMessage());
            }
        }
    }
}

package com.containermgmt.tfpgateway.service;

import com.containermgmt.tfpgateway.config.ActiveJDBCConfig;
import com.containermgmt.tfpgateway.dto.EventMessage;
import com.containermgmt.tfpgateway.exception.EventProcessingException;
import com.containermgmt.tfpgateway.handler.EventHandlerRegistry;
import com.containermgmt.tfpgateway.handler.EventTypeHandler;

import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Base;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Event Processor Service
 *
 * Handles the business logic for processing events received from Artemis.
 * 1. Upserts the raw event in evt_raw_events (idempotent on message_id)
 * 2. Dispatches to the appropriate EventTypeHandler via the registry
 */
@Service
@Slf4j
public class EventProcessorService {

    private static final String UPSERT_SQL =
            "INSERT INTO evt_raw_events (id_event, message_id, event_type, event_time, payload, checksum, processed_at) " +
            "VALUES (nextval('s_evt_raw_events'), ?, ?, ?, CAST(? AS jsonb), ?, ?) " +
            "ON CONFLICT (message_id) DO UPDATE SET " +
            "  event_type  = EXCLUDED.event_type, " +
            "  event_time  = EXCLUDED.event_time, " +
            "  payload     = EXCLUDED.payload, " +
            "  checksum    = EXCLUDED.checksum, " +
            "  processed_at = EXCLUDED.processed_at";

    private final ActiveJDBCConfig activeJDBCConfig;
    private final EventHandlerRegistry handlerRegistry;
    private final ValkeyStreamPublisher valkeyStreamPublisher;

    public EventProcessorService(ActiveJDBCConfig activeJDBCConfig,
                                 EventHandlerRegistry handlerRegistry,
                                 ValkeyStreamPublisher valkeyStreamPublisher) {
        this.activeJDBCConfig = activeJDBCConfig;
        this.handlerRegistry = handlerRegistry;
        this.valkeyStreamPublisher = valkeyStreamPublisher;
    }

    /**
     * Processes an event message: upsert raw event, then dispatch to handler.
     */
    public void processEvent(EventMessage eventMessage) {
        log.trace(" -- Processing event: type={}, messageId={}", eventMessage.getEventType(), eventMessage.getMessageId());

        try {
            activeJDBCConfig.openConnection();
            upsertRawEvent(eventMessage);
            dispatchToHandler(eventMessage);
        } catch (EventProcessingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error processing event: type={}, messageId={}, error={}",
                    eventMessage.getEventType(), eventMessage.getMessageId(), e.getMessage(), e);
            throw new EventProcessingException(
                    "Failed to process event: type=" + eventMessage.getEventType(), e);
        } finally {
            activeJDBCConfig.closeConnection();
        }

        // Fire-and-forget: publish to Valkey stream after DB commit
        valkeyStreamPublisher.publish(eventMessage);
    }

    private void upsertRawEvent(EventMessage eventMessage) {
        Timestamp eventTime = eventMessage.getEventTime() != null
                ? Timestamp.from(eventMessage.getEventTime())
                : null;
        Timestamp processedAt = Timestamp.from(Instant.now());
        String checksum = md5Hex(eventMessage.getRawPayload());

        Base.exec(UPSERT_SQL,
                eventMessage.getMessageId(),
                eventMessage.getEventType(),
                eventTime,
                eventMessage.getRawPayload(),
                checksum,
                processedAt);

        log.trace(" -- Upserted raw event: messageId={}, type={}",
                eventMessage.getMessageId(), eventMessage.getEventType());
    }

    private static String md5Hex(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private void dispatchToHandler(EventMessage eventMessage) {
        EventTypeHandler handler = handlerRegistry.getHandler(eventMessage.getEventType());
        log.trace(" -- Dispatching to handler: {} for eventType={}",
                handler.getClass().getSimpleName(), eventMessage.getEventType());
        handler.handle(eventMessage);
    }
}

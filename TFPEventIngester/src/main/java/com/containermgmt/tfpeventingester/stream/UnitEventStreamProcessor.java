package com.containermgmt.tfpeventingester.stream;

import com.containermgmt.tfpeventingester.model.EvtEventAttachment;
import com.containermgmt.tfpeventingester.model.EvtUnitEvent;
import com.containermgmt.tfpeventingester.service.BerlinkAttachmentService;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService;
import com.containermgmt.tfpeventingester.service.BerlinkLookupService.LookupResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javalite.activejdbc.Model;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Processes messages from tfp-unit-events-stream and persists
 * them to the evt_unit_events table via ActiveJDBC.
 */
@Component
@ConditionalOnProperty("stream.unit-events.key")
@Slf4j
public class UnitEventStreamProcessor extends AbstractStreamProcessor {

    private record AttachmentUploadContext(EvtEventAttachment model, String base64Content) {}

    private List<AttachmentUploadContext> pendingAttachments = new ArrayList<>();
    private final BerlinkAttachmentService berlinkAttachmentService;

    public UnitEventStreamProcessor(ObjectMapper objectMapper,
                                     BerlinkLookupService berlinkLookupService,
                                     BerlinkAttachmentService berlinkAttachmentService,
                                     @Value("${stream.unit-events.key}") String streamKey,
                                     @Value("${stream.unit-events.consumer-group}") String consumerGroup) {
        super(objectMapper, berlinkLookupService, streamKey, consumerGroup);
        this.berlinkAttachmentService = berlinkAttachmentService;
    }

    @Override
    protected Model buildModel(String messageId, String eventType, Map<String, Object> payload) {
        // Not used — buildModels() is overridden instead
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected List<Model> buildModels(String messageId, String eventType, Map<String, Object> payload) {
        EvtUnitEvent event = new EvtUnitEvent();
        event.set("message_id", messageId);
        event.set("message_type", eventType);
        event.set("id", getString(payload, "id"));
        event.set("type", getString(payload, "type"));
        event.set("event_time", parseTimestamp(payload, "eventTime"));
        event.set("create_time", parseTimestamp(payload, "createTime"));
        event.set("latitude", parseBigDecimal(payload, "latitude"));
        event.set("longitude", parseBigDecimal(payload, "longitude"));
        event.set("unit_number", getString(payload, "unitNumber"));
        event.set("unit_type_code", getString(payload, "unitTypeCode"));
        try {
            event.set("payload", objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize payload JSON for message_id={}: {}", messageId, e.getMessage());
        }

        List<Model> models = new ArrayList<>();
        models.add(event);

        pendingAttachments = new ArrayList<>();
        List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) payload.get("attachments");
        if (attachments != null) {
            for (Map<String, Object> att : attachments) {
                EvtEventAttachment a = new EvtEventAttachment();
                a.set("path", getString(att, "fileName"));
                a.set("filename", getString(att, "fileName"));
                String fileContent = getString(att, "fileContent");
                pendingAttachments.add(new AttachmentUploadContext(a, fileContent));
                models.add(a);
            }
        }

        return models;
    }

    @Override
    protected void saveModels(List<Model> models, LookupResult lookup) {
        Model parent = models.get(0); // EvtUnitEvent is always first
        if (lookup.hasData()) {
            parent.set("container_number", lookup.containerNumber());
            parent.set("id_trailer", lookup.idTrailer());
            parent.set("id_vehicle", lookup.idVehicle());
        }
        parent.saveIt();
        Object actualId = parent.getId();
        Object eventTime = parent.get("event_time");
        log.info("Saved parent EvtUnitEvent: generated id_unit_event={}", actualId);

        int attachmentIndex = 0;
        for (int i = 1; i < models.size(); i++) {
            Model child = models.get(i);
            if (child instanceof EvtEventAttachment attachment) {
                AttachmentUploadContext ctx = pendingAttachments.get(attachmentIndex++);
                attachment.set("id_unit_event", actualId);
                attachment.set("event_time", eventTime);
                Long idDocument = null;
                String fileContent = ctx.base64Content();
                if (fileContent != null && !fileContent.isBlank()) {
                    idDocument = uploadAttachment(attachment, actualId, fileContent);
                }
                attachment.set("id_document", idDocument);
            }
            child.saveIt();
        }

        pendingAttachments = new ArrayList<>();
    }

    private Long uploadAttachment(EvtEventAttachment attachment, Object parentId, String base64Content) {
        String fileName = (String) attachment.get("filename");
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Content);
            return berlinkAttachmentService.upload(fileName, bytes, "UNIT_EVENT", parentId);
        } catch (Exception e) {
            log.warn("Failed to upload attachment fileName={}, id_unit_event={}: {}",
                    fileName, parentId, e.getMessage());
            return null;
        }
    }

    @Override
    protected boolean existsByMessageId(String messageId) {
        return EvtUnitEvent.existsByMessageId(messageId);
    }

    @Override
    protected int deleteByMessageId(String messageId) {
        EvtUnitEvent existing = EvtUnitEvent.findByMessageId(messageId);
        if (existing != null) {
            long idUnitEvent = ((Number) existing.getId()).longValue();
            List<Long> idDocuments = EvtEventAttachment.findIdDocumentsByUnitEventId(idUnitEvent);
            for (Long idDocument : idDocuments) {
                berlinkAttachmentService.delete(idDocument);
            }
            EvtEventAttachment.deleteByUnitEventId(idUnitEvent);
        }
        return EvtUnitEvent.deleteByMessageId(messageId);
    }

    @Override
    protected String processorName() {
        return "unit event";
    }
}

package com.containermgmt.tfpeventingester.service;

import com.containermgmt.tfpeventingester.config.BerlinkApiConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Uploads attachments to the BERLink document repository via multipart POST.
 */
@Component
@Slf4j
public class BerlinkAttachmentService {

    private final RestTemplate restTemplate;
    private final BerlinkApiConfig config;
    private final ObjectMapper objectMapper;

    public BerlinkAttachmentService(RestTemplate berlinkRestTemplate, BerlinkApiConfig config,
                                    ObjectMapper objectMapper) {
        this.restTemplate = berlinkRestTemplate;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * Uploads a file to the BERLink document repository.
     *
     * @param fileName   the original file name
     * @param content    raw file bytes
     * @param entityType the entity type (e.g. "ASSET_DAMAGE")
     * @param entityId   the entity primary key
     * @return the generated id_document from BERLink, or null if the upload failed
     */
    @SuppressWarnings("unchecked")
    public Long upload(String fileName, byte[] content, String entityType, Object entityId) {
        String url = config.getBaseUrl() + "/api/attachments/upload";

        ByteArrayResource fileResource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource);
        body.add("entityType", entityType);
        body.add("entityId", String.valueOf(entityId));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("Attachment upload failed for fileName={}, entityType={}, entityId={}: status={}",
                        fileName, entityType, entityId, response.getStatusCode());
                return null;
            }
            Object data = response.getBody().get("data");
            if (!(data instanceof Map)) {
                log.warn("Attachment upload response missing 'data' field for fileName={}", fileName);
                return null;
            }
            Object idAttachment = ((Map<?, ?>) data).get("id_attachment");
            if (idAttachment == null) {
                log.warn("Attachment upload response missing 'id_attachment' in data for fileName={}", fileName);
                return null;
            }
            return ((Number) idAttachment).longValue();
        } catch (Exception e) {
            log.warn("Attachment upload failed for fileName={}, entityType={}, entityId={}: {}",
                    fileName, entityType, entityId, e.getMessage());
            return null;
        }
    }

    /**
     * Deletes an attachment from the BERLink document repository.
     * Failures are logged as warnings and not rethrown, so resend flow is not interrupted.
     *
     * @param idDocument the BERLink document ID to delete
     */
    public void delete(Long idDocument) {
        String url = config.getBaseUrl() + "/api/attachments/" + idDocument;
        try {
            restTemplate.delete(url);
            log.debug("Deleted BERLink attachment id_document={}", idDocument);
        } catch (Exception e) {
            log.warn("Failed to delete BERLink attachment id_document={}: {}", idDocument, e.getMessage());
        }
    }
}

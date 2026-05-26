package com.containermgmt.tfpgateway.controller;

import com.containermgmt.tfpgateway.dto.ResendEventsRequest;
import com.containermgmt.tfpgateway.dto.ResendEventsResponse;
import com.containermgmt.tfpgateway.service.EventBrowserService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * REST API per il reinvio di eventi su Valkey stream.
 *
 * Pensata per consumer downstream (es. ingester) che hanno bisogno di farsi
 * rimandare uno o piu' eventi gia' presenti su evt_raw_events.
 *
 * Endpoint:
 *   POST /api/events/resend
 *
 * Body (JSON):
 *   {
 *     "messageIds": ["mid-1", "mid-2", ...],
 *     "force": true,
 *     "temporalOrder": true
 *   }
 *
 * - force: se true, il messaggio reinviato porta metadata "resend=true"
 *          sul Valkey stream (i consumer downstream possono bypassare la
 *          deduplica e riprocessarlo).
 * - temporalOrder: se true (default), reinvia in ordine cronologico per
 *                  event_time ASC; se false, mantiene l'ordine di input.
 */
@RestController
@RequestMapping("/api/events")
@Slf4j
public class EventResendApiController {

    private static final int MAX_RESEND_LIST = 1000;

    private final EventBrowserService eventBrowserService;

    public EventResendApiController(EventBrowserService eventBrowserService) {
        this.eventBrowserService = eventBrowserService;
    }

    @PostMapping("/resend")
    public ResponseEntity<?> resend(@RequestBody ResendEventsRequest request) {
        if (request == null || request.getMessageIds() == null || request.getMessageIds().isEmpty()) {
            return ResponseEntity.badRequest().body(
                    error("messageIds is required and must contain at least one message_id"));
        }

        // Dedup preservando ordine: anche se il service deduplica internamente,
        // controlliamo qui per validare la dimensione effettiva contro MAX_RESEND_LIST
        // (un client che invia 10000 duplicati non deve sfondare il limite).
        List<String> uniqueMids = new ArrayList<>(new LinkedHashSet<>(request.getMessageIds()));

        if (uniqueMids.size() > MAX_RESEND_LIST) {
            return ResponseEntity.badRequest().body(
                    error("Too many message ids: " + uniqueMids.size() + " (max " + MAX_RESEND_LIST + ")"));
        }

        boolean temporalOrder = request.getTemporalOrder() == null || request.getTemporalOrder();
        boolean force = request.isForce();

        log.info("REST resend requested: requested={}, force={}, temporalOrder={}",
                uniqueMids.size(), force, temporalOrder);

        ResendEventsResponse response = eventBrowserService
                .resendByMessageIdsDetailed(uniqueMids, force, temporalOrder);

        return ResponseEntity.ok(response);
    }

    private static java.util.Map<String, String> error(String message) {
        return java.util.Map.of("error", message);
    }
}

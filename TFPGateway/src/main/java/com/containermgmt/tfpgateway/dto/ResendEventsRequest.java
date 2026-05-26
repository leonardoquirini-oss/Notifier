package com.containermgmt.tfpgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body per il reinvio di eventi via REST.
 *
 * - messageIds: lista di message_id da reinviare (obbligatoria, non vuota)
 * - force: se true, marca il reinvio con metadata "resend=true" sul Valkey stream
 *          cosi' i consumer possono distinguere un evento reinviato forzatamente
 *          e bypassare eventuali deduplica lato downstream
 * - temporalOrder: se true (default) reinvia in ordine cronologico di event_time;
 *                  se false, mantiene l'ordine di input
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResendEventsRequest {

    private List<String> messageIds;

    private boolean force;

    private Boolean temporalOrder;
}

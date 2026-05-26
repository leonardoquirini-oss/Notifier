package com.containermgmt.tfpgateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response body del reinvio eventi REST.
 *
 * - requested: numero di message_id ricevuti in input (dopo dedup)
 * - resent: numero di eventi effettivamente pubblicati su Valkey stream
 * - notFound: message_id richiesti ma non presenti su evt_raw_events
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResendEventsResponse {

    private int requested;

    private int resent;

    private List<String> notFound;
}

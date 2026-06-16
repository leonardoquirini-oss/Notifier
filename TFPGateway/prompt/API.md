# TFP Gateway - API Reference

Base URL: `http://<host>:8080`

Tutti gli endpoint sono unauthenticated (servizio interno dietro VPN/rete privata).

---

## Event Resend API

Permette ai consumer downstream (es. ingester) di farsi rimandare uno o piu' eventi gia' persistiti su `evt_raw_events`, ripubblicandoli sul Valkey stream di competenza.

### POST `/api/events/resend`

Reinvia una lista di eventi identificati per `message_id`.

#### Request

`Content-Type: application/json`

```json
{
  "messageIds": ["mid-abc-123", "mid-def-456"],
  "force": true,
  "temporalOrder": true
}
```

| Field | Type | Required | Default | Descrizione |
|---|---|---|---|---|
| `messageIds` | `string[]` | si | — | Lista di `message_id` da reinviare. Duplicati vengono deduplicati lato server preservando l'ordine di input. |
| `force` | `boolean` | no | `false` | Se `true`, il messaggio reinviato viene marcato con metadata `resend=true` sul Valkey stream. I consumer downstream possono usarlo per bypassare deduplica e riprocessare l'evento. |
| `temporalOrder` | `boolean` | no | `true` | Se `true`, il reinvio rispetta l'ordine cronologico `event_time ASC`. Se `false`, mantiene l'ordine di input. |

#### Limits

- Massimo `1000` `message_id` per request (dopo dedup). Oltre la soglia: `400 Bad Request`.
- Lista vuota o `null`: `400 Bad Request`.

#### Response `200 OK`

```json
{
  "requested": 2,
  "resent": 1,
  "notFound": ["mid-def-456"]
}
```

| Field | Type | Descrizione |
|---|---|---|
| `requested` | `int` | Numero di `message_id` ricevuti in input (dopo dedup). |
| `resent` | `int` | Numero di eventi effettivamente pubblicati su Valkey stream. |
| `notFound` | `string[]` | `message_id` richiesti ma non presenti in `evt_raw_events`. |

Nota: `resent <= (requested - notFound.size())`. Se `resent < (requested - notFound.size())`, significa che alcuni eventi trovati a DB sono falliti in pubblicazione (failure loggata server-side, non propagata al client per design fire-and-forget di Valkey).

#### Response `400 Bad Request`

```json
{ "error": "messageIds is required and must contain at least one message_id" }
```
oppure
```json
{ "error": "Too many message ids: 1500 (max 1000)" }
```

#### Behaviour notes

- L'evento viene ripubblicato sullo **stream Valkey** corrispondente al suo `event_type` (mapping in `gateway.stream-mapping` di `application.yml`). Se nessun mapping esiste per il tipo, il publish viene skippato silenziosamente (log a livello `DEBUG`).
- Il payload reinviato e' il JSON originale persistito al primo upsert. Nessuna trasformazione.
- L'operazione e' **non transazionale**: in caso di failure parziali ricevi `resent < requested - notFound.size()` ma gli eventi pubblicati restano pubblicati.
- Il reinvio NON modifica `evt_raw_events` (no update di `processed_at`, no incremento di counter). Solo publish su stream.

#### Esempi curl

Reinvio singolo, force off:
```bash
curl -X POST http://localhost:8080/api/events/resend \
  -H 'Content-Type: application/json' \
  -d '{"messageIds":["ID:abc-123"]}'
```

Reinvio multiplo, force on, ordine di input:
```bash
curl -X POST http://localhost:8080/api/events/resend \
  -H 'Content-Type: application/json' \
  -d '{
    "messageIds": ["ID:abc-123", "ID:def-456", "ID:ghi-789"],
    "force": true,
    "temporalOrder": false
  }'
```

---

## Other endpoints

Per riferimento rapido, gli altri endpoint esposti dal servizio (UI + API miste). Documentazione dettagliata da aggiungere on-demand.

### UI / Browser

| Method | Path | Descrizione |
|---|---|---|
| GET | `/events` | UI di browse eventi con filtri (eventType, date range, messageId, unitNumber, ecc.). |
| POST | `/events/resend` | UI form: reinvio per `id_event` selezionati in lista. |
| POST | `/events/resend-list` | UI form: reinvio per lista di `message_id` (textarea). |
| POST | `/events/resend-all` | UI form: reinvio di tutti gli eventi che matchano un filtro. |
| POST | `/events/create` | UI AJAX: crea (upsert) un nuovo evento clonato da uno esistente. Vedi sotto. |
| GET | `/gateway` | UI configurazione runtime + status listener. |
| GET | `/statistics` | UI dashboard statistiche. |

### POST `/events/create` (UI AJAX)

Crea un nuovo evento a partire da uno esistente (clone-from-existing dalla detail modal). Esegue upsert su `evt_raw_events` (idempotente su `message_id`) e, opzionalmente, pubblica sul Valkey stream del relativo `event_type`.

`Content-Type: application/x-www-form-urlencoded`

| Param | Type | Required | Default | Descrizione |
|---|---|---|---|---|
| `messageId` | `string` | si | — | `message_id` del nuovo evento (chiave di dedup/upsert). |
| `eventType` | `string` | si | — | Ereditato dall'evento sorgente; determina lo stream Valkey. |
| `eventTime` | `string` | si | — | ISO-8601 instant (es. `2026-06-16T12:30:00.000Z`). Usato anche come `processed_at`. |
| `payload` | `string` | si | — | Payload JSON (validato; colonna JSONB). |
| `send` | `boolean` | no | `false` | Se `true` pubblica anche sul Valkey stream (SAVE & SEND). |

Response `200 OK`: `{ "message": "Event saved." }` (o `"...saved and sent to Valkey streams."`).
Response `400 Bad Request`: `{ "error": "<motivo>" }` (payload non JSON, campi mancanti, data non valida).

### Gateway lifecycle (AJAX/JSON)

| Method | Path | Descrizione |
|---|---|---|
| POST | `/gateway/start` | Avvia tutti i listener Artemis. |
| POST | `/gateway/stop` | Ferma tutti i listener Artemis. |
| POST | `/gateway/apply` | Applica nuova configurazione e riavvia. |
| GET | `/gateway/status` | Polling stato listener (JSON). |

### Statistics (JSON)

| Method | Path | Descrizione |
|---|---|---|
| GET | `/statistics/api/summary` | Riassunto generale (counter, ultimo evento, ecc.). |
| GET | `/statistics/api/events-over-time` | Serie temporale eventi (`granularity`, `lookbackHours`). |
| GET | `/statistics/api/counts-by-type` | Conteggi per `event_type` (`lookbackHours`). |
| GET | `/statistics/api/processing-lag` | Distribuzione lag `event_time` -> `processed_at`. |
| GET | `/statistics/api/arrival-gaps` | Distribuzione gap di arrivo. |
| GET | `/statistics/api/silence` | Periodi di silenzio per `event_type`. |

### Health / Backup

| Method | Path | Descrizione |
|---|---|---|
| GET | `/actuator/health` | Spring Boot Actuator. |
| GET | `/health/*` | Health custom (vedi `HealthController`). |
| POST | `/backup/*` | Operazioni di backup (vedi `BackupController`). |

---

## Versioning policy

- Non rimuovere endpoint: marcarli come `Deprecated` mantenendo backward compatibility.
- Aggiungere/rimuovere campi opzionali nelle response e' non-breaking; rimuovere campi obbligatori si'.
- Aggiornare questo file ad ogni modifica API.

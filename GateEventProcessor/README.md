# Gate Event Processor

Microservizio Spring Boot che consuma eventi gate (`GATE_IN` / `GATE_OUT`) da una coda Valkey Stream e, quando un veicolo entra nei pressi di un gate configurato **con almeno una segnalazione di danno ancora aperta**, invia una notifica BERLink al gruppo di officina competente.

Nessuna persistenza locale: il processore legge il DB BERLink in sola lettura per la verifica danni e parla con BERLink via HTTP per lookup unità e invio notifiche.

---

## Stack

- **Java 17**, **Spring Boot 3.4.x**
- **Valkey Streams** (consumer group) via Spring Data Redis + Lettuce
- **PostgreSQL** (sola lettura su `evt_asset_damages`) via Spring JDBC + HikariCP
- **BERLink REST API** (`/api/units/search`, `/api/vehicles/by-plate/{plate}`, `/api/notifications/send`)
- Lombok per boilerplate
- Docker (multi-stage) per il deploy

---

## Architettura

```
                       ┌──────────────────────────────┐
   Valkey Stream  ───▶ │ StreamListenerOrchestrator   │
   (tfp-unit-events)   │  - auto-discovery dei        │
                       │    StreamProcessor beans     │
                       │  - poll, ack, error log      │
                       └──────────────┬───────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────────┐
                       │ GateEventStreamProcessor     │
                       │  filter payload.type ∈       │
                       │    allowed-types             │
                       └──────────────┬───────────────┘
                                      │
       ┌──────────────────────────────┼──────────────────────────────────┐
       ▼                              ▼                                  ▼
┌──────────────┐            ┌──────────────────────┐         ┌──────────────────────────┐
│ GateMatcher  │            │ BerlinkLookupService │         │ AssetDamageRepository    │
│  haversine   │            │  HTTP → BERLink      │         │  JDBC → BERLink DB       │
│  ≤ radius?   │            │  + cache Valkey      │         │  evt_asset_damages       │
└──────┬───────┘            └──────────────────────┘         └────────────┬─────────────┘
       │                                                                  │
       │ match                                                            │ hasUnresolvedOpenDamage
       ▼                                                                  ▼
       └─────────────────────►   se entrambe true   ◄────────────────────┘
                                          │
                                          ▼
                              ┌─────────────────────────┐
                              │ NotificationClient      │
                              │  POST                   │
                              │  /api/notifications/send│
                              └─────────────────────────┘
```

### Componenti

| Componente | Responsabilità |
|------------|----------------|
| `StreamProcessor` (interface) | Strategy: `streamKey()`, `consumerGroup()`, `process(fields)` |
| `StreamListenerOrchestrator` | Auto-discovery `StreamProcessor` beans, crea consumer group, polla, ack su success, log su errore. Niente DB scrive. |
| `GateEventStreamProcessor` | Filtra per `payload.type` in `allowed-types`, orchestra lookup, gate-match, damage-check, notify |
| `GateMatcher` | Trova il gate più vicino entro il proprio `radius` (haversine) |
| `BerlinkLookupService` | Lookup unità via `/api/units/search` + fallback `/api/vehicles/by-plate`. Cache su Valkey (`unit:lookup:*`) |
| `AssetDamageRepository` | Verifica se esistono OPEN non risolti su `evt_asset_damages` |
| `NotificationClient` | POST `/api/notifications/send` (X-API-Key) — fire-and-forget |
| `HealthController` | `/api/health/live` e `/api/health/ready` (HEALTH_CONTRACT.md) |

---

## Flusso di un messaggio

Per ogni record letto dallo stream:

1. **Parse fields**: `message_id`, `event_type`, `payload` (JSON string).
2. **Type filter**: `payload.type` deve essere in `stream.gate-events.allowed-types` (case-insensitive). Altrimenti scarta + ack.
3. **BERLink lookup**: chiama `BerlinkLookupService.lookupUnit(payload.unitNumber, payload.unitTypeCode)`. Risultato loggato (`containerNumber`, `idTrailer`, `idVehicle`). Failover graceful: se BERLink è giù il flusso prosegue, il lookup ritorna empty.
4. **Geo-match**: estrae `payload.latitude` / `payload.longitude`; `GateMatcher` calcola la distanza haversine verso ogni gate e restituisce il più vicino entro `radius` (metri). Se nessun match → scarta + ack.
5. **Damage check**: `AssetDamageRepository.hasUnresolvedOpenDamage(payload.unitNumber)`. Esegue la query (vedi sotto). Se `false` → niente notifica.
6. **Notify**: `NotificationClient.send(...)` chiama `POST /api/notifications/send` sul backend BERLink.

Errori durante `process()`: il messaggio **non** viene ackato, resta nel PEL Valkey per ispezione manuale (`XPENDING`).

### Logica damage-check

Una notifica parte se per quell'`asset_identifier` esiste almeno un record con:

- `status = 'OPEN'`
- e (`tfp_event_id IS NULL` **oppure** non esistono altri record con lo stesso `tfp_event_id` e `status IN ('REPAIRED','UNDER_REPAIR')`).

Query:

```sql
SELECT 1
FROM evt_asset_damages d
WHERE d.asset_identifier = ?
  AND d.status = 'OPEN'
  AND (
       d.tfp_event_id IS NULL
       OR NOT EXISTS (
            SELECT 1
            FROM evt_asset_damages d2
            WHERE d2.tfp_event_id = d.tfp_event_id
              AND d2.status IN ('REPAIRED', 'UNDER_REPAIR')
       )
  )
LIMIT 1
```

OPEN con `tfp_event_id IS NULL` sono considerati orfani → triggerano comunque la notifica.

### Body notifica

```json
{
  "group_code":        "<gate.notify-group>",
  "notification_type": "gate_in_with_damages",
  "title":             "<vedi sotto>",
  "message":           "<uguale a title>",
  "link":              "/gestione-danni?unit=<unitNumberSanitized>"
}
```

`title` (costruito da `buildTitle()`):

```
"Ingresso "
+ ("Unita <payload.unitNumber> "   se unitNumber presente e non vuoto)
+ ("Targa <payload.trailerPlate> " se trailerPlate presente e non vuoto)
+ "con segnalazioni aperte"
```

`link`: `unitNumber` viene sanificato rimuovendo ogni carattere non `[A-Za-z0-9]` e poi URL-encoded. Se dopo lo strip è vuoto, `link` viene omesso.

Autenticazione: header `X-API-Key` (interceptor su `berlinkRestTemplate`, scope `cd`).

Il `NotificationClient` è fire-and-forget: errori HTTP/connettività vengono loggati ma **non** propagati al consumer (così un backend irraggiungibile non blocca lo stream).

---

## Configurazione (`application.yml`)

```yaml
spring:
  application:
    name: gate-event-processor

  # PostgreSQL BERLink (sola lettura su evt_asset_damages)
  datasource:
    url: jdbc:postgresql://postgres-service:5432/berlinkdb
    username: berlink
    password: berlink
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 5
      minimum-idle: 1
      connection-timeout: 5000
      pool-name: BerlinkDB-Pool

# Valkey
valkey:
  host: valkey-service
  port: 6379
  database: 0
  password:

# Health contract (HEALTH_CONTRACT.md)
health:
  api-key: ${HEALTH_API_KEY:change-me-gate-event-processor-health-key}

# Stream consumato
stream:
  gate-events:
    key: tfp-unit-events-stream            # nome coda Valkey
    consumer-group: gate-event-processor-group-dev
    allowed-types:                          # filtro su payload.type
      - GATE_IN
      - GATE_OUT
      - GATE_IN_TRAIN
  poll-timeout-seconds: 1

# Gates abilitati: map gate-id → centro (lat/lon) + radius (m) + gruppo notifica
gates:
  gate-terni:
    latitude: 42.566593515583676
    longitude: 12.60330054635772
    radius: 500
    notify-group: officina-terni
  gate-fiorenzuola:
    latitude: 44.9226023312316
    longitude: 9.928303746032544
    radius: 500
    notify-group: officina-fiorenzuola

# BERLink API: lookup unità + invio notifiche (stessa base-url, stessa api-key)
berlink:
  api:
    base-url: http://backend:8080
    api-key: <X-API-Key con scope cd>
    connect-timeout-ms: 5000
    read-timeout-ms: 10000
    cache-enabled: true
    cache-ttl-minutes: 43200
    cache-negative-ttl-minutes: 15
```

### Property → uso

| Chiave | Componente |
|--------|-----------|
| `stream.gate-events.key` | nome stream consumato |
| `stream.gate-events.consumer-group` | consumer group Valkey |
| `stream.gate-events.allowed-types` | whitelist `payload.type` |
| `stream.poll-timeout-seconds` | poll timeout del listener |
| `gates.<id>.latitude/longitude` | centro del gate |
| `gates.<id>.radius` | raggio in metri |
| `gates.<id>.notify-group` | `group_code` BERLink destinatario |
| `berlink.api.base-url` | base URL per lookup e notifiche |
| `berlink.api.api-key` | header `X-API-Key` (scope `cd`) |
| `berlink.api.cache-*` | cache Valkey del lookup unità |

### Type fissi nel codice

- `notification_type` invariante: `"gate_in_with_damages"`.
- `link` template: `"/gestione-danni?unit=<sanitized>"`.

---

## Layout

```
GateEventProcessor/
├── pom.xml
├── Dockerfile / docker-compose.yml / Taskfile.yml
└── src/main/
    ├── java/com/containermgmt/gateeventprocessor/
    │   ├── GateEventProcessorApplication.java
    │   ├── config/
    │   │   ├── BerlinkApiConfig.java         # berlink.api props + berlinkRestTemplate
    │   │   ├── GateEventProperties.java      # stream.gate-events.*
    │   │   ├── GatesProperties.java          # gates.* map
    │   │   ├── JacksonConfig.java
    │   │   └── ValkeyConfig.java
    │   ├── controller/HealthController.java  # /api/health/live & /ready
    │   ├── repository/AssetDamageRepository.java
    │   ├── service/
    │   │   ├── BerlinkLookupService.java
    │   │   ├── GateMatcher.java
    │   │   └── NotificationClient.java
    │   └── stream/
    │       ├── StreamProcessor.java
    │       ├── StreamListenerOrchestrator.java
    │       └── GateEventStreamProcessor.java
    └── resources/application.yml
```

---

## Health endpoints

Conformi a `HEALTH_CONTRACT.md` (`gate-event-processor`):

| Endpoint | Auth | Check |
|----------|------|-------|
| `GET /api/health/live` | pubblico | sempre 200, payload minimo |
| `GET /api/health/ready` | `X-API-Key` (header `health.api-key`) | DB + Valkey. 503 se almeno una DOWN |
| `GET /api/health` | come `/ready` | alias deprecato |

---

## Build & Run

```bash
# Build + (re)start del solo processore via Taskfile
task gep      # = task stop-gep + task build-gep
task up

# Build no-cache se Docker tiene layer vecchi
docker-compose build --no-cache gateeventprocessor
docker-compose up -d gateeventprocessor

# Logs
task logs-gep   # docker-compose logs -f gateeventprocessor
```

Health probe:

```bash
curl -fsS http://localhost:8096/api/health/live
curl -fsS -H "X-API-Key: $HEALTH_API_KEY" http://localhost:8096/api/health/ready
```

---

## Test manuale

Pubblica un messaggio sullo stream configurato:

```bash
redis-cli XADD tfp-unit-events-stream "*" \
  message_id  "gate-test-001" \
  event_type  "BERNARDINI_UNIT_EVENTS" \
  payload     '{"type":"GATE_IN","unitNumber":"GBTU0281810","unitTypeCode":"CONTAINER","trailerPlate":"AB123CD","latitude":42.5666,"longitude":12.6033}'
```

Comportamento atteso:

- `type=GATE_IN` ∈ `allowed-types` → si procede.
- Lookup BERLink → log con `containerNumber/idTrailer/idVehicle`.
- Coordinate ≈ `gate-terni` → match con distanza in metri loggata.
- Se `evt_asset_damages` ha un OPEN non risolto per `asset_identifier='GBTU0281810'` → POST a `/api/notifications/send` con `group_code=officina-terni`, altrimenti log "No unresolved OPEN damage…" e niente notifica.

Verifica nel log:

```
docker-compose logs -f gateeventprocessor
```

---

## Aggiungere un nuovo gate

Una sola voce in `application.yml`:

```yaml
gates:
  gate-nuovo:
    latitude: ...
    longitude: ...
    radius: 500
    notify-group: officina-nuovo
```

Restart del container, niente codice da toccare.

## Aggiungere un altro `type` ammesso

```yaml
stream:
  gate-events:
    allowed-types:
      - GATE_IN
      - GATE_OUT
      - NEW_TYPE
```

## Cambiare la coda consumata

```yaml
stream:
  gate-events:
    key: another-stream-name
    consumer-group: another-group
```

Il consumer group viene creato al primo avvio se non esiste.

---

## Note operative

- **Cache lookup**: chiavi Valkey `unit:lookup:<TYPE>:<UNITNUMBER>`. Invalidazione manuale: `redis-cli DEL unit:lookup:CONTAINER:GBTU0281810`.
- **Errori SQL** in `AssetDamageRepository`: query fallita → `false` (non notifica) + log error. Mai eccezione propagata.
- **Errori HTTP notify**: solo log, messaggio comunque ackato (lo stream non re-tenta).
- **Errori non gestiti** dentro `process()`: messaggio **non** ackato, resta nel PEL — ispezionabile via `XPENDING tfp-unit-events-stream gate-event-processor-group-dev`.
- **Sicurezza**: gli endpoint applicativi sono solo `/api/health/*`. Nessuna esposizione di dati di dominio.

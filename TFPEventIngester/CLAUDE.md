# TFP Event Ingester - Claude Code Reference

Spring Boot application che consuma eventi da Valkey Streams e li persiste su PostgreSQL usando ActiveJDBC ORM. Architettura modulare basata su Strategy pattern per supportare più stream con auto-discovery.

## Tech Stack

- **Framework**: Spring Boot 3.4.3
- **Messaging**: Valkey Streams (via Spring Data Redis + Lettuce)
- **ORM**: JavaLite ActiveJDBC 3.0
- **Database**: PostgreSQL (HikariCP connection pool)
- **Java**: 17
- **Deployment**: Docker

## Struttura Progetto

```
TFPEventIngester/
├── src/main/java/com/containermgmt/tfpeventingester/
│   ├── TfpEventIngesterApplication.java
│   ├── config/
│   │   ├── ActiveJDBCConfig.java          # DataSource injection + ActiveJDBC lifecycle
│   │   ├── ValkeyConfig.java              # Lettuce connection factory
│   │   ├── JacksonConfig.java             # ObjectMapper config
│   │   └── BerlinkApiConfig.java          # RestTemplate + BERLink API config
│   ├── service/
│   │   ├── BerlinkLookupService.java      # Lookup container/trailer/vehicle via BERLink API
│   │   └── BerlinkAttachmentService.java  # Upload/delete allegati su BERLink document repository
│   ├── stream/
│   │   ├── StreamProcessor.java           # Strategy interface
│   │   ├── AbstractStreamProcessor.java   # Base class con helper comuni
│   │   ├── StreamListenerOrchestrator.java # Auto-discovery + listener infra
│   │   ├── UnitEventStreamProcessor.java  # Impl per tfp-unit-events-stream
│   │   ├── UnitPositionStreamProcessor.java # Impl per tfp-unit-positions-stream
│   │   └── AssetDamageStreamProcessor.java # Impl per tfp-asset-damages-stream
│   └── model/
│       ├── EvtUnitEvent.java              # ActiveJDBC model → evt_unit_events
│       ├── EvtUnitPosition.java           # ActiveJDBC model → evt_unit_positions
│       ├── EvtAssetDamage.java            # ActiveJDBC model → evt_asset_damages
│       ├── EvtVehicleDamageLabel.java     # ActiveJDBC model → evt_vehicle_damage_labels
│       ├── EvtUnitDamageLabel.java        # ActiveJDBC model → evt_unit_damage_labels
│       ├── EvtDamageAttachment.java       # ActiveJDBC model → evt_damage_attachment
│       └── EvtErrorIngestion.java         # ActiveJDBC model → evt_error_ingestion
├── src/main/resources/
│   ├── application.yml
│   └── db/
│       ├── 01_evt_unit_events.sql
│       ├── 02_evt_unit_positions.sql
│       ├── 03_evt_asset_damages.sql
│       └── 04_evt_error_ingestion.sql
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## Architettura

```
Valkey Streams                      TFPEventIngester
┌──────────────────────────────┐    ┌─────────────────────────────────┐
│ tfp-unit-events-stream       │──> │ StreamListenerOrchestrator      │
│ tfp-unit-positions-stream    │──> │   auto-discovers StreamProcessor│
│ tfp-asset-damages-stream     │──> │   beans via component scan      │
└──────────────────────────────┘    └──────────┬──────────────────────┘
                                               │
              ┌────────────────────────────────┬┴──────────────────────┐
              │                                │                       │
   ┌──────────▼──────────────┐  ┌──────────────▼────────────────┐  ┌──▼──────────────────────────┐
   │ UnitEventStreamProcessor│  │ UnitPositionStreamProcessor   │  │ AssetDamageStreamProcessor  │
   │  → EvtUnitEvent         │  │  → EvtUnitPosition            │  │  → EvtAssetDamage           │
   │  dedup by message_id    │  │  dedup by message_id          │  │  → EvtVehicleDamageLabel    │
   └──────────┬──────────────┘  └──────────────┬────────────────┘  │  → EvtUnitDamageLabel       │
              │                                │                   │  dedup by message_id        │
   ┌──────────▼──────────────┐  ┌──────────────▼────────────────┐  └──┬──────────────────────────┘
   │ PostgreSQL:             │  │ PostgreSQL:                   │     │
   │ evt_unit_events         │  │ evt_unit_positions            │  ┌──▼──────────────────────────┐
   └─────────────────────────┘  │ (partitioned by              │  │ PostgreSQL:                 │
                                │  position_time)              │  │ evt_asset_damages           │
                                └───────────────────────────────┘  │ evt_vehicle_damage_labels   │
                                                                   │ evt_unit_damage_labels      │
                                                                   └─────────────────────────────┘

                                    ┌─────────────────────────────────┐
                                    │ StreamListenerOrchestrator      │
                                    │  on error → EvtErrorIngestion   │──> PostgreSQL: evt_error_ingestion
                                    │  on resend success → cleanup    │
                                    └─────────────────────────────────┘
```

- **StreamProcessor**: Strategy interface con `streamKey()`, `consumerGroup()`, `process(fields)`
- **AbstractStreamProcessor**: Template Method base class. Il metodo `process()` (final) gestisce: validazione message_id, dedup/resend, parsing JSON, chiamata a `buildModel()`, BERLink lookup + enrichment, save. Su resend riuscito, cancella i record da `evt_error_ingestion` per quel `message_id`. I subclass implementano solo `buildModel()`, `existsByMessageId()`, `deleteByMessageId()`, `processorName()`. Include helper comuni (`getString`, `parseTimestamp`, `parseBigDecimal`, `parseResendFlag`, `getBoolean`, `getInteger`, `getLong`). Stream key e consumer group sono iniettati nel costruttore via `@Value`. Hook methods `getUnitNumberFromPayload()` e `getUnitTypeCodeFromPayload()` per customizzare i campi passati al BERLink lookup (default: `unitNumber`/`unitTypeCode`).
- **StreamListenerOrchestrator**: Inietta `List<StreamProcessor>` e `DataSource` (HikariCP pool), crea consumer group e listener per ciascuno. Per ogni messaggio: `Base.open(dataSource)` prende una connessione dal pool, `Base.close()` la restituisce. Poll timeout configurabile via `stream.poll-timeout-seconds`. I messaggi falliti restano nel PEL (non vengono acknowledged su errore). Su errore, salva un record in `evt_error_ingestion` con `message_id`, timestamp e messaggio d'errore (troncato a 4000 char). Il salvataggio errore e' wrappato in try-catch per non mascherare l'eccezione originale.
- **UnitEventStreamProcessor**: Implementa `buildModel()` per mappare payload su `EvtUnitEvent`. Salva anche il JSON raw del payload nella colonna `payload` (JSONB). Stream key da `stream.unit-events.key`. Dopo il save fa l'upsert su `evt_unit_last_position` via `LastPositionUpserter` (vedi sotto).
- **UnitPositionStreamProcessor**: Override di `buildModels()` per produrre un `EvtUnitPosition` per ogni elemento di `unitPositions[]` (campo `pos_index` 1..n). Stream key da `stream.unit-positions.key`. Override di `saveModels()`: salva le posizioni e fa l'upsert su `evt_unit_last_position` (via `LastPositionUpserter`) per ognuna, tutto in una singola transazione. Per le posizioni `event_time` = `position_time`; `id_unit_event`, `terminal_code`, `full_empty`, `operator_code`, `event_type`, `eta` sono null (non presenti nel payload positions).
- **AssetDamageStreamProcessor**: Consuma `tfp-asset-damages-stream` (stream key da `stream.asset-damages.key`). Override di `buildModels()` per produrre `EvtAssetDamage` + label model (`EvtVehicleDamageLabel` o `EvtUnitDamageLabel` a seconda di `assetType`). Override di `getUnitNumberFromPayload()` → `assetIdentifier` e `getUnitTypeCodeFromPayload()` → mappa `UNIT` → `CONTAINER`. Cascade delete su resend: chiama `DELETE /api/attachments/{id}` su BERLink per ogni allegato con `id_document` non-null, poi cancella i record `evt_damage_attachment`, label associate e infine il record principale. Allegati con `id_document = null` (upload precedente fallito) vengono ignorati silenziosamente. I tag in `assetDamageLabels[]` vengono pivotati in colonne booleane sulla tabella label appropriata.

## Allegati: colonna `tfp_attachment_id`

Entrambe le tabelle allegato conservano l'id dell'allegato lato TFP:

| Tabella | Popolata da | Campo del payload |
|---------|-------------|-------------------|
| `evt_event_attachments` | `UnitEventStreamProcessor` | `attachments[i].id` |
| `evt_damage_attachment` | `AssetDamageStreamProcessor` | `assetDamageAttachments[i].id` |

Due proprieta' controintuitive, entrambe verificate sui dati:

- **E' spesso NULL.** TFP invia `"id": null` per una quota rilevante degli allegati (a inizio 2026-07: 1193 elementi su 2195 sugli unit event, 949 su 2078 sui damage). Non e' un difetto di ingestion e nessun backfill puo' recuperarlo: il dato non arriva proprio.
- **Non e' univoco, e non va vincolato.** TFP rimanda lo stesso danno come messaggi distinti; ogni copia genera un `evt_asset_damages` diverso con gli stessi allegati. Percio' lo stesso `tfp_attachment_id` compare su piu' `id_asset_damage` (es. l'id `3325` su tre damage). Nessun UNIQUE, nessuna assunzione di unicita' nelle query.

### `id_document`: da non confondere con `tfp_attachment_id`

`tfp_attachment_id` e' l'id nel sistema **di origine** (TFP). `id_document` e' l'id nel repository **di destinazione** (BERLink): e' letteralmente `attachments.id_attachment`, restituito da `POST /api/attachments/upload` e letto da `BerlinkAttachmentService.upload()` (campo `data.id_attachment`).

E' quindi l'id con cui si torna al file:

```
GET    /api/attachments/{id_document}/download   # scarica il file
GET    /api/attachments/{id_document}            # metadati
DELETE /api/attachments/{id_document}            # usato dal cascade delete su resend
```

Solo i path che finiscono in `/download` accettano il fallback `?token=<api-key>` al posto dell'header `X-API-Key` (link diretti da browser). Alternativa senza passare dalle tabelle allegato: `GET /api/attachments/entity/UNIT_EVENT/{id_unit_event}` (o `ASSET_DAMAGE/{id_asset_damage}`).

**`id_document` NULL ha due cause distinte e indistinguibili a posteriori:**
1. `fileContent` era null o vuoto → l'upload non e' mai partito (`UnitEventStreamProcessor:144`). E' il caso di tutti gli allegati storici pre-feature.
2. L'upload e' fallito → `uploadAttachment()` logga un warn e ritorna null, ma la riga viene salvata comunque.

Il cascade delete su resend chiama `DELETE /api/attachments/{id}` solo per gli allegati con `id_document` non-null: se l'upload era fallito, su BERLink non c'e' nulla da cancellare.

**Backfill dello storico:** `BERLink/database/migration/2.5.1_tfp_attachment_id.sql`, idempotente. La mappatura riga ↔ elemento dell'array e' **posizionale** (`row_number()` sulla PK ↔ `WITH ORDINALITY`), perche' ne' `filename` ne' `path` sono chiavi valide (sui damage esistono duplicati e path NULL). La migration verifica l'assunzione in un pre-flight e aborta se non regge. Sorgenti: `evt_unit_events.payload` per gli eventi, `evt_raw_events.payload` per i damage (`evt_asset_damages` non ha colonna `payload`). Applicare **prima** di deployare il codice: ActiveJDBC risolve le colonne a runtime.

## Tabella evt_unit_last_position

Tabella "stato corrente": **una riga per `unit_number`** (PK) con l'ultima posizione nota di ogni unit. DDL reference in `src/main/resources/db/07_evt_unit_last_position.sql` (table gia' esistente in DB; file solo documentazione).

**Alimentata da due stream**, entrambi via `LastPositionUpserter.upsert(...)`:
- `tfp-unit-events-stream` → `UnitEventStreamProcessor` (event_type `BERNARDINI_UNIT_EVENTS`): `event_time` = `eventTime`, popola anche `terminal_code`, `full_empty`, `operator_code`, `eta`, `event_type` (= `type`), `id_unit_event` (= id del record `evt_unit_events`).
- `tfp-unit-positions-stream` → `UnitPositionStreamProcessor` (event_type `BERNARDINI_UNIT_POSITIONS_MESSAGE` / `BERNARDINI_PROD_UNIT_POSITION_MESSAGE`): `event_time` = `position_time`; gli extra (`terminal_code`, `full_empty`, `operator_code`, `eta`, `event_type`, `id_unit_event`) sono null.

**`LastPositionUpserter`** (classe condivisa, package `stream/`): incapsula l'UPSERT raw SQL via `Base.exec()` (no model ActiveJDBC). Va invocato dentro una transazione ActiveJDBC aperta. No-op se `unit_number` e' null (e' la PK).

**Logica UPSERT** (identica per entrambi gli stream):
```sql
INSERT INTO evt_unit_last_position (...) VALUES (...)
ON CONFLICT (unit_number) DO UPDATE SET ...
WHERE EXCLUDED.event_time > evt_unit_last_position.event_time
```
Update **condizionale**: la riga viene aggiornata solo se l'`event_time` in arrivo e' strettamente piu' recente di quello memorizzato. Tiene quindi sempre la posizione piu' recente, a prescindere da out-of-order, da quale stream arriva, o dall'ordine degli elementi nell'array `unitPositions[]` (il processor positions fa l'upsert per ogni posizione e il WHERE lascia vincere la piu' recente).

## Configurazione

### Environment Variables

| Variable | Default | Descrizione |
|----------|---------|-------------|
| `DB_HOST` | postgres-service | Host PostgreSQL |
| `DB_PORT` | 5432 | Porta PostgreSQL |
| `DB_NAME` | berlinkdb | Nome database |
| `DB_USER` | berlink | Username database |
| `DB_PASSWORD` | berlink | Password database |
| `VALKEY_HOST` | valkey-service | Host Valkey |
| `VALKEY_PORT` | 6379 | Porta Valkey |
| `VALKEY_PASSWORD` | (vuoto) | Password Valkey |

> ⚠️ `BERLINK_API_URL` e `BERLINK_API_KEY` **non sono env var**: `berlink.api.base-url` (`http://backend:8080`) e `berlink.api.api-key` sono **hardcoded** in `application.yml` senza placeholder `${}`, quindi impostarle nell'ambiente non ha alcun effetto. La chiave viene validata dal backend contro la tabella `api_keys`. Da sistemare: esternalizzare la chiave (oggi e' un segreto committato in repo).

### Proprieta' applicative (application.yml)

| Proprieta' | Default | Descrizione |
|------------|---------|-------------|
| `berlink.api.connect-timeout-ms` | 5000 | Timeout connessione BERLink API (ms) |
| `berlink.api.read-timeout-ms` | 10000 | Timeout lettura BERLink API (ms) |
| `berlink.api.cache-enabled` | true | Abilita/disabilita cache Valkey per lookup |
| `berlink.api.cache-ttl-minutes` | 43200 | TTL cache per risultati positivi (minuti, 30 giorni) |
| `berlink.api.cache-negative-ttl-minutes` | 15 | TTL cache per risultati "not found" (minuti) |
| `stream.unit-events.key` | tfp-unit-events-stream | Stream key per unit events |
| `stream.unit-events.consumer-group` | tfp-event-ingester-group | Consumer group per unit events |
| `stream.unit-positions.key` | tfp-unit-positions-stream | Stream key per unit positions |
| `stream.unit-positions.consumer-group` | tfp-event-ingester-group | Consumer group per unit positions |
| `stream.asset-damages.key` | tfp-asset-damages-stream | Stream key per asset damages |
| `stream.asset-damages.consumer-group` | tfp-event-ingester-group | Consumer group per asset damages |
| `stream.poll-timeout-seconds` | 1 | Poll timeout del listener (secondi) |
| `spring.datasource.hikari.maximum-pool-size` | 5 | Connessioni max nel pool HikariCP |
| `spring.datasource.hikari.minimum-idle` | 2 | Connessioni idle minime nel pool |
| `spring.datasource.hikari.connection-timeout` | 5000 | Timeout per ottenere connessione dal pool (ms) |

### Stream Consumati

| Stream Key | Consumer Group | Processor | Tabella Target |
|------------|---------------|-----------|----------------|
| `tfp-unit-events-stream` | `tfp-event-ingester-group` | `UnitEventStreamProcessor` | `evt_unit_events` |
| `tfp-unit-positions-stream` | `tfp-event-ingester-group` | `UnitPositionStreamProcessor` | `evt_unit_positions` |
| `tfp-asset-damages-stream` | `tfp-event-ingester-group` | `AssetDamageStreamProcessor` | `evt_asset_damages` + `evt_vehicle_damage_labels` / `evt_unit_damage_labels` |

## Aggiungere un Nuovo Stream

1. Creare un nuovo Model ActiveJDBC per la tabella target (package `model/`) con `existsByMessageId()` e `deleteByMessageId()`
2. Creare DDL in `src/main/resources/db/`
3. Aggiungere configurazione stream in `application.yml`:
   ```yaml
   stream:
     my-new:
       key: my-new-stream
       consumer-group: tfp-event-ingester-group
   ```
4. Creare un `@Component` che estende `AbstractStreamProcessor` (package `stream/`):
   ```java
   @Component
   @Slf4j
   public class MyNewStreamProcessor extends AbstractStreamProcessor {
       public MyNewStreamProcessor(ObjectMapper objectMapper,
                                    BerlinkLookupService berlinkLookupService,
                                    @Value("${stream.my-new.key}") String streamKey,
                                    @Value("${stream.my-new.consumer-group}") String consumerGroup) {
           super(objectMapper, berlinkLookupService, streamKey, consumerGroup);
       }

       @Override
       protected Model buildModel(String messageId, String eventType, Map<String, Object> payload) {
           // Crea e popola il model ActiveJDBC
       }

       @Override
       protected boolean existsByMessageId(String messageId) { return MyModel.existsByMessageId(messageId); }
       @Override
       protected int deleteByMessageId(String messageId) { return MyModel.deleteByMessageId(messageId); }
       @Override
       protected String processorName() { return "my new"; }
   }
   ```
5. Fine. L'orchestratore lo scopre automaticamente via component scan. Dedup, resend, JSON parsing, BERLink lookup e save sono gestiti dal template method in `AbstractStreamProcessor`.

## Formato Messaggi Stream

I messaggi sullo stream Valkey hanno questi campi (pubblicati da TFPGateway):

| Campo | Descrizione |
|-------|-------------|
| `message_id` | ID univoco del messaggio Artemis |
| `event_type` | Tipo evento (es. `BERNARDINI_UNIT_EVENTS`) |
| `event_time` | Timestamp ISO-8601 dell'evento |
| `payload` | JSON con i dati specifici dell'evento |

## BERLink Lookup

Quando un evento viene processato, tutti i processor chiamano `BerlinkLookupService.lookupUnit(unitNumber, unitTypeCode)` per arricchire l'evento con `container_number`, `id_trailer` e `id_vehicle` dal backend BERLink. I campi passati al lookup sono configurabili via hook methods in `AbstractStreamProcessor` (`getUnitNumberFromPayload()`, `getUnitTypeCodeFromPayload()`). `AssetDamageStreamProcessor` usa `assetIdentifier` come unitNumber e mappa `UNIT` → `CONTAINER` per il unitTypeCode.

**`unitTypeCode` e' ignorato dalla logica** (resta nella signature solo per back-compat). Non esiste piu' nessun branch container-vs-altro: il lookup e' una **cascade** che interroga gli endpoint in sequenza e **accumula** i match in un `LookupResult` (record immutabile, `merge()` = primo-non-null vince). Un singolo identifier puo' quindi risolversi contemporaneamente come container + trailer + vehicle — caso tipico del **silos**, che a seconda di come l'operatore apre la segnalazione arriva come `assetType=UNIT` o `assetType=TRAILER` pur essendo lo stesso asset fisico.

**Cascade** (`lookupUnit()`):
1. `lookupContainer()` → `GET /api/units/search?q={formatted}&limit=1`, accetta solo `unitType="c"` → salva `cassa` in `container_number`. Il numero e' normalizzato da `formatContainerNumberForSearch()` (`GBTU0281810` → `GBTU*28181.0`, `BRND00042` → `BRND*42`).
2. `lookupViaUnitsSearch()` → `GET /api/units/search?q={raw}&limit=1&includeVehicles=true` → `unitType="t"` salva `id_trailer`, `unitType="v"` salva `id_vehicle`.
3. Fallback trailer, **solo se `id_trailer` e' ancora null** → `GET /api/trailers/search-by-plate?plate={raw}` → salva `id_trailer`.
4. Fallback vehicle, **solo se `id_vehicle` e' ancora null** → `GET /api/vehicles/by-plate/{raw}` → salva `id_vehicle`.

**Perche' serve il fallback trailer (step 3).** `UnitController.search` lato backend concatena le sorgenti in ordine fisso — container → trailer → vehicle — e si ferma appena `limit` e' saturo. Con `limit=1`, se un container matcha (la ricerca e' substring `%q%` su `cassa`), consuma l'unico slot e `flt_trailers` non viene mai interrogato; `lookupViaUnitsSearch` ritorna vuoto perche' `unitType="c"`. Senza lo step 3 il trailer resta invisibile. I silos in `flt_trailers` hanno **targa italiana reale** (`AD 24208`), non codici container: gli step 3 e 4 passano quindi `unitNumber` **raw**, non formattato; il match lato backend e' gia' case/space-insensitive.

**Perche' `search-by-plate` e non `by-plate` per i trailer:** `search-by-plate` risponde `200` con `data: null` sui miss, mentre `by-plate` risponde `404`. Il miss e' il caso comune (ogni container e ogni veicolo processato), e il 404 genererebbe una `HttpClientErrorException` + `log.warn` a ogni evento.

**Parsing risposta:** gli endpoint `by-plate` / `search-by-plate` rispondono con l'involucro `ApiResponse` — `{"success": true, "data": {...}}`. **Non c'e' nessun campo `status` di primo livello.** L'helper condiviso `fetchIdByPlate(url, idField)` verifica `success == true` ed estrae `data.<idField>` (`id_trailer` / `id_vehicle`, che sono i nomi colonna prodotti da `Model.toMap()` di ActiveJDBC).

**Gestione errori:** ogni step della cascade e' wrappato in un `safeXxxLookup()` con try-catch. Se BERLink non è raggiungibile, l'evento viene salvato senza i campi di lookup (log warn). Timeout: connect 5s, read 10s.

## Mission Resolution (colonna `evt_unit_events.mission`)

`UnitEventStreamProcessor.resolveMission()` → `MissionResolutionService.resolve(unitNumber, eventTime, transportOrderShortCode)`. Risoluzione **tutta su DB** (BERLink `evt_unit_events` + TIR `ElencoRichieste3` via TIRConnector). La chiamata diretta a TFP (`TfpMissionLookupService`, `TfpClient`) è **disabilitata** ma il codice resta in repo (non più invocato).

Il refNum si estrae da `transportOrderShortCode` (es. `id:210994+refNum:26A03044_06` → `26A03044_06`) con `extractRefNum()`.

**Algoritmo** (CURR_EVT = evento corrente, non ancora salvato a DB):
1. CURR_EVT ha `transportOrderShortCode` → `mission = refNum`. FINE.
2. Altrimenti cerca START_EVT (`event_time <= CURR.eventTime`, stesso `unit_number`, `payload->>'type'='PICKUP' AND loadStatus='FULL'` oppure `type='BEGIN_LOAD'`, più recente). Se assente → mission null. Se presente → `BG = refNum(START_EVT)`. **Se CURR_EVT.type IN ('DROP','END_UNLOAD')** (evento di chiusura) → `mission=BG` subito, senza TIR (eredita la mission dello START). Altrimenti query TIR `SELECT DataS, DaProcessare FROM ElencoRichieste3 WHERE NumRic='<BG>'`; se `DaProcessare=0` → mission null, altrimenti (null o 1) → step 3.
3. Cerca END_EVT dopo START_EVT (`type IN ('DROP','END_UNLOAD')`): `END>=CURR` → mission=BG; `END<CURR` → null (fra due missioni); nessun END → se `DataS<CURR` null, se `DataS>=CURR AND DaProcessare=1` → mission=BG.

`DataS` (= DataConsegnaEffettiva) e `DaProcessare` vengono dalla singola query TIR del passo 2 (riusati al passo 3c). TIR irraggiungibile / non configurato → `DataS`/`DaProcessare` null (degradazione graceful).

| Variabile env | Default | Descrizione |
|---------------|---------|-------------|
| `TIRCONNECTOR_API_URL` | http://172.28.234.122:9090 | Base URL TIRConnector |
| `TIRCONNECTOR_API_KEY` | default-key-change-me | X-API-Key TIRConnector |

### Cache Valkey per BERLink Lookup

I risultati di `BerlinkLookupService.lookupUnit()` vengono cachati in Valkey per evitare chiamate API ridondanti. La cache usa `RedisTemplate<String, String>` + `ObjectMapper` (bean gia' disponibili, zero dipendenze aggiuntive).

**Formato chiave:** `unit:lookup:{UNIT_NUMBER}` (normalizzato uppercase/trimmed). La chiave **non** include il unitTypeCode: la cascade e' type-agnostic, quindi lo stesso identifier produce sempre lo stesso `LookupResult` a prescindere dal tipo dichiarato nel payload.

**Valore:** JSON del record `LookupResult` → `{"containerNumber": ..., "idTrailer": ..., "idVehicle": ...}`.

**Strategia TTL:**
| Scenario | Cache? | TTL |
|----------|--------|-----|
| Lookup riuscito (hasData=true) | Si | `cache-ttl-minutes` |
| Lookup senza match (not found) | Si | `cache-negative-ttl-minutes` |
| Eccezione API (timeout, 5xx) | No | - |
| Input null/blank | No | - |

**Proprieta' configurazione:**
| Proprieta' | Default codice | Valore in `application.yml` | Descrizione |
|------------|----------------|------------------------------|-------------|
| `berlink.api.cache-enabled` | true | true | Abilita/disabilita cache lookup |
| `berlink.api.cache-ttl-minutes` | 60 | **43200** (30 giorni) | TTL per risultati positivi (minuti) |
| `berlink.api.cache-negative-ttl-minutes` | 15 | 15 | TTL per risultati "not found" (minuti) |

**Degradazione graceful:** Ogni operazione cache e' wrappata in try-catch. Se Valkey non e' raggiungibile, il lookup prosegue normalmente con chiamata API diretta (log warn).

> **Attenzione ai deploy che cambiano la logica di lookup.** Con `cache-ttl-minutes` a 30 giorni, le entry gia' in Valkey sopravvivono a lungo e contengono il risultato calcolato dalla cascade **vecchia**. Dopo una modifica a `BerlinkLookupService` la cache va invalidata, altrimenti il nuovo comportamento sembra non funzionare.

**Comandi operativi:**
```bash
# Verifica cache hit
redis-cli GET "unit:lookup:GBTU0281810"

# Verifica TTL
redis-cli TTL "unit:lookup:GBTU0281810"

# Conta chiavi cache
redis-cli KEYS "unit:lookup:*" | wc -l

# Invalida cache per una unit specifica
redis-cli DEL "unit:lookup:GBTU0281810"

# Invalida tutta la cache lookup (obbligatorio dopo un deploy che tocca la cascade)
redis-cli KEYS "unit:lookup:*" | xargs redis-cli DEL
```

## Deduplication e Error Handling

La dedup avviene tramite `message_id` nel template method `AbstractStreamProcessor.process()`:
- Indice UNIQUE su `evt_unit_events.message_id`, `evt_unit_positions.message_id` e `evt_asset_damages.message_id`
- Check `existsByMessageId()` prima di ogni insert
- Messaggi duplicati vengono acknowledged e skippati silenziosamente
- Supporto resend: se il campo `metadata.resend=true`, il record esistente viene cancellato e re-inserito

**Error handling:** I messaggi che falliscono durante il processing NON vengono acknowledged. Restano nel PEL (Pending Entries List) di Valkey per ispezione via `XPENDING` e reprocessing manuale.

**Error ingestion tracking:** Quando il processing di un messaggio fallisce, `StreamListenerOrchestrator` salva un record in `evt_error_ingestion` (model `EvtErrorIngestion`) con `message_id`, `ingestion_time` e `error_message`. Quando un messaggio viene re-inviato con `metadata.resend=true` e il save va a buon fine, `AbstractStreamProcessor` cancella i record errore correlati da `evt_error_ingestion` tramite `EvtErrorIngestion.deleteByMessageId()`.

## Comandi Utili

```bash
# Build
task ing

# Build e avvia con Docker
docker-compose up --build -d

# Logs
docker-compose logs -f tfpeventingester

# Test manuale: pubblica messaggio su stream
redis-cli XADD tfp-unit-events-stream "*" \
  message_id "test-001" \
  event_type "BERNARDINI_UNIT_EVENTS" \
  event_time "2026-02-04T10:00:00Z" \
  payload '{"id":null,"type":"DAMAGE_REPORT","latitude":44.409,"severity":"MEDIUM","eventTime":"2026-02-04T10:00:00Z","longitude":8.947,"createTime":"2026-02-04T10:00:00Z","damageType":null,"unitNumber":"TEST001","attachments":[],"reportNotes":"test","unitTypeCode":"CONTAINER"}'

# Verifica inserimento
psql -h localhost -U berlink berlinkdb -c "SELECT * FROM evt_unit_events WHERE message_id = 'test-001'"

# Test manuale: pubblica messaggio posizione su stream
redis-cli XADD tfp-unit-positions-stream "*" \
  message_id "test-pos-001" \
  event_type "BERNARDINI_UNIT_POSITIONS_MESSAGE" \
  event_time "2026-02-09T16:14:32Z" \
  payload '{"unitId":2458,"vehicleId":null,"uniqueUnit":true,"unitNumber":"GBTU0281810","unitTypeCode":"CONTAINER","vehiclePlate":null,"uniqueVehicle":false,"unitPositions":[{"id":null,"unitId":2458,"latitude":45.6791948,"longitude":9.5296944,"vehicleId":null,"createTime":"2026-02-09T16:14:32Z","positionTime":"2026-02-09T16:14:32Z"}]}'

# Verifica inserimento posizione
psql -h localhost -U berlink berlinkdb -c "SELECT * FROM evt_unit_positions WHERE message_id = 'test-pos-001'"

# Test manuale: pubblica messaggio asset damage su stream
redis-cli XADD tfp-asset-damages-stream "*" \
  message_id "test-dmg-001" \
  event_type "BERNARDINI_ASSET_DAMAGES" \
  event_time "2026-02-10T10:00:00Z" \
  payload '{"id":99001,"type":"STANDARD","status":"OPEN","assetId":123,"editTime":null,"severity":"MEDIUM","assetType":"VEHICLE","assetOwner":null,"editUserId":null,"reportTime":"2026-02-10T10:00:00Z","closingTime":null,"description":"Test damage","reportNotes":"Brake issue","closingUserId":null,"assetIdentifier":"AB123CD","assetDamageLabels":[{"tag":"DMG_BRACKING","value":"true","valueFormat":"BOOLEAN","assetDamageId":99001},{"tag":"DMG_TYRES","value":"true","valueFormat":"BOOLEAN","assetDamageId":99001},{"tag":"DMG_OTHER","value":"false","valueFormat":"BOOLEAN","assetDamageId":99001}]}'

# Verifica inserimento asset damage
psql -h localhost -U berlink berlinkdb -c "SELECT * FROM evt_asset_damages WHERE message_id = 'test-dmg-001'"
psql -h localhost -U berlink berlinkdb -c "SELECT * FROM evt_vehicle_damage_labels WHERE id_asset_damage = 99001"

# Metriche connection pool HikariCP
curl http://localhost:8080/actuator/metrics/hikaricp.connections.active
curl http://localhost:8080/actuator/metrics/hikaricp.connections.idle
curl http://localhost:8080/actuator/metrics/hikaricp.connections.max
```

## UI Web (Thymeleaf)

L'app espone una UI Thymeleaf con menu hamburger (dropdown Bootstrap `dropdown-menu-end`, in alto a destra) per navigare tra le pagine:

| Pagina | Route | Descrizione |
|--------|-------|-------------|
| Event Browser | `/events` | Browser eventi/posizioni/danni/errori (`events.html`, `EventBrowserController`) |
| Geofencing | `/geofencing` | Gestione mappe e punti geofence (`geofencing.html`, `GeofencingController`) |

### Geofencing

Pagina con mappa Leaflet (tile OpenStreetMap online) per definire piu' "mappe" (gruppi nominati di punti). Per ogni mappa si gestiscono punti `(label, lat, lon, raggio in metri)` visualizzati come marker + cerchio (raggio reale in metri). Lista punti editabile nella colonna sinistra (add/edit/remove), mappa a destra. Click sulla mappa precompila lat/lon del nuovo punto.

**Persistenza SQLite separata dal Postgres primario.** `GeofencingDbConfig` crea un `DataSource` SQLite dedicato (HikariCP, pool size 1, `PRAGMA foreign_keys=ON`) + `JdbcTemplate geofencingJdbcTemplate` — NON usa ActiveJDBC ne' il DataSource Postgres (evita conflitti col binding thread-local di `Base.open`). Schema (`geo_map`, `geo_point`) creato in `@PostConstruct`. `GeofencingService` fa il CRUD via JdbcTemplate.

| Proprieta' / env | Default | Descrizione |
|------------------|---------|-------------|
| `geofencing.db-path` / `GEOFENCING_DB_PATH` | `./data/geofencing.db` | Path file SQLite (in Docker: `/app/data/geofencing.db`, volume `geofencing-data`) |

**REST API** (`GeofencingController`, JSON): `GET/POST /geofencing/api/maps`, `PUT/DELETE /geofencing/api/maps/{id}`, `GET/POST /geofencing/api/maps/{id}/points`, `PUT/DELETE /geofencing/api/points/{id}`. Errori di validazione (lat/lon range, raggio>0, nome obbligatorio) → HTTP 400 con `{error}`.

```bash
# Ispeziona il DB SQLite
sqlite3 ./data/geofencing.db 'SELECT * FROM geo_map; SELECT * FROM geo_point;'
```

---

## For Claude Code

## Workflow
- Start complex tasks in Plan mode
- Get plan approval before implementation
- Break large changes into reviewable chunks

### When Creating New Features
1. Seguire i pattern esistenti (StreamProcessor interface + auto-discovery)
2. Usare ActiveJDBC Model per nuove tabelle
3. Cerca di mantenere le funzioni piccole: <= 100 righe di codice
4. Applica principio DRY e NON DUPLICARE CODICE
5. Implementa un mini-agile cycle: proponi -> ottieni feedback -> implementa -> review

### When Encountering a Bug or Failing Test
1. First explain possible causes step-by-step
2. Check assumptions, inputs, and relevant code paths

### When Fixing Bugs
1. Verificare i log (`docker-compose logs -f tfpeventingester`)
2. Con bug critici aggiungi log per isolare la issue
3. No Silent Failures: Do not swallow exceptions silently

### When Refactoring
1. Mantenere backward compatibility
2. Verificare che i field mapping nei processor siano allineati con la DDL

### Questions to Ask Human
- Business logic requirements non chiari
- Nuovi stream da consumare
- Mapping campi payload → colonne DB
- Requisiti di performance

---

## Keep This Updated

**When to update this file:**
- Dopo aggiunta di nuovi StreamProcessor
- Dopo aggiunta di nuove tabelle/model
- Dopo modifiche architetturali
- Quando emergono nuovi pattern

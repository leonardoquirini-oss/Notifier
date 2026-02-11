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
│   │   └── BerlinkLookupService.java      # Lookup container/trailer/vehicle via BERLink API
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
- **UnitEventStreamProcessor**: Implementa `buildModel()` per mappare payload su `EvtUnitEvent`. Stream key da `stream.unit-events.key`.
- **UnitPositionStreamProcessor**: Implementa `buildModel()` per estrarre primo elemento di `unitPositions[]` e mappare su `EvtUnitPosition`. Stream key da `stream.unit-positions.key`.
- **AssetDamageStreamProcessor**: Consuma `tfp-asset-damages-stream` (stream key da `stream.asset-damages.key`). Override di `buildModels()` per produrre `EvtAssetDamage` + label model (`EvtVehicleDamageLabel` o `EvtUnitDamageLabel` a seconda di `assetType`). Override di `getUnitNumberFromPayload()` → `assetIdentifier` e `getUnitTypeCodeFromPayload()` → mappa `UNIT` → `CONTAINER`. Cascade delete su resend (cancella label associate prima del record principale). I tag in `assetDamageLabels[]` vengono pivotati in colonne booleane sulla tabella label appropriata.

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
| `BERLINK_API_URL` | http://backend_new:8081 | URL base BERLink API |
| `BERLINK_API_KEY` | (vuoto) | API Key per autenticazione BERLink |

### Proprieta' applicative (application.yml)

| Proprieta' | Default | Descrizione |
|------------|---------|-------------|
| `berlink.api.connect-timeout-ms` | 5000 | Timeout connessione BERLink API (ms) |
| `berlink.api.read-timeout-ms` | 10000 | Timeout lettura BERLink API (ms) |
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

Quando un evento viene processato, tutti i processor chiamano `BerlinkLookupService` per arricchire l'evento con `container_number`, `id_trailer` o `id_vehicle` dal backend BERLink. I campi passati al lookup sono configurabili via hook methods in `AbstractStreamProcessor` (`getUnitNumberFromPayload()`, `getUnitTypeCodeFromPayload()`). `AssetDamageStreamProcessor` usa `assetIdentifier` come unitNumber e mappa `UNIT` → `CONTAINER` per il unitTypeCode.

**Logica:**
- `unit_type_code == "CONTAINER"` → `GET /api/units/search?q={unit_number}&limit=1` → salva `cassa` in `container_number`
- Altrimenti → `GET /api/units/search?q={unit_number}&limit=1&includeVehicles=true` → `unitType="t"` salva `id_trailer`, `unitType="v"` salva `id_vehicle`
- Fallback per non-container: `GET /api/vehicles/by-plate/{unit_number}` → salva `id_vehicle`

**Gestione errori:** Se BERLink non è raggiungibile, l'evento viene salvato senza i campi di lookup (log warn). Timeout: connect 5s, read 10s.

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

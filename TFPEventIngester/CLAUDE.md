# TFP Event Ingester - Claude Code Reference

Spring Boot application che consuma eventi da Valkey Streams e li persiste su PostgreSQL usando ActiveJDBC ORM. Architettura modulare basata su Strategy pattern per supportare più stream con auto-discovery.

## Tech Stack

- **Framework**: Spring Boot 3.1.5
- **Messaging**: Valkey Streams (via Spring Data Redis + Lettuce)
- **ORM**: JavaLite ActiveJDBC 3.0
- **Database**: PostgreSQL
- **Java**: 17
- **Deployment**: Docker

## Struttura Progetto

```
TFPEventIngester/
├── src/main/java/com/containermgmt/tfpeventingester/
│   ├── TfpEventIngesterApplication.java
│   ├── config/
│   │   ├── ActiveJDBCConfig.java          # Connessione DB ActiveJDBC
│   │   ├── ValkeyConfig.java              # Lettuce connection factory
│   │   └── JacksonConfig.java             # ObjectMapper config
│   ├── stream/
│   │   ├── StreamProcessor.java           # Strategy interface
│   │   ├── StreamListenerOrchestrator.java # Auto-discovery + listener infra
│   │   └── UnitEventStreamProcessor.java  # Impl per tfp-unit-events-stream
│   └── model/
│       └── EvtUnitEvent.java              # ActiveJDBC model → evt_unit_events
├── src/main/resources/
│   ├── application.yml
│   └── db/
│       └── 01_evt_unit_events.sql
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## Architettura

```
Valkey Streams                      TFPEventIngester
┌─────────────────────────┐         ┌─────────────────────────────────┐
│ tfp-unit-events-stream  │ ──────> │ StreamListenerOrchestrator      │
│ (future streams...)     │         │   auto-discovers StreamProcessor│
└─────────────────────────┘         │   beans via component scan      │
                                    └──────────┬──────────────────────┘
                                               │
                                    ┌──────────▼──────────────────────┐
                                    │ UnitEventStreamProcessor        │
                                    │   parses payload → EvtUnitEvent │
                                    │   dedup by message_id           │
                                    └──────────┬──────────────────────┘
                                               │
                                    ┌──────────▼──────────────────────┐
                                    │ PostgreSQL: evt_unit_events     │
                                    └─────────────────────────────────┘
```

- **StreamProcessor**: Strategy interface con `streamKey()`, `consumerGroup()`, `process(fields)`
- **StreamListenerOrchestrator**: Inietta `List<StreamProcessor>`, crea consumer group e listener per ciascuno. Gestisce connessione ActiveJDBC per-thread e acknowledge.
- **UnitEventStreamProcessor**: Prima implementazione. Legge da `tfp-unit-events-stream`, dedup via `message_id`, parsa payload JSON e persiste su `evt_unit_events`.

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

### Stream Consumati

| Stream Key | Consumer Group | Processor | Tabella Target |
|------------|---------------|-----------|----------------|
| `tfp-unit-events-stream` | `tfp-event-ingester-group` | `UnitEventStreamProcessor` | `evt_unit_events` |

## Aggiungere un Nuovo Stream

1. Creare un nuovo Model ActiveJDBC per la tabella target (package `model/`)
2. Creare DDL in `src/main/resources/db/`
3. Creare un `@Component` che implementa `StreamProcessor` (package `stream/`):
   ```java
   @Component
   @Slf4j
   public class MyNewStreamProcessor implements StreamProcessor {
       @Override
       public String streamKey() { return "my-new-stream"; }

       @Override
       public String consumerGroup() { return "tfp-event-ingester-group"; }

       @Override
       public void process(Map<String, String> fields) {
           // parsing e persistenza
       }
   }
   ```
4. Fine. L'orchestratore lo scopre automaticamente via component scan.

## Formato Messaggi Stream

I messaggi sullo stream Valkey hanno questi campi (pubblicati da TFPGateway):

| Campo | Descrizione |
|-------|-------------|
| `message_id` | ID univoco del messaggio Artemis |
| `event_type` | Tipo evento (es. `BERNARDINI_UNIT_EVENTS`) |
| `event_time` | Timestamp ISO-8601 dell'evento |
| `payload` | JSON con i dati specifici dell'evento |

## Deduplication

La dedup avviene tramite `message_id`:
- Indice UNIQUE su `evt_unit_events.message_id`
- Check `EvtUnitEvent.existsByMessageId()` prima di ogni insert
- Messaggi duplicati vengono acknowledged e skippati silenziosamente

## Comandi Utili

```bash
# Build
mvn clean package -DskipTests

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

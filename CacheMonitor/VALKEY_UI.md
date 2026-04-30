# Valkey UI - Specifica Progetto

Specifica per l'implementazione di una nuova applicazione Spring Boot dedicata al **monitoraggio di un'istanza Valkey** con focus particolare sugli **stream** (statistiche, grafici, operazioni di pulizia). Il progetto deve replicare la struttura del `TFPEventIngester` (stessi pattern Spring Boot, stesso Dockerfile/Taskfile/build script di supporto), ma SENZA dipendenze da PostgreSQL/ActiveJDBC e SENZA logica di consumo eventi: e' una webapp di sola lettura/amministrazione su Valkey.

---

## 1. Obiettivi funzionali

L'applicazione deve fornire una **web console** Thymeleaf + REST API che consenta a un operatore di:

1. **Monitorare lo stato generale dell'istanza Valkey**
   - Versione, uptime, modalita' (standalone/cluster), numero clients connessi
   - Uso memoria (used / peak / RSS), evictions, hit/miss rate
   - Numero totale di chiavi per database
   - Latenza ping verso il server

2. **Elencare e ispezionare gli stream**
   - Lista di tutti gli stream presenti (chiavi che hanno `TYPE = stream`)
   - Per ciascuno: lunghezza, first-id, last-id, primo timestamp, ultimo timestamp, memoria stimata
   - Lista dei consumer group con: nome, pending count, last-delivered-id, lag
   - Lista dei consumer per ciascun group: nome, pending count, idle time
   - Visualizzazione paginata degli ultimi N messaggi (default 100) con possibilita' di filtrare per range di ID o range temporale

3. **Visualizzare statistiche e grafici**
   - Grafico "messaggi per minuto/ora/giorno" per ogni stream (ultime 24h, 7g, 30g)
   - Grafico distribuzione per ora del giorno (heatmap settimanale)
   - Grafico crescita memoria stream nel tempo (campionato dall'app, vedi sezione 4)
   - Top 10 stream per volume / per pending / per lag

4. **Operazioni di pulizia stream**
   - Svuotamento totale di uno stream (`XTRIM ... MAXLEN 0` oppure `DEL`)
   - Svuotamento per range di ID (`XDEL` di entry specifiche, oppure `XTRIM MINID`)
   - Svuotamento per range temporale (from/to datetime → calcolo MINID derivato dal timestamp e `XTRIM MINID ~ <id>`)
   - Mantenimento ultimi N elementi (`XTRIM MAXLEN <N>`)
   - Tutte le operazioni di scrittura devono richiedere conferma esplicita lato UI (modal con riepilogo) e devono produrre un log di audit (vedi sezione 5)

5. **Operazioni di gestione consumer group**
   - Creare/cancellare consumer group (`XGROUP CREATE/DESTROY`)
   - Cancellare consumer (`XGROUP DELCONSUMER`)
   - Ack manuale di entry pending (`XACK`)
   - Reset last-delivered-id (`XGROUP SETID`)

---

## 2. Tech stack obbligatorio

- **Framework**: Spring Boot 3.4.3
- **Java**: 17
- **Templating UI**: Thymeleaf (server-side rendering, coerenza con TFPEventIngester)
- **Frontend**: HTML5 + Bootstrap 5 (CDN) + Chart.js 4 (CDN) per i grafici
- **Tabelle**: DataTables.net (CDN) o tabelle Bootstrap statiche con paginazione lato server
- **Client Valkey**: Spring Data Redis + **Lettuce** (gia' usato in TFPEventIngester, supporta tutti i comandi `XINFO`, `XRANGE`, `XTRIM`, `XDEL`, `XGROUP` necessari)
- **Build**: Maven
- **Container**: Docker multi-stage build (eclipse-temurin:17-jre-alpine come runtime)

**NON usare**: PostgreSQL, ActiveJDBC, JDBC, RestTemplate verso BERLink. La webapp e' stateless rispetto a un DB relazionale; tutto cio' che serve sta in Valkey o nella memoria del processo (vedi sezione 4 per la persistenza dei sample storici).

---

## 3. Struttura progetto

Da creare come fratello di `TFPEventIngester/`, dentro `Processors/`:

```
ValkeyUI/
├── src/main/java/com/containermgmt/valkeyui/
│   ├── ValkeyUiApplication.java
│   ├── config/
│   │   ├── ValkeyConfig.java              # Lettuce connection factory + RedisTemplate
│   │   ├── JacksonConfig.java             # ObjectMapper config (timestamp ISO-8601)
│   │   └── WebMvcConfig.java              # static resources + view controllers se serve
│   ├── controller/
│   │   ├── DashboardController.java       # GET / → render dashboard.html
│   │   ├── StreamController.java          # GET /streams, /streams/{key} (Thymeleaf views)
│   │   ├── api/
│   │   │   ├── ServerInfoApi.java         # GET /api/server/info
│   │   │   ├── StreamsApi.java            # GET /api/streams, /api/streams/{key}, /api/streams/{key}/messages
│   │   │   ├── StreamStatsApi.java        # GET /api/streams/{key}/stats?bucket=minute|hour|day&range=24h|7d|30d
│   │   │   ├── StreamAdminApi.java        # POST /api/streams/{key}/trim, /api/streams/{key}/delete-range ...
│   │   │   └── ConsumerGroupApi.java      # CRUD consumer groups
│   ├── service/
│   │   ├── ValkeyServerInfoService.java   # wrapper su INFO/CLIENT LIST/CONFIG GET
│   │   ├── StreamDiscoveryService.java    # SCAN + TYPE per scoprire gli stream
│   │   ├── StreamInspectionService.java   # XINFO STREAM/GROUPS/CONSUMERS, XRANGE
│   │   ├── StreamAdminService.java        # XTRIM, XDEL, XGROUP CREATE/DESTROY, XACK, XGROUP SETID
│   │   ├── StreamStatsService.java        # bucketing dei messaggi per timeline
│   │   └── StreamSamplerService.java      # @Scheduled task di campionamento metriche (vedi 4)
│   ├── model/
│   │   ├── ServerInfo.java                # DTO INFO server
│   │   ├── StreamSummary.java             # DTO summary di uno stream
│   │   ├── StreamMessage.java             # DTO singola entry XRANGE
│   │   ├── ConsumerGroupInfo.java
│   │   ├── ConsumerInfo.java
│   │   ├── TimeBucket.java                # bucket {timestamp, count}
│   │   └── TrimRequest.java               # body POST trim/delete (validato)
│   └── audit/
│       └── AuditLogger.java               # log strutturato di tutte le operazioni write
├── src/main/resources/
│   ├── application.yml
│   ├── static/
│   │   ├── css/app.css
│   │   └── js/
│   │       ├── dashboard.js
│   │       ├── streams.js
│   │       └── stream-detail.js
│   └── templates/
│       ├── layout.html                    # base layout Thymeleaf con menu
│       ├── dashboard.html                 # overview server + top stream
│       ├── streams.html                   # lista stream
│       ├── stream-detail.html             # dettaglio stream + grafici + messaggi
│       └── fragments/
│           ├── navbar.html
│           └── confirm-modal.html
├── Dockerfile
├── docker-compose.yml
├── pom.xml
├── Taskfile.yml
├── build-production.sh
├── README.md
└── CLAUDE.md                              # documentazione tecnica analoga a TFPEventIngester
```

---

## 4. Persistenza metriche storiche

L'applicazione e' stateless rispetto a un DB relazionale, ma per disegnare i grafici di **andamento temporale** servono campioni storici. Soluzione:

- Bean `StreamSamplerService` con `@Scheduled(fixedDelayString = "${valkeyui.sampler.interval-ms:60000}")`.
- Ogni tick: per ogni stream rilevato, salvare in Valkey una entry sullo stream `valkeyui:metrics:stream:{streamKey}` (tipo: stream Valkey stesso) con i campi:
  - `length` (long) — XLEN dello stream
  - `first_id` / `last_id` (string)
  - `pending_total` (long) — somma pending di tutti i consumer group
  - `memory_bytes` (long) — da `MEMORY USAGE <streamKey>` se disponibile
- Trim dello stream metriche con `XTRIM MAXLEN ~ 43200` (≈ 30 giorni di campioni a 1/min).
- I grafici "lunghezza nel tempo" e "memoria nel tempo" leggono questo stream interno.

I grafici "messaggi per bucket temporale" invece NON serve campionarli: si leggono direttamente i timestamp impliciti negli ID (`<ms>-<seq>`) usando `XRANGE` con range derivato dall'orizzonte richiesto, e si bucketizza in memoria. Per stream molto grandi imporre limite `count` configurabile (default 50.000 entry per chiamata) e paginare.

---

## 5. Sicurezza e audit

- **Autenticazione**: Basic Auth via Spring Security oppure semplice header `X-Admin-Token` confrontato con valore in `application.yml`. Default: una credenziale admin configurabile via env.
- **Read-only mode**: proprieta' `valkeyui.read-only=true` (default `false`) che disabilita tutti gli endpoint di scrittura — utile per ambienti di produzione dove la UI deve solo osservare.
- **Conferma operazioni distruttive**: tutti gli endpoint `POST/DELETE` su `/api/streams/{key}/...` accettano un parametro `confirm=<streamKey>` che deve combaciare con il path; se mismatch → HTTP 400.
- **Audit log**: ogni operazione di scrittura va loggata con livello INFO dal bean `AuditLogger` nel formato:
  ```
  [AUDIT] user=<principal> op=<TRIM|DELETE_RANGE|XGROUP_DESTROY|...> stream=<key> params={...} result=<OK|ERROR> duration_ms=<n>
  ```
  Inoltre va scritta una entry sullo stream Valkey interno `valkeyui:audit` con gli stessi campi, mantenuto con `XTRIM MAXLEN ~ 10000`. Una pagina `/audit` mostra le ultime N operazioni.

---

## 6. Endpoint REST (contratto)

Tutti i path sotto `/api`. Risposte JSON. Errori in formato `{ "error": "<code>", "message": "<human>" }` con HTTP status appropriato.

### Server

- `GET /api/server/info` → `ServerInfo` (versione, uptime, memoria, clients, db sizes)
- `GET /api/server/ping` → `{ "ok": true, "latencyMs": 1.3 }`
- `GET /api/server/clients` → lista client connessi (`CLIENT LIST` parsato)

### Streams

- `GET /api/streams` → lista `StreamSummary[]`. Query param `?pattern=tfp-*` per filtro `SCAN MATCH`.
- `GET /api/streams/{key}` → dettaglio (XINFO STREAM FULL parsato)
- `GET /api/streams/{key}/messages?from=<id|iso8601>&to=<id|iso8601>&limit=100&direction=desc` → entry paginate
- `GET /api/streams/{key}/groups` → consumer group + consumer
- `GET /api/streams/{key}/stats?bucket=minute|hour|day&range=24h|7d|30d` → array `TimeBucket[]`
- `GET /api/streams/{key}/timeseries?metric=length|memory&range=24h|7d|30d` → serie storiche dal sampler

### Streams - operazioni di scrittura

- `POST /api/streams/{key}/trim`
  - body: `{ "strategy": "MAXLEN" | "MINID", "value": "1000" | "1709123456789-0", "approximate": true, "confirm": "<key>" }`
- `POST /api/streams/{key}/delete-range`
  - body: `{ "fromId": "...", "toId": "...", "confirm": "<key>" }` — implementa via `XRANGE` + `XDEL` a batch
- `POST /api/streams/{key}/delete-time-range`
  - body: `{ "fromTime": "2026-04-01T00:00:00Z", "toTime": "2026-04-15T00:00:00Z", "confirm": "<key>" }`
- `DELETE /api/streams/{key}?confirm=<key>` → `DEL` totale dello stream

### Consumer groups

- `POST /api/streams/{key}/groups` body `{ "name": "...", "id": "0|$|<id>", "mkstream": false }`
- `DELETE /api/streams/{key}/groups/{group}?confirm=<group>`
- `DELETE /api/streams/{key}/groups/{group}/consumers/{consumer}`
- `POST /api/streams/{key}/groups/{group}/setid` body `{ "id": "0|$|<id>" }`
- `POST /api/streams/{key}/groups/{group}/ack` body `{ "ids": ["<id>", ...] }`

### Audit

- `GET /api/audit?limit=200` → ultime N entry dello stream `valkeyui:audit`

---

## 7. UI - viste richieste

### `/` (dashboard.html)
- Card "Server Info" (versione, uptime, memoria con barra, clients, ping)
- Tabella "Top 10 Stream per lunghezza"
- Tabella "Top 10 Stream per pending"
- Grafico aggregato "messaggi/min nelle ultime 24h" sommando tutti gli stream
- Bottone refresh manuale + auto-refresh ogni 30s (toggle)

### `/streams` (streams.html)
- Tabella DataTables con colonne: nome, lunghezza, first/last id, ultimo timestamp, # gruppi, pending totale, memoria
- Search, sort, paginazione client-side
- Click su riga → `/streams/{key}`

### `/streams/{key}` (stream-detail.html)
- Header con info principali + bottoni azione (Trim, Delete Range, Delete Time Range, Delete All) ognuno con modal di conferma
- Tab "Messaggi" — tabella ultimi N (con pulsanti "carica piu' vecchi" / "carica piu' nuovi"), JSON pretty-print espandibile
- Tab "Consumer groups" — tabella gruppi con sub-tabella consumer, azioni (destroy, setid, delete consumer)
- Tab "Statistiche" — Chart.js:
  - Bar chart "messaggi per minuto/ora/giorno" (selettore bucket + range)
  - Line chart "lunghezza nel tempo" (dal sampler)
  - Line chart "memoria nel tempo" (dal sampler)
  - Heatmap settimanale (giorni × ore) con conteggio messaggi
- Tab "Audit" filtrato per stream

### `/audit` (audit.html)
- Tabella audit log globale con filtri per operazione, stream, esito, range temporale

---

## 8. Configurazione (`application.yml`)

```yaml
spring:
  application:
    name: valkey-ui

server:
  port: 8080

valkey:
  host: ${VALKEY_HOST:valkey-service}
  port: ${VALKEY_PORT:6379}
  database: ${VALKEY_DB:0}
  password: ${VALKEY_PASSWORD:}

valkeyui:
  read-only: ${VALKEYUI_READ_ONLY:false}
  admin-token: ${VALKEYUI_ADMIN_TOKEN:change-me}
  sampler:
    enabled: ${VALKEYUI_SAMPLER_ENABLED:true}
    interval-ms: ${VALKEYUI_SAMPLER_INTERVAL_MS:60000}
    metrics-stream-prefix: valkeyui:metrics:stream:
    metrics-retention: 43200      # ≈ 30 giorni a 1/min
  audit:
    stream-key: valkeyui:audit
    retention: 10000
  query:
    max-messages-per-call: 50000   # safety limit XRANGE

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: when-authorized

logging:
  level:
    root: INFO
    com.containermgmt.valkeyui: DEBUG
    io.lettuce: INFO
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
```

---

## 9. Variabili d'ambiente principali

| Variable | Default | Descrizione |
|----------|---------|-------------|
| `VALKEY_HOST` | valkey-service | Host Valkey |
| `VALKEY_PORT` | 6379 | Porta Valkey |
| `VALKEY_DB` | 0 | Database Valkey |
| `VALKEY_PASSWORD` | (vuoto) | Password Valkey |
| `VALKEYUI_READ_ONLY` | false | Disabilita endpoint di scrittura |
| `VALKEYUI_ADMIN_TOKEN` | change-me | Token admin per Basic Auth o header |
| `VALKEYUI_SAMPLER_ENABLED` | true | Abilita campionamento metriche |
| `VALKEYUI_SAMPLER_INTERVAL_MS` | 60000 | Intervallo campionamento (ms) |

---

## 10. File di supporto da replicare 1:1 (con modifiche di nomenclatura)

### `Taskfile.yml`
Stesso pattern del TFPEventIngester ma con prefisso `vui`:
- `stop-vui`, `build-vui`, `vui` (stop+build), `logs-vui`, `up`, `restart`
- Nome servizio docker-compose: `valkey-ui`

### `Dockerfile`
Identica struttura multi-stage del TFPEventIngester. Differenze:
- Pattern JAR copiato: `valkey-ui-*.jar`
- User/group: `valkeyui`
- Porta esposta: 8080 (debug 5017 commentato)
- Health check su `/actuator/health`

### `docker-compose.yml`
Identica struttura. Differenze:
- service name: `valkey-ui`
- container_name: `valkey-ui`
- Mappatura porta: `8096:8080`
- Stessa network `berlink-network` external

### `build-production.sh`
Copia di quello del TFPEventIngester. Differenze:
- IMAGE_NAME default: `valkey-ui`
- Header banner aggiornato a "VALKEY UI - Build Production Image"
- Stesso registry default (`docker.io/leonardoquirini`)

---

## 11. `pom.xml` - dipendenze richieste

Replicare il `pom.xml` di TFPEventIngester rimuovendo:
- `spring-boot-starter-jdbc`
- `org.postgresql:postgresql`
- `org.javalite:activejdbc`
- Plugin `activejdbc-instrumentation`

Mantenendo:
- `spring-boot-starter` (base)
- `spring-boot-starter-web`
- `spring-boot-starter-thymeleaf`
- `spring-boot-starter-data-redis`
- `io.lettuce:lettuce-core`
- `jackson-databind`
- `lombok`
- `spring-boot-starter-actuator`
- `spring-boot-configuration-processor`
- `spring-boot-starter-test`

Aggiungere (per Basic Auth):
- `spring-boot-starter-security`

Coordinate Maven:
```xml
<groupId>com.containermgmt</groupId>
<artifactId>valkey-ui</artifactId>
<version>1.0.0</version>
<name>Valkey UI</name>
<description>Web Console for Valkey monitoring and stream administration</description>
```

---

## 12. Pattern implementativi da rispettare

1. **Service-Controller separation**: i controller sono thin, tutta la logica nei service.
2. **DTO immutabili** con Lombok `@Value` o `@Builder`.
3. **Funzioni piccole**: ≤ 100 righe per metodo (regola del progetto).
4. **DRY**: helper centralizzati in `StreamInspectionService` per parsing risposte `XINFO`. Un singolo punto per la conversione `id → epoch ms` (il prefisso ms-secondi degli ID Valkey).
5. **No silent failures**: ogni eccezione Lettuce viene loggata e ri-throwata come `ResponseStatusException` o gestita da un `@ControllerAdvice`.
6. **Conferma scritture**: pattern uniforme `confirm=<resourceId>` su tutti gli endpoint distruttivi, con check up-front nel controller.
7. **Audit obbligatorio** per ogni endpoint di scrittura (decoratore o aspect, non sparso nei service).
8. **Logging**: pattern stesso del TFPEventIngester (`%d{yyyy-MM-dd HH:mm:ss} - %msg%n`).

---

## 13. Comandi Valkey da usare (riferimento rapido)

| Operazione | Comando |
|-----------|---------|
| Discovery stream | `SCAN 0 MATCH * COUNT 1000` + `TYPE <key>` |
| Info server | `INFO server`, `INFO memory`, `INFO clients`, `INFO stats` |
| Lunghezza stream | `XLEN <key>` |
| Info stream completa | `XINFO STREAM <key> FULL` |
| Consumer groups | `XINFO GROUPS <key>` |
| Consumers | `XINFO CONSUMERS <key> <group>` |
| Lettura range | `XRANGE <key> <start> <end> COUNT <n>` |
| Lettura range inversa | `XREVRANGE <key> <end> <start> COUNT <n>` |
| Trim per lunghezza | `XTRIM <key> MAXLEN ~ <n>` |
| Trim per id minimo | `XTRIM <key> MINID ~ <id>` |
| Delete entry | `XDEL <key> <id> [<id> ...]` |
| Delete totale | `DEL <key>` |
| Memory di una chiave | `MEMORY USAGE <key>` |
| Crea group | `XGROUP CREATE <key> <group> <id> [MKSTREAM]` |
| Distruggi group | `XGROUP DESTROY <key> <group>` |
| Set last-delivered | `XGROUP SETID <key> <group> <id>` |
| Cancella consumer | `XGROUP DELCONSUMER <key> <group> <consumer>` |
| Ack | `XACK <key> <group> <id> [<id> ...]` |

**ID → timestamp**: gli ID Valkey hanno il formato `<ms>-<seq>`. Per convertire un `Instant` in ID lower-bound: `instant.toEpochMilli() + "-0"`. Per upper-bound inclusivo del millisecondo: `instant.toEpochMilli() + "-" + Long.MAX_VALUE`.

---

## 14. Test minimi richiesti

- **Unit test** sui parser di `XINFO` (mock `RedisTemplate`).
- **Unit test** sul converter `Instant ↔ stream ID`.
- **Integration test** con Testcontainers (`redis/redis-stack:latest` o `valkey/valkey:latest`) che copre:
  - discovery stream
  - inspection
  - trim per MAXLEN, MINID, time-range
  - audit log scritto correttamente

Skippabili durante build Docker (`-DskipTests`).

---

## 15. README.md (creare)

README sintetico in italiano, struttura:
- Cosa fa l'app (1 paragrafo)
- Quick start: `task vui && task up`
- Variabili d'ambiente (tabella)
- Endpoint principali (lista)
- Note operative: read-only mode, audit, sampler

## 16. CLAUDE.md (creare)

CLAUDE.md analogo a quello del TFPEventIngester (sezioni: Tech Stack, Struttura Progetto, Architettura, Configurazione, Endpoint, Operazioni Valkey, Pattern, Comandi Utili). Servira' come riferimento futuro per Claude Code.

---

## 17. Definition of Done

- [ ] Progetto `Processors/ValkeyUI/` compila con `mvn clean package`
- [ ] `task vui && task up` builda e avvia il container `valkey-ui` su porta `8096`
- [ ] `http://localhost:8096/` mostra la dashboard con server info popolata
- [ ] `/streams` lista almeno gli stream `tfp-*` presenti nell'istanza Valkey condivisa
- [ ] Click su uno stream apre la pagina dettaglio con i tre grafici funzionanti
- [ ] Operazione "Trim MAXLEN" eseguita da UI riduce effettivamente la lunghezza e produce una entry audit
- [ ] In modalita' `VALKEYUI_READ_ONLY=true` tutti i bottoni di scrittura sono disabilitati e gli endpoint POST/DELETE rispondono 403
- [ ] Sampler popola lo stream `valkeyui:metrics:stream:*` ogni minuto
- [ ] `build-production.sh -v 1.0.0` builda l'immagine `docker.io/leonardoquirini/valkey-ui:1.0.0`
- [ ] Nessuna dipendenza PostgreSQL/ActiveJDBC nel pom

---

## 18. Note conclusive per l'implementatore

- L'app deve essere **leggera**: niente connection pool DB, niente entity, niente migration. Solo Lettuce + un manciata di service.
- La UI deve essere **funzionale prima che bella**: Bootstrap default, niente custom CSS pesante. Coerenza visiva con il `BerlinkLogProcessor` se gia' esiste un suo template (osservare `BerlinkLogProcessor/src/main/resources/templates/dashboard.html` se serve ispirazione).
- Tutti i comandi Valkey distruttivi devono essere **idempotenti dove possibile** e sempre tracciati. Se un trim viene chiamato su uno stream gia' vuoto, restituire `{ "trimmed": 0 }` e non errore.
- Il timezone di tutti i timestamp UI e' UTC; convertire lato client se necessario. Le API ritornano sempre ISO-8601 con suffisso `Z`.
- Il Dockerfile DEVE seguire lo stesso pattern multi-stage del TFPEventIngester (Maven build + jre-alpine runtime + utente non-root + healthcheck su `/actuator/health`).



---

## For Claude Code

## Workflow
- Start complex tasks in Plan mode
- Get plan approval before implementation
- Break large changes into reviewable chunks

### When Creating New Features
1. Seguire i pattern esistenti 
2. Cerca di mantenere le funzioni piccole: <= 100 righe di codice. Se una funzione fa troppe cose, spezzala in funzioni helper piu' piccole
3. Applica principio DRY e NON DUPLICARE CODICE. Se una logica esiste in due posti, rifattorizzala in una funzione comune (o chiarisci perche' servono due implementazioni differenti se esiste un motivo valido)
4. Implementa un mini-agile cycle: proponi -> ottieni feedback -> implementa -> review

### When encountering a bug or failing test
1. First explain possible causes step-by-step. 
2. Check assumptions, inputs, and relevant code paths.

### When Fixing Bugs
1. Verificare i log 
2. Con bug critici aggiungi log per isolare la issue
3. No Silent Failures: Do not swallow exceptions silently. Always surface errors either by throwing or logging them.

### When Refactoring
1. Mantenere backward compatibility API

### When adding / updating API
1. Aggiorna il file PLANNING_MONITOR_REQS.md.md
2. Non rimuovere endpoint ma rendili deprecati (per backward compatibility)
3. For API changes, test with `curl` or Postman

### Questions to Ask Human
- Business logic requirements non chiari
- Requisiti di performance
- Considerazioni di sicurezza

---

## Main Rules

1. Don’t assume. Don’t hide confusion. Surface tradeoffs.
2. Minimum code that solves the problem. Nothing speculative.
3. Touch only what you must. Clean up only your own mess.
4. Define success criteria. Loop until verified.

---

## Keep This Updated

**When to update this file:**
- Dopo aggiunta di nuove dipendenze major
- Dopo modifiche architetturali
- Dopo cambio convenzioni di codice
- Quando emergono nuovi pattern
# Valkey UI - Claude Code Reference

Spring Boot web console per monitoraggio Valkey + amministrazione stream. Stateless rispetto a un DB relazionale: tutto cio' che serve sta in Valkey o nella memoria del processo. Pensata come "fratello" di `TFPEventIngester` ma senza dipendenze PostgreSQL/ActiveJDBC.

## Tech Stack

- **Framework**: Spring Boot 3.4.3
- **Java**: 17
- **UI**: Thymeleaf + Bootstrap 5 + Chart.js 4 + DataTables (CDN)
- **Client Valkey**: Spring Data Redis + Lettuce
- **Auth**: Spring Security Basic Auth (utente in-memory)
- **Container**: Docker multi-stage (eclipse-temurin:17-jre-alpine)

## Struttura Progetto

```
ValkeyUI/
├── src/main/java/com/containermgmt/valkeyui/
│   ├── ValkeyUiApplication.java
│   ├── config/
│   │   ├── ValkeyConfig.java          # Lettuce factory + RedisTemplate<String,String>
│   │   ├── JacksonConfig.java         # ObjectMapper ISO-8601
│   │   ├── WebMvcConfig.java          # static resources /css /js
│   │   ├── SecurityConfig.java        # Basic Auth in-memory
│   │   └── ValkeyUiProperties.java    # @ConfigurationProperties valkeyui.*
│   ├── controller/
│   │   ├── DashboardController.java   # /, /streams, /streams/{key}, /audit (Thymeleaf)
│   │   └── api/
│   │       ├── ServerInfoApi.java     # /api/server/{info,ping,clients}
│   │       ├── StreamsApi.java        # /api/streams[/{key}[/messages|/groups]]
│   │       ├── StreamStatsApi.java    # /api/streams/{key}/{stats,heatmap,timeseries}
│   │       ├── StreamAdminApi.java    # /api/streams/{key}/{trim,delete-range,...}
│   │       ├── ConsumerGroupApi.java  # /api/streams/{key}/groups...
│   │       ├── AuditApi.java          # /api/audit
│   │       └── GlobalExceptionHandler.java
│   ├── service/
│   │   ├── ValkeyServerInfoService.java
│   │   ├── StreamDiscoveryService.java   # SCAN + TYPE
│   │   ├── StreamInspectionService.java  # XINFO, XRANGE, MEMORY USAGE
│   │   ├── StreamAdminService.java       # XTRIM, XDEL, XGROUP, XACK
│   │   ├── StreamStatsService.java       # bucketing + heatmap + timeseries
│   │   ├── StreamSamplerService.java     # @Scheduled sampler
│   │   ├── AuditService.java             # read XRANGE valkeyui:audit
│   │   └── StreamIdUtils.java            # id <-> epoch ms
│   ├── model/                         # DTOs immutabili (@Value @Builder)
│   └── audit/AuditLogger.java         # log INFO + XADD valkeyui:audit
├── src/main/resources/
│   ├── application.yml
│   ├── static/{css/app.css, js/{common,dashboard,streams,stream-detail,audit}.js}
│   └── templates/{dashboard,streams,stream-detail,audit}.html + fragments/{head,navbar,scripts,confirm-modal}.html
├── Dockerfile, docker-compose.yml, Taskfile.yml, build-production.sh, pom.xml
└── README.md, CLAUDE.md
```

## Architettura

```
Browser ──(/, /streams, /streams/{key}, /audit, /api/...)──>  ValkeyUiApplication
                                                                      │
                                  ┌───────────────────────────────────┤
                                  │                                   │
                          Controllers (UI)                    Controllers (REST /api/*)
                                  │                                   │
                                  └───────────────┬───────────────────┘
                                                  │
                                          Services
                              ┌───────────┬───────┴────────┬──────────────┐
                              │           │                │              │
                  ValkeyServerInfo  StreamDiscovery  StreamInspection  StreamStats
                  (INFO/CLIENT)    (SCAN+TYPE)      (XINFO/XRANGE)   (bucketing)
                              │           │                │              │
                              └───────────┴────────────────┴──────────────┘
                                                  │
                                            Lettuce → Valkey
                                                  ▲
                                                  │
                                          StreamAdminService (XTRIM/XDEL/XGROUP/XACK)
                                                  │
                                          AuditLogger (log INFO + XADD valkeyui:audit)
                                                  │
                                          StreamSamplerService @Scheduled
                                            XADD valkeyui:metrics:stream:{key}
```

## Configurazione

Definita in `application.yml` e via env. Vedi `ValkeyUiProperties` per la mappatura tipata sotto prefisso `valkeyui.*`.

| Property | Env | Default |
|----------|-----|---------|
| `valkey.host` | `VALKEY_HOST` | `valkey-service` |
| `valkey.port` | `VALKEY_PORT` | `6379` |
| `valkey.database` | `VALKEY_DB` | `0` |
| `valkey.password` | `VALKEY_PASSWORD` | (vuoto) |
| `valkeyui.read-only` | `VALKEYUI_READ_ONLY` | `false` |
| `valkeyui.admin-username` | `VALKEYUI_ADMIN_USERNAME` | `admin` |
| `valkeyui.admin-token` | `VALKEYUI_ADMIN_TOKEN` | `change-me` |
| `valkeyui.sampler.enabled` | `VALKEYUI_SAMPLER_ENABLED` | `true` |
| `valkeyui.sampler.interval-ms` | `VALKEYUI_SAMPLER_INTERVAL_MS` | `60000` |
| `valkeyui.sampler.metrics-stream-prefix` | - | `valkeyui:metrics:stream:` |
| `valkeyui.sampler.metrics-retention` | - | `43200` |
| `valkeyui.audit.stream-key` | - | `valkeyui:audit` |
| `valkeyui.audit.retention` | - | `10000` |
| `valkeyui.query.max-messages-per-call` | - | `50000` |

## Endpoint

Vedi `README.md` per l'elenco completo.

## Operazioni Valkey usate

| Operazione | Comando |
|-----------|---------|
| Discovery | `SCAN MATCH ... COUNT 1000` + `TYPE` |
| Server info | `INFO server|memory|clients|stats|replication|keyspace`, `CLIENT LIST` |
| Stream length | `XLEN` |
| Stream info | `XINFO STREAM` |
| Consumer groups | `XINFO GROUPS`, `XINFO CONSUMERS` |
| Range read | `XRANGE` / `XREVRANGE` con limit `valkeyui.query.max-messages-per-call` |
| Trim | `XTRIM MAXLEN ~`, `XTRIM MINID ~` |
| Delete | `XDEL` (a batch da 500 ID), `DEL` per cancellazione totale |
| Memory | `MEMORY USAGE` |
| Group mgmt | `XGROUP CREATE [MKSTREAM]`, `DESTROY`, `SETID`, `DELCONSUMER` |
| Ack | `XACK` |
| Audit/sampler | `XADD` + `XTRIM MAXLEN ~` su `valkeyui:audit` e `valkeyui:metrics:stream:*` |

## Pattern

1. **Service-Controller separation**: controller thin, logica nei service.
2. **DTO immutabili** (`@Value @Builder`); request DTO con `@Data`.
3. **Funzioni piccole** ≤ 100 righe.
4. **DRY**: parsing risposte Lettuce centralizzato in `StreamInspectionService.parseFlatMap/parseListOfMaps`. Conversione id↔ms in `StreamIdUtils`.
5. **No silent failures**: errori loggati e/o ri-throwati come `ResponseStatusException`. `GlobalExceptionHandler` mappa a JSON `{ error, message }`.
6. **Conferma scritture**: ogni endpoint POST/DELETE distruttivo richiede `confirm=<key>` (o `confirm=<group>`) up-front.
7. **Audit obbligatorio** per operazioni di scrittura, gestito in `StreamAdminService` (entry per ogni operazione).
8. **Read-only enforcement**: `StreamAdminService.ensureWritable()` solleva 403 quando `valkeyui.read-only=true`.
9. **Logging**: pattern `%d{yyyy-MM-dd HH:mm:ss} - %msg%n`.

## Comandi utili

```bash
task vui                         # stop + build container
task up                          # docker-compose up -d
task logs-vui                    # follow log
mvn clean package -DskipTests    # build locale
./build-production.sh -v 1.0.0   # build + push registry
```

## Persistenza metriche storiche

Il `StreamSamplerService` (`@Scheduled fixedDelay = valkeyui.sampler.interval-ms`) campiona ogni stream rilevato e scrive una entry su `valkeyui:metrics:stream:{streamKey}` con campi `length`, `first_id`, `last_id`, `pending_total`, `memory_bytes`, `sampled_at`. Trim `XTRIM MAXLEN ~ 43200`.

Gli stream `valkeyui:audit` e `valkeyui:metrics:stream:*` sono auto-esclusi dal sampler.

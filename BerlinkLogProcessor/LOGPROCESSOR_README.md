# FlowCenter Collector

Edge agent for the FlowCenter observability platform. Tails the SQL query log written by
the Berlink application, parses each query execution, and ships raw execution points to
FlowCenter central over HTTP. It does **not** store metrics or serve a dashboard — metric
aggregation, the read API and the UI all live in FlowCenter central.

```
Log file ─▶ QueryLogFileProcessor ─▶ QueryLogParser ─▶ ExecutionBuffer ─▶ IngestClient ─┐
                                                                                        │ HTTP batch
                                                              FlowCenter central ◀───────┘
                                                                       ▲
                                                       HeartbeatClient ─┘ (status every 30s)
```

Previously this app stored metrics in Valkey and served its own dashboard. That path was
removed: Valkey is no longer a dependency of the collector.

## Key features

- **Zero application impact** — reads an existing log file, no agent/hooks in Berlink.
- **Multi-line record support** — reassembles SQL queries spanning many log lines.
- **Two log formats** — ActiveJDBC JSON payloads and a legacy pipe-delimited format.
- **Log rotation support** — detects truncation/replacement and resets position.
- **Fault tolerant** — read position persisted to a local file, restored on restart.
- **Batched ingestion** — executions buffered and POSTed in batches to central.
- **Bounded disk spool** — on central-down, batches spool to disk (size-capped) and a
  background task drains them when central recovers. Same `batch_id` reused → central dedups.
- **Heartbeat** — periodic status push so central can tell "alive but idle" from "dead".

## Tech stack

| Layer   | Technology                |
|---------|---------------------------|
| Runtime | Java 17 + Spring Boot     |
| HTTP    | `java.net.http` (HTTP/1.1)|
| Parser  | Jackson (lenient JSON)    |
| Build   | Maven                     |

## Project structure

```
src/main/java/it/berlink/
├── LogProcessorApplication.java         Spring Boot entry point
├── position/
│   └── FilePositionStore.java           read position persisted to a local file
└── monitoring/
    ├── controller/
    │   └── CollectorStatusController.java   ops endpoints (/api/collector/*)
    ├── service/
    │   └── QueryLogFileProcessor.java       tails the log file
    ├── parser/
    │   └── QueryLogParser.java              log line → ParsedLogEntry
    ├── ingest/
    │   ├── ExecutionBuffer.java             buffers + flushes batches
    │   ├── IngestClient.java                HTTP delivery + spool + retry-drain
    │   ├── DiskSpool.java                   bounded on-disk fallback
    │   ├── HeartbeatClient.java             periodic status push to central
    │   ├── ExecutionDto.java / IngestBatch.java / HeartbeatDto.java
    └── model/
        ├── ExecutionPoint.java              one query execution sample
        ├── ParseError.java                  a captured parse failure
        └── ProcessorStatus.java             log processor runtime status
```

## Configuration

`src/main/resources/application.yml` — key properties (all overridable via env vars):

| Property                              | Default                       | Meaning |
|----------------------------------------|-------------------------------|---------|
| `server.port`                          | 8082                          | HTTP port (ops endpoints) |
| `flowcenter.collector.id`              | `berlink-prod`                | this collector's id (`COLLECTOR_ID`) |
| `flowcenter.central.url`               | `http://flowcenter:8000`      | FlowCenter central base URL (`FLOWCENTER_URL`) |
| `flowcenter.api.token`                 | —                             | bearer token for central (`FLOWCENTER_TOKEN`) |
| `flowcenter.ingest.batch.size`         | 200                           | executions per batch |
| `flowcenter.ingest.flush.interval.ms`  | 5000                          | max time before a partial batch is flushed |
| `flowcenter.ingest.retry.interval.ms`  | 30000                         | spool drain retry interval |
| `flowcenter.heartbeat.interval.ms`     | 30000                         | status push interval |
| `flowcenter.spool.dir`                 | `<state>/spool`               | disk spool directory |
| `flowcenter.spool.max.bytes`           | 104857600                     | spool size cap (oldest dropped over this) |
| `flowcenter.position.file`             | `<state>/position.dat`        | read position file |
| `query.log.file.path`                  | `/berlink/logs/berlink_queries.log` | log file to tail (`QUERY_LOG_PATH`) |
| `query.monitor.poll.interval.ms`       | 1000                          | file poll interval |

`COLLECTOR_STATE_DIR` (default `/var/lib/collector`) is the base for spool + position file —
it must be a writable volume.

## Log file format

Unchanged. The parser tries the ActiveJDBC JSON format first, then the legacy pipe-delimited
format. Records may span multiple lines; the processor accumulates lines until the JSON
payload terminator or the next record start.

**ActiveJDBC JSON (primary):**
```
2026-01-25 16:55:10.891 INFO  [823552] [OperationService.getAllOperations] org.javalite.activejdbc.LazyList - {"sql":"SELECT * FROM gb_site_bulks WHERE id_site = ?","params":[4],"duration_millis":1,"cache":"miss"}
```

**Legacy pipe-delimited (fallback):**
```
2025-01-24 10:15:32.456 | SELECT * FROM users WHERE id = ? | 45ms | rows:1
```

Queries are normalized before hashing (whitespace collapsed, literals → `?`, `IN (...)` /
`VALUES (...)` collapsed, upper-cased). `query_hash` is the first 16 hex chars of the MD5 of
the normalized query.

## Ingestion contract

The collector POSTs to `${flowcenter.central.url}/api/ingest/queries` with a bearer token:

```json
{
  "collector_id": "berlink-prod",
  "batch_id": "<uuid>",
  "executions": [
    {
      "query_hash": "abc123def4567890",
      "normalized_sql": "SELECT * FROM USERS WHERE ID = ?",
      "timestamp": "2026-01-25T16:55:10.891Z",
      "duration_ms": 1,
      "row_count": 0,
      "method": "OperationService.getAllOperations"
    }
  ]
}
```

Heartbeat goes to `/api/collectors/status` with the same token.

## Ops endpoints

Base path `/api/collector` — these describe the collector itself, not query metrics.

| Endpoint | Returns |
|----------|---------|
| `GET /status` | `ProcessorStatus` — log file path, position, lines/entries/errors, running |
| `GET /parse-errors?limit=50` | recent parse failures, newest first |
| `GET /ingest` | spool state — `pendingBatches`, `droppedBatches` |
| `GET /actuator/health` | actuator health |

Query metrics, slowest/most-frequent, time-series etc. are served by FlowCenter central at
`/api/queries/*`, not here.

## Running

```bash
mvn clean package
java -jar target/log-processor-2.0.0-SNAPSHOT.jar
# or
docker-compose up -d --build
```

`docker-compose.yml` mounts the Berlink log directory read-only and a writable named volume
for collector state, and passes `FLOWCENTER_URL` / `FLOWCENTER_TOKEN` / `COLLECTOR_ID`.

## Troubleshooting

- **Processor not reading new lines** — check `query.log.file.path` is correct/readable,
  `GET /api/collector/status` (`fileExists`, `isRunning`, `currentFilePosition` advancing).
- **Lines counted but not parsed** — `parseErrors` high → format mismatch;
  `GET /api/collector/parse-errors` to inspect failing lines.
- **Data not reaching central** — `GET /api/collector/ingest`: `pendingBatches > 0` means
  central is unreachable and batches are spooling. Check `FLOWCENTER_URL`, `FLOWCENTER_TOKEN`
  (401 in logs = bad token), and central health.
- **`droppedBatches > 0`** — the disk spool hit its size cap while central was down; raise
  `flowcenter.spool.max.bytes` or restore central sooner.

## Rollback

The pre-FlowCenter (Valkey-based) build is preserved under `_rollback/` — OLD jar,
`docker-compose.yml`, `application.yml` and the source commit hash. Strategy is a clean
switch: redeploy the OLD jar + compose to revert.

# Berlink Log Processor - Query Monitor

## Overview

Real-time SQL query monitoring for the Berlink platform. The processor tails a log
file produced by application DB logging, parses query executions, computes
performance metrics (count, avg, min, max, P50, P95, P99) and stores them in
Valkey with a configurable TTL. Metrics are exposed via a REST API and an HTML
dashboard.

```
Log file ──▶ QueryLogFileProcessor ──▶ QueryLogParser ──▶ QueryMonitorService ──▶ Valkey
                                                                                    │
                                                              REST API / Dashboard ◀┘
```

### Key features

- **Zero application impact** — reads an existing log file, no agent/hooks.
- **Multi-line record support** — reassembles SQL queries that span many log lines.
- **Two log formats** — ActiveJDBC JSON payloads and a legacy pipe-delimited format.
- **Log rotation support** — detects truncation/replacement and resets position.
- **Fault tolerant** — read position persisted to Valkey, restored on restart.
- **Configurable retention** — TTL-based expiry in Valkey (default 15 days).
- **Bounded memory** — per-query sample list capped (`LPUSH` + `LTRIM`).
- **Windowed analytics** — P95 trend and impact score over time windows.

## Tech stack

| Layer   | Technology              |
|---------|-------------------------|
| Runtime | Java 17 + Spring Boot   |
| Storage | Valkey (Redis protocol) |
| Client  | Spring Data Redis / Lettuce |
| View    | Thymeleaf (dashboard)   |
| Build   | Maven                   |

## Project structure

```
src/main/java/it/berlink/
├── LogProcessorApplication.java            Spring Boot entry point
├── config/
│   └── RedisConfig.java                    RedisTemplate + JSON serializers
└── monitoring/
    ├── controller/
    │   ├── QueryMonitorController.java      REST API (/api/query-monitor)
    │   └── DashboardController.java         HTML dashboard (/dashboard)
    ├── service/
    │   ├── QueryLogFileProcessor.java       Tails the log file, persists position
    │   └── QueryMonitorService.java         Metrics computation + Valkey access
    ├── parser/
    │   └── QueryLogParser.java              Log line → ParsedLogEntry
    └── model/
        ├── ExecutionPoint.java              One query execution sample
        ├── QueryMetric.java                 Aggregated metrics per query pattern
        ├── QueryMetricWithTrend.java        QueryMetric + windowed trend fields
        ├── QueryDetail.java                 QueryMetric + recent executions
        ├── MonitorOverview.java             Global aggregated statistics
        ├── ProcessorStatus.java             Log processor runtime status
        ├── ParseError.java                  A captured parse failure
        ├── PaginatedResult.java             Generic pagination wrapper
        ├── TimeSeriesPoint.java             Hourly aggregated chart point
        └── DurationDistribution.java        Execution counts per duration bucket
```

`src/main/resources/`:
- `application.yml` — configuration
- `templates/dashboard.html` — Thymeleaf dashboard view

## Configuration

`src/main/resources/application.yml`:

```yaml
server:
  port: 8082

spring:
  application:
    name: berlink-log-processor
  thymeleaf:
    prefix: classpath:/templates/
    suffix: .html
    mode: HTML
    encoding: UTF-8
    cache: false
  data:
    redis:
      host: 172.28.234.122
      port: 6379
      database: 0
      timeout: 5000ms
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
          max-wait: -1ms

query:
  log:
    file:
      path: /berlink/logs/berlink_queries.log   # log file to tail
  monitor:
    ttl:
      days: 15            # Valkey TTL for metrics, samples and index
    poll:
      interval:
        ms: 1000          # how often the file is polled for new content
    max:
      samples: 1000       # max execution samples kept per query pattern

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics

logging:
  level:
    it.berlink: ${LOG_LEVEL:TRACE}
```

| Property                          | Default | Meaning |
|------------------------------------|---------|---------|
| `server.port`                      | 8082    | HTTP port |
| `query.log.file.path`              | —       | Absolute path of the log file to tail (required) |
| `query.monitor.ttl.days`           | 15      | TTL applied to metric/sample/index keys |
| `query.monitor.poll.interval.ms`   | 1000    | Polling interval of the file processor |
| `query.monitor.max.samples`        | 1000    | Bounded sample list size per query pattern |

The file processor uses its own `ScheduledExecutorService` started from
`@PostConstruct` — Spring's `@EnableScheduling` is **not** required.

## Data model in Valkey

`RedisConfig` configures a `RedisTemplate<String,Object>` with `StringRedisSerializer`
for keys and `GenericJackson2JsonRedisSerializer` (JSR-310 aware) for values.

| Key                          | Type   | Content | TTL |
|------------------------------|--------|---------|-----|
| `query:{hash}:metric`        | String (JSON) | Serialized `QueryMetric` for the query pattern | `ttl.days` |
| `query:{hash}:samples`       | List   | `ExecutionPoint` samples, newest first (`LPUSH` + `LTRIM` to `max.samples`) | `ttl.days` |
| `query:index`                | Set    | All known query hashes | `ttl.days` |
| `logprocessor:position`      | String | Last byte offset read from the log file | 30 days |

`{hash}` is the first 16 hex chars of the MD5 of the normalized query.

## Log file format

The parser tries the ActiveJDBC JSON format first, then falls back to the legacy
pipe-delimited format. Records may span multiple lines: the processor accumulates
lines starting at a `TIMESTAMP LEVEL` prefix until the JSON payload terminator
(`"duration_millis":<n>(,"cache":"...")?}`) or the next record start.

### ActiveJDBC JSON format (primary)

```
2026-01-25 16:55:10.891 INFO  [823552] [OperationService.getAllOperations] org.javalite.activejdbc.LazyList - {"sql":"SELECT * FROM gb_site_bulks WHERE id_site = ?","params":[4],"duration_millis":1,"cache":"miss"}
```

Layout: `TIMESTAMP LEVEL [THREAD] [METHOD] LOGGER - {json}`. The parser extracts the
timestamp, the `[METHOD]` group (stored as `topMethod`), and from the JSON payload
the `sql` and `duration_millis` fields. The JSON reader allows non-standard
backslash escaping because ActiveJDBC emits unescaped SQL.

### Legacy pipe-delimited format (fallback)

```
2025-01-24 10:15:32.456 | SELECT * FROM users WHERE id = ? | 45ms | rows:1
```

The trailing `| rows:N` segment is optional.

### Query normalization

Before hashing, queries are normalized so similar queries group together:
whitespace collapsed; numeric literals, single- and double-quoted strings replaced
with `?`; `IN (...)` collapsed to `IN (?)`; `VALUES (...)` collapsed to `VALUES (?)`;
text upper-cased.

## REST API

Base path: `/api/query-monitor`

### GET `/slowest?limit=20`
Queries sorted by P95 descending. Returns `List<QueryMetric>`.

### GET `/most-frequent?limit=20`
Queries sorted by execution count descending. Returns `List<QueryMetric>`.

### GET `/queries-paginated`
Paginated, filtered and sorted query list.

| Param             | Default          | Notes |
|-------------------|------------------|-------|
| `page`            | 0                | zero-based page index |
| `size`            | 20               | page size |
| `sortBy`          | `p95DurationMs`  | `p95DurationMs` \| `avgDurationMs` \| `executionCount` \| `impactScore` \| `queryPattern` \| `topMethod` |
| `sortDir`         | `desc`           | `asc` \| `desc` |
| `queryFilter`     | —                | case-insensitive substring on the query pattern |
| `methodFilter`    | —                | case-insensitive substring on `topMethod` |
| `timeWindowHours` | —                | if `> 0`, metrics are recomputed over the window and trend fields are populated |

Returns `PaginatedResult<QueryMetric>`, or `PaginatedResult<QueryMetricWithTrend>`
when `timeWindowHours > 0`.

### GET `/{hash}`
Detail for one query pattern. Returns `QueryDetail` (metrics + up to 100 most recent
`ExecutionPoint`s), or `404` if the hash is unknown.

### GET `/overview`
Global statistics. Returns `MonitorOverview` (`totalQueriesTracked`,
`totalExecutions`, `avgDurationMs`, `slowestQueryP95Ms`, `slowestQueryHash`,
`monitoringStartTime`, `lastUpdateTime`).

### GET `/processor/status`
Log processor runtime status. Returns `ProcessorStatus` (`logFilePath`,
`fileExists`, `currentFilePosition`, `fileSizeBytes`, `linesProcessed`,
`entriesParsed`, `parseErrors`, `startTime`, `lastReadTime`, `isRunning`).

### GET `/processor/parse-errors?limit=50`
Most recent parse failures, newest first. Returns `List<ParseError>`. The processor
keeps a bounded in-memory buffer of the last 200 errors.

### GET `/time-series?hours=24`
Executions across all queries aggregated into hourly buckets. Returns
`List<TimeSeriesPoint>` (`timestamp`, `avgDurationMs`, `p95DurationMs`,
`executionCount`), sorted by timestamp.

### GET `/duration-distribution`
Execution counts bucketed by duration. Returns `DurationDistribution`:
`<10ms`, `10–50ms`, `50–100ms`, `100–500ms`, `>500ms`, plus `totalExecutions`.

### GET `/dashboard`
HTML performance dashboard (Thymeleaf). Served by `DashboardController`.

### Actuator
`/actuator/health`, `/actuator/info`, `/actuator/metrics`.

## Running

```bash
# build
mvn clean package

# run
mvn spring-boot:run
# or
java -jar target/*.jar
```

Then:

```bash
curl http://localhost:8082/api/query-monitor/processor/status
curl http://localhost:8082/api/query-monitor/overview
curl http://localhost:8082/api/query-monitor/slowest
# dashboard
open http://localhost:8082/dashboard
```

Inspect Valkey directly:

```bash
redis-cli -h <host> ping
redis-cli -h <host> SMEMBERS query:index
redis-cli -h <host> GET "query:{hash}:metric"
redis-cli -h <host> LRANGE "query:{hash}:samples" 0 9
redis-cli -h <host> GET logprocessor:position
```

## Dashboard indicators guide

### KPI cards

| Card | Description |
|------|-------------|
| **Unique Queries** | Distinct query patterns currently tracked (`totalQueriesTracked`) |
| **Total Executions** | Sum of executions across all patterns (`totalExecutions`) |
| **Avg Duration** | Execution-count-weighted average duration across all queries |
| **Processor Status** | Log processor state (running/stopped) with lines/entries/errors counters |

### Time window filter

The button group above the Slowest Queries table sets the analysis window
(`timeWindowHours` on `/queries-paginated`):

| Button | Behavior |
|--------|----------|
| **1h / 6h / 24h / 7d** | Recomputes P95, Avg, Count, Impact and Trend using only samples inside the window |
| **All** | Uses the aggregated metrics stored in Valkey (no time filtering) |

Trend and windowed Impact are only available when a time window is active.

### Trend indicator

Visible only with a time window selected. Compares the current window's P95 against
the previous window of equal length.

| Label       | Meaning |
|-------------|---------|
| `improving` | P95 improved more than 10% vs the previous window |
| `degrading` | P95 worsened more than 10% vs the previous window |
| `stable`    | P95 change within ±10% |
| `new`       | No samples in the previous window to compare against |

### Impact score

Composite score ranking queries by overall operational impact.

**Non-windowed (`All`):**

```
impactScore = P95 × log₂(executionCount + 1) × recencyFactor
```

| `lastSeen` age      | recencyFactor |
|---------------------|---------------|
| < 1 hour            | 1.0 |
| < 24 hours          | 0.8 |
| < 7 days (168h)     | 0.5 |
| ≥ 7 days / unknown  | 0.2 |

**Windowed (`1h/6h/24h/7d`):** samples are already time-filtered, so no recency
factor is applied:

```
impactScore = P95 × log₂(executionCount + 1)
```

The Impact column is sortable.

## Troubleshooting

### Processor not reading new lines
- Check `query.log.file.path` is correct and readable.
- `GET /api/query-monitor/processor/status` — verify `fileExists`, `isRunning`,
  `currentFilePosition` advancing, `lastReadTime` recent.
- Check application logs (`it.berlink` log level is `TRACE` by default).

### Lines counted but not parsed
- `entriesParsed` low while `parseErrors` high → log format mismatch.
- `GET /api/query-monitor/processor/parse-errors` to inspect failing lines.
- Confirm lines match the ActiveJDBC or legacy format above.

### No data in the API / dashboard
- Verify Valkey is reachable (`redis-cli ping`) and `spring.data.redis.*` is correct.
- Check `query:index` is populated (`SMEMBERS query:index`).
- Confirm queries were actually logged into the file.

### Position stuck after rotation
- The processor resets to `0` when file size drops below the stored position.
- To force a full re-read: `redis-cli DEL logprocessor:position` and restart.

## Future enhancements

- [ ] Alerting for slow queries (email / Slack)
- [ ] Export to CSV / Excel
- [ ] Query execution plan capture
- [ ] Long-term storage backend (PostgreSQL)
- [ ] Grafana dashboard integration

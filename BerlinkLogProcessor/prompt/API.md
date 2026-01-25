# BerlinkLogProcessor - API Documentation

Base URL: `http://localhost:8080`

## Endpoints

### GET /api/query-monitor/slowest

Restituisce le query più lente ordinate per P95.

**Query Parameters:**
| Parametro | Tipo | Default | Descrizione |
|-----------|------|---------|-------------|
| `limit` | int | 20 | Numero massimo di risultati |

**Response:** `200 OK`
```json
[
  {
    "queryHash": "abc123def4567890",
    "queryPattern": "SELECT * FROM USERS WHERE ID = ?",
    "executionCount": 150,
    "avgDurationMs": 45.5,
    "minDurationMs": 10,
    "maxDurationMs": 200,
    "p50DurationMs": 40,
    "p95DurationMs": 150,
    "p99DurationMs": 180,
    "firstSeen": "2026-01-25T10:00:00Z",
    "lastSeen": "2026-01-25T20:30:00Z"
  }
]
```

**Esempio:**
```bash
curl "http://localhost:8080/api/query-monitor/slowest?limit=10"
```

---

### GET /api/query-monitor/most-frequent

Restituisce le query più frequenti ordinate per numero di esecuzioni.

**Query Parameters:**
| Parametro | Tipo | Default | Descrizione |
|-----------|------|---------|-------------|
| `limit` | int | 20 | Numero massimo di risultati |

**Response:** `200 OK`
```json
[
  {
    "queryHash": "abc123def4567890",
    "queryPattern": "SELECT * FROM USERS WHERE ID = ?",
    "executionCount": 5000,
    "avgDurationMs": 5.2,
    "minDurationMs": 1,
    "maxDurationMs": 50,
    "p50DurationMs": 4,
    "p95DurationMs": 15,
    "p99DurationMs": 30,
    "firstSeen": "2026-01-25T08:00:00Z",
    "lastSeen": "2026-01-25T20:45:00Z"
  }
]
```

**Esempio:**
```bash
curl "http://localhost:8080/api/query-monitor/most-frequent?limit=5"
```

---

### GET /api/query-monitor/{hash}

Restituisce i dettagli di una specifica query, incluse le esecuzioni recenti.

**Path Parameters:**
| Parametro | Tipo | Descrizione |
|-----------|------|-------------|
| `hash` | string | Hash MD5 (16 caratteri) della query normalizzata |

**Response:** `200 OK`
```json
{
  "metrics": {
    "queryHash": "abc123def4567890",
    "queryPattern": "SELECT * FROM USERS WHERE ID = ?",
    "executionCount": 150,
    "avgDurationMs": 45.5,
    "minDurationMs": 10,
    "maxDurationMs": 200,
    "p50DurationMs": 40,
    "p95DurationMs": 150,
    "p99DurationMs": 180,
    "firstSeen": "2026-01-25T10:00:00Z",
    "lastSeen": "2026-01-25T20:30:00Z"
  },
  "recentExecutions": [
    {
      "timestamp": "2026-01-25T20:30:00Z",
      "durationMs": 45,
      "rowCount": 1,
      "method": "UserService.findById"
    },
    {
      "timestamp": "2026-01-25T20:29:55Z",
      "durationMs": 38,
      "rowCount": 1,
      "method": "UserService.findById"
    }
  ]
}
```

**Response:** `404 Not Found` - Se la query non esiste

**Esempio:**
```bash
curl "http://localhost:8080/api/query-monitor/abc123def4567890"
```

---

### GET /api/query-monitor/overview

Restituisce statistiche generali sul monitoraggio delle query.

**Response:** `200 OK`
```json
{
  "totalQueriesTracked": 250,
  "totalExecutions": 15000,
  "avgDurationMs": 12.5,
  "slowestQueryP95Ms": 500,
  "slowestQueryHash": "xyz789abc1234567",
  "monitoringStartTime": "2026-01-25T08:00:00Z",
  "lastUpdateTime": "2026-01-25T20:45:00Z"
}
```

**Esempio:**
```bash
curl "http://localhost:8080/api/query-monitor/overview"
```

---

### GET /api/query-monitor/processor/status

Restituisce lo stato del processore di log.

**Response:** `200 OK`
```json
{
  "logFilePath": "/berlink/logs/berlink_queries.log",
  "fileExists": true,
  "currentFilePosition": 1048576,
  "fileSizeBytes": 2097152,
  "linesProcessed": 50000,
  "entriesParsed": 48500,
  "parseErrors": 1500,
  "startTime": "2026-01-25T08:00:00Z",
  "lastReadTime": "2026-01-25T20:45:30Z",
  "isRunning": true
}
```

**Esempio:**
```bash
curl "http://localhost:8080/api/query-monitor/processor/status"
```

---

## Modelli

### QueryMetric

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `queryHash` | string | Hash MD5 (16 char) della query normalizzata |
| `queryPattern` | string | Query SQL normalizzata (valori sostituiti con `?`) |
| `executionCount` | long | Numero totale di esecuzioni |
| `avgDurationMs` | double | Durata media in millisecondi |
| `minDurationMs` | long | Durata minima |
| `maxDurationMs` | long | Durata massima |
| `p50DurationMs` | long | 50° percentile (mediana) |
| `p95DurationMs` | long | 95° percentile |
| `p99DurationMs` | long | 99° percentile |
| `firstSeen` | ISO8601 | Prima esecuzione registrata |
| `lastSeen` | ISO8601 | Ultima esecuzione registrata |

### ExecutionPoint

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `timestamp` | ISO8601 | Timestamp dell'esecuzione |
| `durationMs` | long | Durata in millisecondi |
| `rowCount` | int | Numero di righe (se disponibile) |
| `method` | string | Metodo che ha invocato la query (es. `UserService.findById`) |

### MonitorOverview

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `totalQueriesTracked` | long | Numero di query uniche tracciate |
| `totalExecutions` | long | Numero totale di esecuzioni |
| `avgDurationMs` | double | Durata media globale |
| `slowestQueryP95Ms` | long | P95 della query più lenta |
| `slowestQueryHash` | string | Hash della query più lenta |
| `monitoringStartTime` | ISO8601 | Inizio monitoraggio |
| `lastUpdateTime` | ISO8601 | Ultimo aggiornamento |

### ProcessorStatus

| Campo | Tipo | Descrizione |
|-------|------|-------------|
| `logFilePath` | string | Path del file di log monitorato |
| `fileExists` | boolean | Se il file esiste |
| `currentFilePosition` | long | Posizione corrente nel file (bytes) |
| `fileSizeBytes` | long | Dimensione totale del file |
| `linesProcessed` | long | Righe totali processate |
| `entriesParsed` | long | Entry parse correttamente |
| `parseErrors` | long | Errori di parsing |
| `startTime` | ISO8601 | Avvio del processor |
| `lastReadTime` | ISO8601 | Ultima lettura dal file |
| `isRunning` | boolean | Se il processor è attivo |

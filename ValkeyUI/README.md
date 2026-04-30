# Valkey UI

Web Console Spring Boot per il monitoraggio di un'istanza Valkey con focus sugli stream: statistiche, grafici, ispezione consumer group e operazioni di pulizia (XTRIM / XDEL / XGROUP).

## Quick start

```bash
task vui   # build immagine docker (stop + build)
task up    # avvio container valkey-ui (porta 8096)
```

UI disponibile su `http://localhost:8096/` (Basic Auth, default `admin / change-me`).

## Variabili d'ambiente

| Variabile | Default | Descrizione |
|-----------|---------|-------------|
| `VALKEY_HOST` | `valkey-service` | Host Valkey |
| `VALKEY_PORT` | `6379` | Porta Valkey |
| `VALKEY_DB` | `0` | Database Valkey |
| `VALKEY_PASSWORD` | (vuoto) | Password Valkey |
| `VALKEYUI_READ_ONLY` | `false` | Disabilita endpoint di scrittura |
| `VALKEYUI_ADMIN_USERNAME` | `admin` | Utente Basic Auth |
| `VALKEYUI_ADMIN_TOKEN` | `change-me` | Password / token Basic Auth |
| `VALKEYUI_SAMPLER_ENABLED` | `true` | Abilita campionamento metriche periodico |
| `VALKEYUI_SAMPLER_INTERVAL_MS` | `60000` | Intervallo campionamento (ms) |

## Endpoint principali

UI:

- `GET /` - dashboard con info server, top stream, grafico aggregato 24h
- `GET /streams` - lista stream (DataTables)
- `GET /streams/{key}` - dettaglio stream con tab messaggi / consumer groups / statistiche / audit
- `GET /audit` - log audit globale

REST:

- `GET /api/server/info`, `/api/server/ping`, `/api/server/clients`
- `GET /api/streams[?pattern=...]`
- `GET /api/streams/{key}` / `/messages` / `/groups` / `/stats` / `/heatmap` / `/timeseries`
- `POST /api/streams/{key}/trim` (body `{strategy, value, approximate, confirm}`)
- `POST /api/streams/{key}/delete-range`
- `POST /api/streams/{key}/delete-time-range`
- `DELETE /api/streams/{key}?confirm=<key>`
- `POST /api/streams/{key}/groups` / `DELETE .../{group}` / `.../setid` / `.../ack` / `.../consumers/{consumer}`
- `GET /api/audit?limit=200`

## Note operative

- **Read-only mode**: con `VALKEYUI_READ_ONLY=true` ogni endpoint POST/DELETE risponde 403 e i bottoni di scrittura sono disabilitati lato UI.
- **Audit**: ogni operazione di scrittura produce una riga di log INFO `[AUDIT] ...` ed una entry nello stream Valkey `valkeyui:audit` (retention `XTRIM MAXLEN ~ 10000`). La pagina `/audit` visualizza le ultime N entry.
- **Sampler**: ogni `interval-ms` viene scritto un sample (length, first-id, last-id, pending, memory) sullo stream interno `valkeyui:metrics:stream:{streamKey}` (retention `~ 43200` campioni ≈ 30 giorni a 1/min). Alimenta i grafici "lunghezza/memoria nel tempo".
- **Conferma scritture**: tutti gli endpoint distruttivi richiedono `confirm=<key>` (o `confirm=<group>` per i consumer group); mismatch → HTTP 400.
- **Timezone**: timestamp UI in UTC (gli ID Valkey contengono già epoch ms).

## Build immagine production

```bash
./build-production.sh -v 1.0.0
```

(usa `--skip-push` per evitare push, `--skip-tests` per saltare i test).

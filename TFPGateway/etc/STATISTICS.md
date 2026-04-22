# TFP Gateway - Statistics Dashboard

Pagina di monitoraggio del flusso eventi su `/statistics`. Tutti i dati sono calcolati lato PostgreSQL a partire dalla tabella `evt_raw_events`; nessuna metrica richiede uno store esterno (Prometheus, JMX, ...).

Raggiungibile dal dropdown menu di ogni altra pagina dell'applicazione (Event Browser, Configurazione Gateway, Backup).

---

## Architettura

```
statistics.html  (Chart.js CDN, Bootstrap 5)
     |  fetch JSON
     v
StatisticsController  ->  StatisticsService  ->  PostgreSQL (evt_raw_events)
```

### Endpoint JSON

Tutti gli endpoint sotto `/statistics/api/`:

| Endpoint | Parametri | Ritorna |
|---|---|---|
| `GET /summary` | — | KPI globali (totali, throughput, oldest/newest) |
| `GET /events-over-time` | `granularity` (`minute`\|`hour`\|`day`), `lookbackHours` | Time-series per event type |
| `GET /counts-by-type` | `lookbackHours` | Count ed ultimo `event_time` per event type |
| `GET /processing-lag` | `lookbackHours` | Statistiche di `processed_at - event_time` per event type |
| `GET /arrival-gaps` | `lookbackHours` | Istogramma dei delta tra eventi consecutivi per event type |
| `GET /silence` | — | Per ogni event type, ultimo evento visto e silenzio in secondi |

`lookbackHours` e' clamped all'intervallo `[1, 2160]` (max 90 giorni).

### Colonne DB usate

Solo quattro colonne di `evt_raw_events` alimentano la dashboard:

- `event_type` — raggruppamento principale
- `event_time` — timestamp dall'upstream (usato per bucketing temporale e gap)
- `processed_at` — timestamp locale al gateway (usato per il lag)
- `message_id` — solo indirettamente, per deduplica prima dell'insert

Gli indici gia' presenti (`idx_events_event_type`, `idx_events_event_time`, `idx_events_processed_at`) coprono tutte le query della dashboard.

---

## Range selector

In alto a destra:

| Opzione | `lookbackHours` | Granularita' | Quando usarlo |
|---|---|---|---|
| Last 1h (per minute) | 1 | `minute` | Debug real-time, verifica post-deploy, incident |
| Last 6h (per minute) | 6 | `minute` | Turno oncall, analisi picco recente |
| **Last 24h (per hour)** (default) | 24 | `hour` | Visione giornaliera, lettura quotidiana |
| Last 7d (per hour) | 168 | `hour` | Trend settimanale, analisi anomalie |
| Last 30d (per day) | 720 | `day` | Capacity planning, confronti mese/mese |

Il bottone **Refresh** ricarica tutti i pannelli. Lo switch **Stacked** nel pannello time-series cambia tra line chart e stacked area (vedi sotto).

---

## Pannello 1 - KPI Cards

Sei valori numerici a colpo d'occhio. Non dipendono dal range selector: descrivono lo stato globale del gateway.

| Card | Significato | Allarme quando |
|---|---|---|
| Total events | Count di tutta la tabella | Cambia poco, utile per context |
| Today | Eventi dalle `00:00` di oggi | Confronto con tipica giornata |
| Last 24h | Rolling window di 24h | Piu' stabile di "Today" nei primi orari della mattina |
| Last 7d | Rolling window di 7 giorni | Benchmark settimanale |
| Event types | Numero di `event_type` distinti | Cala -> qualche producer e' morto |
| Throughput /min | Eventi/minuto medi sugli ultimi 60 min | Cala bruscamente -> backpressure o upstream giu' |

**Come leggerli.** Ogni card e' uno scalare. Utili per il *sanity check* iniziale prima di guardare i grafici. Se "Today" e' a zero a meta' mattina, non serve guardare altro: qualcosa e' fermo.

---

## Pannello 2 - Messaggi ricevuti nel tempo per Event Type (grafico principale)

Line / stacked area chart. Asse X = bucket temporali (minuto/ora/giorno secondo granularita'), asse Y = count di eventi ricevuti in quel bucket.

Ogni event type e' una serie distinta con colore stabile (hash del nome). Il toggle **Stacked** permette di passare tra:

- **Line mode** (toggle OFF): ogni event type e' una linea indipendente. Usare per confrontare l'andamento relativo delle code.
- **Stacked mode** (toggle ON, default): aree impilate. L'altezza totale e' il throughput aggregato. Usare per vedere il volume complessivo del gateway nel tempo.

### Come leggere il grafico

| Pattern | Interpretazione |
|---|---|
| Flat line a zero su un event type | Producer spento, address non routata, subscription rotta |
| Crollo simultaneo di tutte le serie | Broker giu', gateway disconnesso, DB saturo |
| Picchi periodici (ogni ora/giorno) | Job batch upstream - normale, verifica solo l'ampiezza |
| Crescita costante giorno su giorno | Accumulo fisiologico o nuovo producer attivato; confrontare con Last 30d |
| Una sola serie esplode | Producer in loop, retry storm, upstream che rimanda tutto |
| Total (in stacked) stabile, ma composizione cambia | Un event type ha preso il posto di un altro - possibile bug di classificazione |

**Nota sulla granularita'.** Bucket piu' grandi smussano i burst. Su "Last 1h (per minute)" si vede lo spike di 30 secondi; su "Last 30d (per day)" lo stesso spike sparisce. Se si cerca un burst, zoommare.

---

## Pannello 3 - Distribuzione per Event Type

Due viste affiancate dello **stesso dato** (count aggregato per event type nell'intervallo scelto):

- **Donut chart** — percentuali visive. Utile per vedere subito quale event type domina.
- **Tabella a destra** — Event Type / Count / % / Ultimo ricevuto, ordinata per count decrescente.

### Come leggerla

- La distribuzione dipende fortemente dal business. Una volta conosciuto il rapporto normale (es. POSITIONS 70%, UNIT_EVENTS 25%, ASSET_DAMAGES 5%), ogni scostamento > 10% e' da investigare.
- La colonna **Ultimo ricevuto** e' un early warning: se un event type con count alto ha "Ultimo ricevuto" di ieri, il producer si e' fermato *oggi*.
- Gli event type con count 0 non appaiono. Se un tipo atteso manca del tutto dall'elenco, e' fermo dall'inizio della finestra.

---

## Pannello 4 - Processing Lag

Tabella con `processed_at - event_time` in secondi, aggregato per event type:

| Colonna | Significato |
|---|---|
| Avg | Media aritmetica |
| p50 | Mediana (il "caso tipico") |
| p95 | 95-esimo percentile (il "caso peggiore ragionevole") |
| p99 | 99-esimo percentile (le code) |
| Max | Peggior caso assoluto nell'intervallo |

Ordinata per **p95 decrescente**: i problemi galleggiano in cima.

### Cosa significa lag alto

Il lag misura **quanto tempo passa tra quando l'upstream ha generato l'evento e quando il gateway lo ha persistito**. Include: tempo in coda Artemis + tempo di consume + tempo di parse + tempo di upsert.

| Forma | Probabile causa |
|---|---|
| p50 basso, p95/p99/Max alti | Backlog in catch-up: il gateway sta processando messaggi vecchi accumulati nel broker |
| Tutti i percentili alti e uniformi | Sistema globalmente lento: DB, rete, o gateway sotto-dimensionato |
| p95 alto solo su un event type | Handler di quel tipo specifico e' lento (query lenta, chiamata esterna, ecc.) |
| Lag negativi o quasi zero costanti | Clock skew tra producer e gateway - verificare NTP |
| Max enorme ma p99 basso | Outlier singolo (un messaggio vecchio rigiocato manualmente), non e' un problema |

### Soglie indicative

Dipendono dalla natura dell'evento. Come punto di partenza:

- **p95 < 5s**: healthy
- **5s < p95 < 60s**: sistema sotto carico, monitorare
- **p95 > 60s**: backpressure reale, servono piu' consumer o investigazione

Queste soglie vanno calibrate sulla baseline del singolo event type: eventi batch notturni hanno naturalmente lag piu' alto.

---

## Pannello 5 - Arrival Gap Distribution

Istogramma: per ogni event type calcola il delta `event_time[n] - event_time[n-1]` tra eventi consecutivi (funzione `LAG()` di PostgreSQL) e classifica i delta in 7 bucket a scala logaritmica:

```
<1s | 1-5s | 5-30s | 30s-2m | 2-10m | 10-60m | >1h
```

Sotto l'istogramma una tabella con `min / p50 / avg / p95 / max` del gap per event type.

### Perche' serve oltre al Processing Lag

Il lag misura **ritardo di elaborazione**. Il gap misura il **ritmo di arrivo**. Sono diversi:

- Un producer puo' mandare con ritmo perfetto (gap costanti) ma il gateway processare lento (lag alto).
- Un producer puo' mandare a raffica (gap piccoli concentrati) e poi stare zitto per ore (gap enormi) con lag sempre basso.

Il solo lag non rivela burst ne' starvation dell'upstream.

### Come leggere l'istogramma

| Forma della distribuzione | Interpretazione |
|---|---|
| **Concentrata a sinistra** (`<1s`, `1-5s`) | Burst: eventi in raffica. Normale per job batch, sospetto se e' continuo. Sono il caso in cui la coda Artemis puo' accumulare se i consumer sono pochi. |
| **Picco centrale regolare** (es. `30s-2m`) | Producer con heartbeat periodico (tipico delle posizioni veicoli). Sano se corrisponde al ritmo atteso. |
| **Coda a destra** (barre non trascurabili in `10-60m`, `>1h`) | Starvation: buchi lunghi nel ritmo di arrivo. Possibili cause: producer intermittente, connessione instabile, oppure volume reale basso. |
| **p95 >> p50** | Comportamento bimodale: il ritmo tipico e' X, ma ogni tanto ci sono pause lunghe. Controllare se il producer fa job periodici. |
| **Barra unica in `>1h`** | Event type praticamente fermo: c'e' un primo evento isolato nella finestra e basta. |
| **Istogramma vuoto per un tipo previsto** | Meno di 2 eventi nella finestra -> nessun gap calcolabile. Allargare il range. |

### Confronto utile con il Pannello 4

- Gap piccoli + lag alto -> **problema nostro** (gateway/DB/handler lento).
- Gap grandi + lag basso -> **problema upstream** (producer non manda abbastanza).
- Gap grandi + lag alto -> **entrambi**: upstream rallentato e gateway fatica a tenere testa quando arriva la raffica.

---

## Pannello 6 - Silence Monitor

Tabella che risponde alla domanda: **"quale event type e' fermo in questo momento?"**.

Per ogni event type mostra l'ultimo `event_time` assoluto (non filtrato dal range selector) e il silenzio in secondi. Ordinata per silenzio decrescente: i piu' fermi in cima.

Color coding sulla riga:

| Colore | Silenzio | Significato |
|---|---|---|
| Nessuno | < 1h | OK |
| **Giallo** | 1h - 24h | Warning: verificare se e' normale per quel tipo |
| **Rosso** | > 24h | Critico: event type quasi certamente fermo |

### Perche' serve oltre ai count

Un event type con **count totale alto** in DB puo' essere fermo oggi: la Distribuzione (Pannello 3) non lo rivela perche' guarda un range di tempo. Il Silence Monitor e' l'unico pannello che usa la data **attuale** come riferimento.

### Come interpretarlo

- **Rosso su tutti gli event type**: gateway completamente disconnesso (broker, rete, auth).
- **Rosso su un solo tipo**: producer upstream di quel tipo morto, oppure subscription rotta nel broker, oppure address eliminata da `broker.xml`.
- **Giallo su tipi batch**: normale se l'evento arriva una volta al giorno (es. report notturno).
- **Tutti verdi ma Throughput /min basso**: ognuno sta mandando qualcosa ma poco - non e' un problema di "coda morta" ma di volume.

### Falsi positivi noti

- Clock skew: se il producer ha l'orologio indietro di 2 ore, tutti gli event_time sembreranno di 2 ore fa anche se appena ricevuti.
- Backfill/resend: resend di eventi vecchi aggiorna `processed_at` ma non `event_time`, quindi il silenzio continua a salire.

---

## Flusso di debug tipico

Quando qualcosa sembra non funzionare:

1. **KPI cards** — "Throughput /min" e "Today" tornano un valore sensato?
2. **Silence Monitor** — qualche event type e' rosso/giallo?
3. **Time-series** — il crollo e' simultaneo su tutto o solo su una serie?
4. **Processing Lag** — il problema e' sul gateway (lag alto) o sull'upstream (lag normale)?
5. **Arrival Gap** — se upstream sospetto: e' un burst (consumer da scalare) o starvation (producer da investigare)?
6. **Event Browser** (`/events`) — se identifico il tipo problematico, apro il browser filtrato su quell'event_type per vedere i payload reali.

---

## Performance

Tutte le query usano `event_time >= NOW() - interval` con indice `idx_events_event_time`. Le query con `GROUP BY event_type` beneficiano anche di `idx_events_event_type`.

Costo approssimativo su 10M righe (indicativo, su PostgreSQL con RAM adeguata):

| Query | Costo |
|---|---|
| `summary` | < 50 ms (count con indice) |
| `events-over-time` su Last 24h | < 200 ms |
| `events-over-time` su Last 30d | 500 ms - 1 s (considerare caching se l'uso cresce) |
| `processing-lag` | 100 - 300 ms |
| `arrival-gaps` | 300 ms - 1 s (usa `LAG()` + window function) |
| `silence` | 50 - 100 ms |

Per volumi significativamente superiori considerare: materialized view rinfrescata ogni N minuti per `events-over-time` e `arrival-gaps`.

---

## File sorgenti

| File | Ruolo |
|---|---|
| `src/main/java/.../controller/StatisticsController.java` | Route page + API JSON |
| `src/main/java/.../service/StatisticsService.java` | Query SQL aggregate |
| `src/main/resources/templates/statistics.html` | UI (Bootstrap 5 + Chart.js 4 da CDN) |

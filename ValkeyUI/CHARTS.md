# Valkey UI - Guida ai grafici

Riferimento operativo per leggere correttamente i grafici esposti dall'app. Tutti i timestamp visualizzati sull'asse X sono in **UTC** (formato `YYYY-MM-DD HH:MM` o `HH:MM` a seconda del grafico).

---

## 1. Dashboard (`/`)

### 1.1 "Messaggi/min - ultime 24h (aggregato)"

- **Tipo**: bar chart (Chart.js).
- **Sorgente dati**: per ogni stream rilevato, chiamata a `GET /api/streams/{key}/stats?bucket=minute&range=24h`. I bucket vengono **sommati** sullo stesso `timestampMs` per ottenere un valore aggregato globale.
- **Asse X**: ora UTC (`HH:MM`), bucket di 1 minuto, finestra mobile delle ultime 24h. `maxTicksLimit = 24` quindi vedi un'etichetta ogni ora circa.
- **Asse Y**: numero totale di messaggi prodotti in quel minuto su **tutti gli stream sommati**.
- **Come si legge**:
  - barre alte = picchi di traffico aggregato sul cluster Valkey (utile per individuare ore di pressione).
  - barre vuote = nessun messaggio scritto in quel minuto su nessuno stream (anche un singolo stream attivo basta a riempire la barra).
- **Limiti / gotcha**:
  - Il conteggio si basa sull'ID dei record (`XRANGE` dei dati live), non sulle metriche del sampler. Se uno stream e' stato troncato (XTRIM/XDEL), i bucket dei messaggi rimossi compaiono **vuoti** anche se in passato c'era traffico.
  - Il calcolo richiede una `GET /api/streams/{key}/stats` per stream: con tanti stream l'aggregato puo' impiegare qualche secondo.
  - Stream `valkeyui:audit` e `valkeyui:metrics:stream:*` rientrano nell'elenco e quindi nell'aggregato; ricordatelo per non confondere il "rumore di servizio" con il traffico applicativo.
- **Tabelle correlate** (stesso pannello):
  - "Top 10 Stream per lunghezza" / "Top 10 Stream per pending": ordinamento immediato sugli stream piu' grossi o con piu' messaggi non ancora consumati. Non sono grafici, ma sono il primo posto dove guardare quando l'aggregato si impenna.

---

## 2. Stream Detail (`/streams/{key}`) - tab "Statistiche"

I tre selettori in alto al tab pilotano tutti i grafici insieme:

- **Bucket**: `Minuto` / `Ora` / `Giorno` -> influenza solo "Messaggi per bucket".
- **Range**: `24h` / `7 giorni` / `30 giorni` -> influenza "Messaggi per bucket", "Lunghezza nel tempo", "Memoria nel tempo".
- **Aggiorna**: ricarica tutti e quattro i pannelli.

### 2.1 "Messaggi per bucket"

- **Tipo**: bar chart.
- **Sorgente**: `GET /api/streams/{key}/stats?bucket={bucket}&range={range}`.
- **Calcolo**: il backend (`StreamStatsService.messagesPerBucket`) inizializza tutti i bucket nell'intervallo a 0, poi legge **i messaggi reali** dello stream con `XRANGE` (limite `valkeyui.query.max-messages-per-call`, default 50.000) e li conta per bucket basandosi sul **timestamp dell'ID** (`StreamIdUtils.timestampMs`).
- **Asse X**: timestamp UTC arrotondato alla granularita' del bucket (`YYYY-MM-DD HH:MM`).
- **Asse Y**: numero di messaggi finiti in quel bucket.
- **Come si legge**:
  - distribuzione temporale del traffico **di questo singolo stream**.
  - barre = 0 contigue indicano "buco" reale (nessun messaggio) o stream troncato in quel periodo.
  - in modalita' `Giorno` la barra rappresenta il volume giornaliero, utile per trend a 7/30 giorni.
- **Gotcha**:
  - se i messaggi totali nel range superano `max-messages-per-call`, il conteggio si **ferma** al limite -> i bucket piu' vecchi possono apparire sottostimati. In tal caso restringi il range o aumenta la property.
  - la finestra parte da `now - range` non da inizio bucket, quindi il primo bucket puo' essere parziale.

### 2.2 "Lunghezza nel tempo"

- **Tipo**: line chart.
- **Sorgente**: `GET /api/streams/{key}/timeseries?metric=length&range={range}`.
- **Calcolo**: legge le entry del **sampler** dallo stream `valkeyui:metrics:stream:{key}` (vedi `StreamSamplerService`), campo `length`. Granularita' = `valkeyui.sampler.interval-ms` (default 60s). Retention = `valkeyui.sampler.metrics-retention` (default 43.200 punti ~ 30 giorni a 1 punto/min).
- **Asse X**: timestamp UTC del campionamento (`YYYY-MM-DD HH:MM`).
- **Asse Y**: numero di entry nello stream (`XLEN`) al momento del sampling.
- **Come si legge**:
  - linea **crescente** = piu' produzione che consumo / trim.
  - linea **piatta** = stream stabile (es. trim attivo, oppure produzione = 0).
  - **scalini in giu'** = `XTRIM` o `XDEL` (tipicamente correlati a una entry corrispondente nell'audit).
  - linea che **riparte da 0** = `DEL` totale dello stream o re-creazione.
- **Gotcha**:
  - se il sampler era **disabilitato** (`VALKEYUI_SAMPLER_ENABLED=false`) o lo stream e' nato dopo l'avvio del processo, il grafico puo' essere vuoto o partire da meta'.
  - dopo restart dell'app il sampler riparte: i punti non vengono persi (sono in Valkey) ma c'e' un **gap** tra l'ultimo sampling pre-restart e il primo post-restart.

### 2.3 "Memoria nel tempo"

- **Tipo**: line chart.
- **Sorgente**: `GET /api/streams/{key}/timeseries?metric=memory&range={range}`. Stesso meccanismo del grafico Lunghezza, ma legge il campo `memory_bytes`.
- **Asse X**: timestamp UTC del sampling.
- **Asse Y**: byte occupati dallo stream (`MEMORY USAGE` di Valkey al momento del sampling). L'etichetta del dataset e' "Memoria (B)" -> **byte**, non KB/MB.
- **Come si legge**:
  - confronto diretto con "Lunghezza nel tempo": se la memoria cresce piu' velocemente della lunghezza significa che i messaggi recenti hanno **payload mediamente piu' grandi**.
  - cali bruschi della memoria senza cali di lunghezza non sono normali: di solito significano che e' avvenuto un compaction interno di Valkey o un sampling ha visto lo stream in stato transitorio.
- **Gotcha**:
  - `MEMORY USAGE` e' una **stima** di Valkey, non un valore esatto -> piccole oscillazioni del +/- qualche % sono fisiologiche.
  - i byte includono overhead di metadata e indice radix tree, non solo il payload utente.

### 2.4 "Heatmap settimanale (giorni x ore)"

- **Tipo**: heatmap HTML/CSS custom (non Chart.js), 7 righe (Lun-Dom) x 24 colonne (0-23 UTC).
- **Sorgente**: `GET /api/streams/{key}/heatmap`.
- **Calcolo**: il backend (`StreamStatsService.heatmapWeekly`) legge **tutti i messaggi degli ultimi 7 giorni** via `XRANGE` (limite `max-messages-per-call`) e li accumula nella cella `[giorno_della_settimana][ora_UTC]` ricavata dal timestamp dell'ID.
- **Come si legge**:
  - colore piu' intenso = piu' messaggi in quella combinazione giorno+ora **negli ultimi 7 giorni**.
  - il numero scritto nella cella e' il conteggio assoluto; passando il mouse il tooltip mostra `Giorno HH:00 = N`.
  - intensita' su scala 1-5: `intensity = ceil((value / max) * 5)`. **L'intensita' e' relativa al max della heatmap stessa**, non assoluta tra stream diversi.
- **Casi tipici**:
  - banda verticale = stesso orario tutti i giorni (job schedulato).
  - banda orizzontale = un giorno fuori scala (probabilmente rilascio/incident).
  - tutta scura tranne 1 cella = tutto il traffico e' confinato in un solo slot, di solito anomalia.
- **Gotcha**:
  - 7 giorni rolling, **non** settimana ISO -> l'eta' delle celle dipende dall'istante in cui carichi la pagina.
  - se lo stream ha piu' di `max-messages-per-call` entry nei 7 giorni, le piu' vecchie vengono troncate e la heatmap **sottostima il passato**. Per stream ad alto volume considera che e' una vista del **piu' recente**.
  - tutti i timestamp sono in UTC: se vivi in Europe/Rome, le 09:00 italiane d'estate stanno in colonna `07`.

---

## 3. Indicatori non grafici utili in lettura combinata

Pur non essendo grafici, vengono spesso letti insieme ai grafici sopra:

- **Summary badge in cima a Stream Detail** (`length`, `firstId`, `lastId`, `lastTimestampMs`, `groupCount`, `pendingTotal`, `memoryBytes`): fotografia istantanea, da confrontare con l'ultimo punto delle line chart.
- **Tab "Audit"**: ogni `XTRIM`/`XDEL`/`DEL`/`XGROUP` e' loggato qui. Quando una line chart fa uno scalino, qui trovi chi/quando l'ha fatto.

---

## 4. Cheatsheet rapido

| Grafico | Dove | Granularita' | Sorgente | Cosa misura |
|---------|------|--------------|----------|-------------|
| Aggregate msg/min 24h | Dashboard | 1 min | `XRANGE` live, sommato per stream | Throughput totale cluster |
| Messaggi per bucket | Stream Detail / Statistiche | minuto / ora / giorno | `XRANGE` dello stream | Throughput dello stream |
| Lunghezza nel tempo | Stream Detail / Statistiche | sampler interval (60s) | `valkeyui:metrics:stream:{key}` campo `length` | `XLEN` storico |
| Memoria nel tempo | Stream Detail / Statistiche | sampler interval (60s) | `valkeyui:metrics:stream:{key}` campo `memory_bytes` | `MEMORY USAGE` storico (byte) |
| Heatmap settimanale | Stream Detail / Statistiche | 1 ora x 7 gg | `XRANGE` ultimi 7 gg | Distribuzione giorno x ora |

---

## 5. Troubleshooting comune

- **Line chart vuoto / con gap**: sampler disabilitato, stream piu' giovane dell'avvio, o restart recente. Controlla `VALKEYUI_SAMPLER_ENABLED` e l'ora di start del container.
- **Bar chart "Messaggi per bucket" piatto a 0 ma stream non vuoto**: hai selezionato un range piu' vecchio del `firstId` corrente (i messaggi di quel periodo sono stati trimmati).
- **Heatmap tutta chiara tranne poche celle**: traffico molto sbilanciato, oppure raggiunto il limite `max-messages-per-call` -> l'intensita' viene riscalata sul max corrente.
- **Aggregate dashboard piu' alto della somma dei singoli per bucket**: nessun motivo legittimo, e' un bug. Verifica che il bucket della dashboard (sempre `minute/24h`) corrisponda a quello del confronto manuale.

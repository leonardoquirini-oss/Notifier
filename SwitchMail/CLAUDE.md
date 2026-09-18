# CLAUDE.md — SwitchMail

Microservizio che legge le mail di aggiornamento inviate da sistemi esterni, le classifica per
mittente/oggetto/allegato e per ogni tipo esegue un'azione verso BERLink. Sostituisce un lavoro
manuale (un dipendente che legge la casella e agisce a mano) **senza toccare la casella**.

Documenti: `MAIL_PROCESSOR_DESIGN.md` (il perché), `IMPLEMENTATION_PLAN.md` (il come, con le
motivazioni di ogni scelta), `IMPLEMENTATION_STATUS.md` (a che punto è).

## Stack

Spring Boot 3.4.3, Java 17, Maven. `it.gruppobernardini:switch-mail`, package root
`it.gruppobernardini.switchmail`. SQLite (file singolo) + `JdbcTemplate`. Thymeleaf + Bootstrap da
CDN, nessun npm. Porta host **8105** (nel container 8080).

## Decisioni da non "correggere"

- **Niente ActiveJDBC**, benché gli altri processori lo usino. `Base.open/close` lega la connessione
  a una mappa statica per-thread: qui i thread sono molti (Tomcat, `ThreadPoolTaskScheduler(3)`,
  runner di boot) e il pool SQLite è di **una** connessione, quindi una connessione non rilasciata
  bloccherebbe il servizio. In più le sue transazioni stanno fuori da `DataSourceTransactionManager`
  e l'instrumentation entrerebbe in ogni run di test. Non darebbe nemmeno portabilità: non ha layer
  DDL e i model dei fratelli sono legati a Postgres dall'annotazione `@IdGenerator("nextval(...)")`.
  Stessa scelta già presa nella piattaforma: `TFPEventIngester/CLAUDE.md:401`.
- **Tutto lo SQL sta in `dao/`.** `JdbcTemplate` compare solo lì e in `config/SwitchMailDbConfig`.
  Le uniche eccezioni sono `config/SwitchMailDbConfig` (URL, PRAGMA, pool) e `config/SchemaMigrations`
  (schema e migration: sono legate al motore per definizione). È il seam su cui si farebbe un porting
  a Postgres (§8 di `IMPLEMENTATION_PLAN.md`): si toccherebbero tre file, nessun service, nessun
  controller e nessun processore. Verifica meccanica:
  `grep -rn "JdbcTemplate" src/main/java | grep -v "/dao/\|SwitchMailDbConfig\|SchemaMigrations"`
  deve essere vuoto. Vale anche per una `SELECT 1`: il ping di health passa da `dao/HealthDao`.
- **Timestamp prodotti in Java** da `util/TimestampUtil` sopra il bean `Clock` (TEXT ISO-8601 UTC con
  millisecondi). Nessun `DEFAULT` di colonna con `strftime`: è l'SQLite-ismo più diffuso e rende i
  test dipendenti dall'orologio vero.
- **`@ExceptionHandler` locale al controller**, non `@ControllerAdvice`: è la convenzione della
  piattaforma. `IllegalArgumentException` → 400 `{"error": msg}`.
- **`mail.imaps.peek = true` sempre.** Senza, `getContent()` emette `FETCH BODY[...]` e il server
  marca come letto **anche su una folder aperta in READ_ONLY**. È inchiodata da
  `ImapSessionPropertiesTest`; non rimuoverla come "ridondante".
- **`process()` è final** in `AbstractMailSubProcessor` e `ProcessingOutcome` non ha una costante di
  fallimento: un errore si lancia, non si ritorna. Non aggiungere `Status.FAILED`.
- **Il boot non fallisce** se una regola punta a un processore inesistente: la UI che ripara quel dato
  gira in questo stesso processo. Si logga, si notifica, `/api/health/ready` va DOWN su `checks.rules`
  e a runtime la mail finisce in dead-letter esplicita.

## Punti aperti

- **`X-Idempotency-Key`**: il client lo manda su ogni scrittura
  (`switchmail:<accountId>:<uidValidity>:<uid>:<ruleId>`), **BERLink oggi non lo onora**. Finché non lo
  farà, la finestra "crash dopo la chiamata, prima della riga di log" resta aperta: il sistema è
  at-least-once, non exactly-once.
- Formato del `.txt` treno e del body forecast: ignoti. I due processori partono in `mode=COLLECT`,
  che acquisisce e archivia senza agire. Endpoint BERLink reali: da definire con i formati.

## Struttura

```
config/      DataSource unico + PRAGMA, properties, schema/migration, RestTemplate BERLink, scheduler
controller/  4 pagine + JSON API (@Controller + @ResponseBody per metodo), health
dao/         TUTTO lo SQL (6 classi)
dto/         payload di API e di servizio
model/       record immutabili di dominio
processor/   SPI + base astratta + registry + i 2 sub-processori
service/     pipeline (reader IMAP, extractor, matcher, ingest, retry, retention) e servizi UI
util/        cifratura credenziali, regex con budget, JSON, timestamp
```

## Aggiungere un sub-processore

1. Nuova classe in `processor/` che estende `AbstractMailSubProcessor`, annotata `@Component`.
2. `public static final String ID = "...";` **scritto a mano** (mai derivato dal nome della classe:
   deve sopravvivere a rinomine e spostamenti di package); `id()` lo ritorna.
3. `displayName()`, `description()`, `paramSpecs()` — da lì la UI genera il form dei parametri.
4. `validateRule(rule, params)` per i requisiti sulla regola (es. "serve un allegato").
5. `handle(ctx)`: usare gli helper della base (`requireAttachmentText`, `requireBodyText`,
   `requireGroup`, `parseDate`, `terminal`, `retry`) e `api(ctx)` per BERLink. Ritornare
   `ProcessingOutcome.success(...)` o `.skipped(...)`; ogni fallimento si lancia.
6. Rinominare un id: aggiungere il vecchio ad `aliasIds()`, il registry lo risolve con un WARN e il
   salvataggio della regola riscrive l'id canonico.

## Comandi

```bash
mvn -B test                      # unit + ImapPollGreenMailIT (server IMAP in-process)
task sm && task up && task logs-sm
curl -H "X-API-Key: $HEALTH_API_KEY" http://127.0.0.1:8105/api/health/ready
sqlite3 ./data/switchmail.db 'SELECT status, COUNT(*) FROM mail_processing_log GROUP BY status;'
```

Variabili obbligatorie (nessun default, di proposito): `SWITCHMAIL_CREDS_KEY` (`openssl rand -base64 32`),
`BERLINK_API_KEY`, `HEALTH_API_KEY`.

## Il ciclo che chiude il progetto

`/logs` → **Scarica .eml** → `src/test/resources/mail/` → test del parser → implementa `mode=PROCESS`
→ **Riprova** sulla stessa mail dal log. Le righe `NO_RULE` sono il feed di scoperta: dicono quali
regole mancano.

# SwitchMail — stato implementazione

> Aggiornato: 2026-09-18. **Le 5 tappe di `IMPLEMENTATION_PLAN.md` §6 sono complete.**
> Build: `mvn -B test` → **98 test, 0 failure** (unit + `ImapPollGreenMailIT` con server IMAP in-process).

## Cosa esiste

| Tappa | Contenuto | Stato |
|---|---|---|
| 1 | `pom.xml`, `SwitchMailApplication`, `config/` (DataSource unico, properties, schema+migration), `db/schema.sql` (7 tabelle, 12 indici), `application.yml` | fatta |
| 2 | `processor/`: SPI, base astratta (`process()` final), registry con alias, `ParamSpec`/`ProcessorParams`, eccezioni classificate, i 2 stub in `mode=COLLECT` | fatta |
| 3 | `dao/` (7 classi, tutto lo SQL), `service/`: extractor MIME, matcher, `ImapMailReader`, `MailIngestService`, recorder transazionale, retry/sweeper, retention, client BERLink (reale + registrante), notifiche admin, servizi UI | fatta |
| 4 | `controller/` (5) + i 4 template Thymeleaf (`logs`, `rules`, `ruletest`, `accounts`) | fatta |
| 5 | `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `Taskfile.yml`, `.gitignore`, `README.md`, `CLAUDE.md` | fatta |

## Verifiche eseguite davvero

- `mvn -B test`: 98 test verdi, inclusi i passi dell'IT GreenMail — poll ed esito, **la mail non
  risulta letta né cancellata dopo un poll completo**, idempotenza, high-water mark (trappola
  `LASTUID`), recupero dei claim orfani, retry con backoff, `NO_RULE`, reset di UIDVALIDITY.
- Avvio reale: schema creato, `journal_mode=wal`, `foreign_keys=1`, le 4 pagine servite,
  `/api/health/ready` risponde `{"status":"UP","checks":{db,rules,credentials}}` e 401 senza chiave.
- API provate da `curl`: creazione casella (la password torna solo come `passwordSet`), regola
  rifiutata perché il processore esige un allegato, regola catch-all rifiutata, processore ignoto →
  400, `/ruletest` manuale (mostra il *perché no* per campo) e upload `.eml` con dry-run a zero
  chiamate HTTP.
- **Immagine Docker costruita davvero** (337 MB): i 98 test girano *dentro* la build (`SKIP_TESTS=false`
  di default). Container avviato: `/api/health/live` UP, `/api/health/ready` UP, le 4 pagine servite,
  HEALTHCHECK `healthy`, DB su `/app/data/switchmail.db` con WAL e foreign key attive.
  `docker stop` → shutdown graceful in 0,3 s (il SIGTERM arriva al JVM: ENTRYPOINT in exec form).
- `docker compose config` valido; senza i segreti si ferma con il messaggio giusto.
- Seam verificati con grep: nessun `JdbcTemplate` fuori da `dao/` + `SwitchMailDbConfig` +
  `SchemaMigrations`; `jakarta.mail` solo in `ImapMailReader` e `MailContentExtractor`.

## Quattro cose scoperte sul campo (già gestite, non reintrodurle)

1. **URL JDBC SQLite**: xerial applica solo le PRAGMA che conosce e lascia le altre *attaccate al nome
   del file* — `wal_autocheckpoint=2000` creava un DB chiamato `switchmail.db?wal_autocheckpoint=2000`.
   Rimossa; `SchemaMigrations.verifyOpenedFile()` confronta a ogni avvio il file aperto con quello
   configurato.
2. **`rs.getObject()` su SQLite** restituisce `Integer` per gli INTEGER piccoli: il cast a `Long`
   esplodeva a runtime. Le letture nullable passano da `dao/JdbcReads` (getLong/getInt + wasNull).
   Stesso motivo per `JsonUtil.readLongList`: Jackson deserializza i numeri come Integer.
3. **Budget regex**: su JDK 17 gli "evil regex" da manuale (`(a+)+$`, `^(([a-z])+.)+[A-Z]([a-z])+$`)
   sono ormai lineari. Quello che morde davvero è `(.*a){20}$`: 29 caratteri = 8,4 s e 3,2 miliardi di
   accessi, 33 caratteri = 130 s. È il caso usato in `FieldCheckTest`.
4. **GreenMail non riproduce il comportamento di `peek`**: non marca come letto nemmeno senza. Il test
   negativo previsto dal piano non direbbe nulla, quindi la property è inchiodata da
   `ImapSessionPropertiesTest`, che fallisce se qualcuno la rimuove.

## Cosa resta aperto (per costruzione, non per mancanza di tempo)

- **Formati**: il `.txt` di partenza treno e il body del forecast non sono definiti. I due processori
  girano in `mode=COLLECT`: acquisiscono, archiviano il MIME e registrano uno `SKIPPED` leggibile.
  Il ciclo per chiuderli: `/logs` → **Scarica .eml** → `src/test/resources/mail/` → test del parser →
  `mode=PROCESS` → **Riprova** sulla stessa mail.
- **Endpoint BERLink reali** per ciascun tipo di mail.
- **Casella e credenziali vere**: servono host, porta, service account e la scelta tra modalità A
  (casella dedicata con forward) e B (READ_ONLY sulla casella del dipendente) — checklist nel §6 del
  design doc.
- **`X-Idempotency-Key`**: il client lo manda già, BERLink non lo onora. Finché non lo farà, il
  sistema resta at-least-once nella finestra "crash dopo la chiamata, prima della riga di log".

## Note operative

- `SwitchMail/` è ora tracciata nel repo `Processors` (branch `FlowCenter`), commit `a292b6a`
  "SwitchMail primo push". **Da sistemare**: quel commit include 17 file di `target/` (artefatti di
  build: `classes/`, `surefire-reports/`, `maven-status/`). Il `.gitignore` aggiunto dopo non li
  rimuove da solo. Nessun `.db` e nessun segreto sono stati committati. Per ripulire:
  `git rm -r --cached SwitchMail/target` e committare.
- Il working tree del repo ha molte modifiche non correlate su altri processori: committare solo i
  file di `SwitchMail/`.
- Per avviare in locale servono `SWITCHMAIL_CREDS_KEY` (`openssl rand -base64 32`), `BERLINK_API_KEY`,
  `HEALTH_API_KEY`: nessuno ha un default, di proposito.

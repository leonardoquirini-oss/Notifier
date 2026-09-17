# SwitchMail — stato implementazione

> Aggiornato: 2026-09-17, sessione interrotta (batteria).
> Per riprendere: apri Claude Code da `Processors/SwitchMail/` e scrivi
> **"Leggi IMPLEMENTATION_STATUS.md e continua da dove eri rimasto"**.

Build: `mvn -B test` → **40 test, 0 failure**. `mvn -B compile` verde.
Smoke test di boot eseguito: schema creato, 7 tabelle, 12 indici, `schema_version = 1`.

## Decisione presa in questa sessione

Niente ActiveJDBC: resta SQLite + `JdbcTemplate`, con un **seam di portabilità** esplicito
(package `dao/`, timestamp in Java da `Clock`, claim `ON CONFLICT … DO NOTHING RETURNING id`,
marcatori `-- PG:` nello schema, nuova §8 "Portabilità Postgres" in `IMPLEMENTATION_PLAN.md`).
Motivi: `Base.open` lega la connessione a un thread-local (incompatibile con scheduler + pool 1),
transazioni fuori da Spring, instrumentation in ogni build, e nessuna portabilità reale in cambio
(i model dei fratelli sono PG-locked da `@IdGenerator("nextval(...)")`).
`IMPLEMENTATION_PLAN.md` è già stato patchato di conseguenza (Passo 0 completato).

## Fatto

**Tappa 1 — scaffold (completa)**
- `pom.xml` (Boot 3.4.3, Java 17, `it.gruppobernardini:switch-mail:1.0.0`, GreenMail 2.1.3 test-only)
- `SwitchMailApplication` (+ bean `Clock`), `config/SwitchMailProperties`, `config/SwitchMailDbConfig`,
  `config/SchemaMigrations`, `util/TimestampUtil`, `resources/application.yml`,
  `resources/db/schema.sql` (7 tabelle, 12 indici, commenti `-- PG:`)

**Tappa 2 — `processor/` (completa)**
- SPI `MailSubProcessor`, `AbstractMailSubProcessor` (`process()` final), `MailSubProcessorRegistry`,
  `MailContext`, `ProcessingOutcome`, `ParamSpec`, `ProcessorParams`, gerarchia eccezioni,
  `TrainDepartureProcessor` + `TerminalForecastProcessor` (mode COLLECT/PROCESS)
- `util/RegexUtil`, `util/JsonUtil`, `dto/ProcessorDescriptor`
- Test: `FieldCheckTest`, `ProcessorParamsTest`, `ProcessingOutcomeTest`,
  `MailSubProcessorRegistryTest`, `StubProcessorsTest`, `support/MailFixtures`

**Tappa 3 — in corso**
- `model/`: `FieldCheck`, `RuleConfig`, `MailAttachment`, `ParsedMail`, `MailAccount`,
  `MailFolderState`, `ProcessingLogEntry`, `FieldVerdict`, `RuleMatchResult`, `MatchedRules`,
  enum `AccessMode`/`PostAction`/`ProcessingStatus`/`AttemptStatus`/`TriggeredBy`
- `util/CredentialCipher` (AES-256-GCM, blob `[0x01][IV 12][ct||tag]`)
- `dao/`: `MailAccountDao`, `MailFolderStateDao`, `RuleDao`, `ProcessingLogDao` (claim atomico incluso),
  `ProcessingAttemptDao`, `RawMailDao`
- `dto/LogFilter`
- `service/`: `BerlinkApiClient` (interfaccia), `MailContentExtractor`, `RuleMatcher`

## Da fare — riprendere da qui

1. **`service/` mancanti**: `DefaultBerlinkApiClient` + `RecordingBerlinkApiClient` + factory legata al
   `MailContext`, `config/BerlinkApiConfig` (RestTemplate + interceptor `X-API-Key`, copiare da
   `TFPEventIngester/.../config/BerlinkApiConfig.java`), `AdminNotifier`
   (`POST /api/notifications/send`, vedi `EventControlProcessor/.../notify/NotificationClient.java`),
   `RawMailStore`, `RetentionService`, `ImapMailReader` (con `mail.imaps.peek=true`),
   `MailIngestService` (claim → match → processore → record), `RetryScheduler` + recupero claim stantii,
   `MailPollService`, `MailPollScheduler`, `config/SchedulingConfig`, `ProcessorValidationRunner`,
   `AccountService`, `RuleConfigService`, `ProcessingLogService`, `RuleTestService`
2. **Test tappa 3**: `MailContentExtractorTest` (fixture `.eml`), `RuleMatcherTest`,
   `CredentialCipherTest`, `BerlinkApiClientClassificationTest`, `MailIngestServiceTest`,
   `ImapPollGreenMailIT` (11 passi, incluso l'assert `!msg.isSet(SEEN)` e il test negativo con
   `peek=false`)
3. **Tappa 4**: `controller/` + i 4 template Thymeleaf (`logs`, `rules`, `ruletest`, `accounts`)
4. **Tappa 5**: `Dockerfile`, `.dockerignore`, `docker-compose.yml` (publish su `127.0.0.1:8105`),
   `Taskfile.yml`, `CLAUDE.md`, `README.md`, `.gitignore`

## Due trappole verificate sul campo (già gestite, non reintrodurle)

- **URL JDBC SQLite**: xerial applica solo le PRAGMA che conosce e lascia le altre *attaccate al nome
  del file*. `wal_autocheckpoint=2000` creava un DB chiamato `switchmail.db?wal_autocheckpoint=2000`.
  Rimossa; `SchemaMigrations.verifyOpenedFile()` ora confronta a ogni avvio il file aperto con quello
  configurato e fallisce se divergono.
- **Budget regex**: su JDK 17 gli "evil regex" da manuale (`(a+)+$`, `^(([a-z])+.)+[A-Z]([a-z])+$`)
  sono ormai lineari. Quello che morde davvero è `(.*a){20}$`: 29 caratteri = 8,4 s e 3,2 miliardi di
  accessi, 33 caratteri = 130 s e 48,7 miliardi. È il caso usato in `FieldCheckTest`.

## Note operative

- Git: `SwitchMail/` è una sottodirectory **non tracciata** del repo `Processors` (branch `FlowCenter`).
  Nessun commit fatto in questa sessione. Il working tree ha molte modifiche non correlate su altri
  processori: committare solo i file di `SwitchMail/`.
- Per far partire l'app in locale servono: `SWITCHMAIL_CREDS_KEY` (`openssl rand -base64 32`),
  `BERLINK_API_KEY`, `HEALTH_API_KEY` — nessuno ha un default, di proposito.

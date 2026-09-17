# SwitchMail — Piano di implementazione

> **Come riprendere questo lavoro.**
> Apri una sessione Claude Code da `Processors/SwitchMail/` e scrivi:
> **"Leggi `IMPLEMENTATION_PLAN.md` e implementa tutto."**
> Il piano è autosufficiente: contiene decisioni, schema, firme delle classi, convenzioni della piattaforma
> e criteri di verifica. Non serve rifare l'esplorazione del codebase.
> Per implementare solo una parte: *"…implementa la tappa N"* (le 5 tappe sono in §6).
>
> Companion: `MAIL_PROCESSOR_DESIGN.md` (il design approvato, il *perché*). Questo file è il *come*.
> Stato: **piano approvato, implementazione da avviare.** Nessun codice scritto.

---

## Context

`Processors/SwitchMail/` contiene oggi solo `MAIL_PROCESSOR_DESIGN.md` (design approvato, nessun commit git). Il documento descrive un microservizio che sostituisce un lavoro manuale: oggi un dipendente legge a mano le mail di aggiornamento inviate da sistemi esterni alla sua casella Exchange on-prem e agisce di conseguenza. Serve un automatismo che legga le stesse mail **senza toccare la casella**, le classifichi per mittente+oggetto, e per ogni tipo esegua un'azione verso BERLink.

Rispetto al design doc si aggiungono tre requisiti, che chiudono anche il punto aperto §8 ("regole in YAML statico vs tabella DB"):

1. Le regole stanno **in DB (SQLite)**, non in YAML, con una **UI di configurazione**.
2. Il sub-processore è **una classe Java** con gerarchia esplicita (interfaccia + base astratta), selezionabile dalla UI.
3. UI semplice nello stile di TFPGateway / TFPEventIngester — niente SPA.

Decisioni prese:

| Tema | Scelta |
|---|---|
| Package root | **`it.gruppobernardini.switchmail`** |
| Credenziali IMAP | in SQLite, editabili da UI, **password cifrata AES-GCM** con chiave da env `SWITCHMAIL_CREDS_KEY`; mai restituita in chiaro (solo `passwordSet: true/false`) |
| Numero caselle | **N account** gestiti da UI, ogni regola legata a un account (o a tutti) |
| Azione post-parsing | **interamente dentro il sub-processor Java** — nessun endpoint/template HTTP configurabile nella regola |
| Auth UI | **nessuna**, come i servizi fratelli (protezione = rete Docker interna) |

Esito atteso: servizio buildabile e testabile **subito**, indipendente dall'IT e dai formati mail ancora ignoti. Le due classi concrete (`TrainDepartureProcessor`, `TerminalForecastProcessor`) partono in modalità **COLLECT** — acquisiscono e archiviano il MIME grezzo senza agire — così la fase di stub diventa la fase di **scoperta dei formati**: dopo una settimana si hanno `.eml` reali scaricabili dalla UI da usare come fixture di test.

---

## Stack e convenzioni

Ricalca i fratelli (`TFPGateway`, `TFPEventIngester`) senza reimportarne i difetti.

- Maven, `spring-boot-starter-parent` **3.4.3**, Java **17**, `it.gruppobernardini:switch-mail:1.0.0`, package root **`it.gruppobernardini.switchmail`**, sottopackage piatti (`config`, `controller`, `dto`, `model`, `processor`, `service`, `util`).
  *Nota*: i fratelli usano `com.containermgmt`; qui il `groupId` segue il package root richiesto, così coordinate Maven e package restano allineati.
- Dipendenze: `spring-boot-starter-web`, `-thymeleaf`, `-actuator`, `-jdbc`, `-mail` (porta jakarta.mail + Angus con versione gestita; nessun `spring.mail.*` configurato ⇒ nessun `JavaMailSender` creato), `org.xerial:sqlite-jdbc:3.45.3.0`, `jackson-databind`, `lombok` (optional), `spring-boot-configuration-processor`. Test: `spring-boot-starter-test` + `com.icegreen:greenmail-junit5:2.1.x`.
  **Niente ActiveJDBC e niente plugin di instrumentation**: `Base.open/close` lega la connessione a una mappa statica *per-thread* (`ConnectionsAccess`), e qui i thread sono molti (Tomcat + `ThreadPoolTaskScheduler(3)` per poll/retry/sweeper + runner di boot) mentre il pool SQLite è di **1** connessione — una connessione thread-bound non rilasciata bloccherebbe l'intero servizio; le sue transazioni (`Base.openTransaction`) stanno fuori da `DataSourceTransactionManager`, mentre il passo 5 della pipeline (§3) vuole un solo `@Transactional`; e l'instrumentation a `process-classes` entrerebbe in ogni run di test. Non darebbe nemmeno la portabilità che sembra promettere: non ha layer DDL (lo schema si scrive a mano comunque) e i model dei fratelli sono PG-locked nell'annotazione (`@IdGenerator("nextval('s_evt_unit_positions')")`). Stessa scelta, stesso motivo, già presa nella piattaforma: `TFPEventIngester/CLAUDE.md:401`. Si usa `JdbcTemplate`, con la portabilità gestita dal seam di §8.
- UI: Thymeleaf, template piatti e autonomi in `src/main/resources/templates/*.html`, header + dropdown hamburger ripetuti, Bootstrap 5.3.2 + bootstrap-icons 1.11.3 da jsDelivr, nessun `static/`, nessun npm. Helper `jfetch` / `escHtml` copiati **verbatim** da `TFPEventIngester/src/main/resources/templates/geofencing.html`.
- Controller: `@Controller` a livello classe + `@ResponseBody` per-metodo per la JSON API della pagina (`/<pagina>/api/...`); `@RestController` solo per `/api/health`. Constructor injection esplicita, `private final`, `@Slf4j`, niente `@Autowired`, niente `@Valid`. Errori: il service lancia `IllegalArgumentException`, un `@ExceptionHandler` **locale al controller** la mappa a 400 `{"error": msg}` (niente `@ControllerAdvice` — è la convenzione della piattaforma, va annotata in `CLAUDE.md` perché non venga "corretta" più avanti). CRUD con `Map<String,Object>` in/out + helper privati `str()`/`intg()`/`bool()`, come `GeofencingController`.
- **Tutto lo SQL vive in `dao/`.** `JdbcTemplate` compare solo nelle classi `dao/*Dao` e in `config/SwitchMailDbConfig`; i service dipendono dai DAO, mai dal template. È il seam su cui si fa un eventuale porting a Postgres (§8) e va annotato in `CLAUDE.md`.
- Porta host **8105** (8000, 8082, 8085, 8095, 8161, 61616, 5205 già occupate).

### File di riferimento da tenere aperti
- `TFPEventIngester/src/main/java/com/containermgmt/tfpeventingester/config/GeofencingDbConfig.java` — wiring SQLite
- `TFPEventIngester/src/main/java/com/containermgmt/tfpeventingester/controller/GeofencingController.java` — stile CRUD
- `TFPEventIngester/src/main/resources/templates/geofencing.html` — shell + `fetch`, helper `jfetch`/`escHtml`
- `TFPGateway/src/main/resources/templates/gateway.html` — form + flash `redirect:`
- `FlowCenter/app/core/schema.sql`, `FlowCenter/app/core/crypto.py`, `FlowCenter/app/core/db.py` — schema, PRAGMA, segreti write-only

### Cache Maven locale
`angus-mail`, `sqlite-jdbc 3.45.3.0`, `spring-boot-starter-parent 3.4.3` già presenti in `~/.m2`. **GreenMail no** — la prima build richiede rete.

---

## 1. Gerarchia delle classi sub-processor

```
              MailSubProcessor            (interfaccia — contratto SPI)
                     ▲
        AbstractMailSubProcessor          (astratta — template method + helper)
                     ▲
       ┌─────────────┴─────────────┐
TrainDepartureProcessor    TerminalForecastProcessor
```

Un'interfaccia, una base astratta, nessun terzo livello. Entrambe, non solo una: l'interfaccia è il contratto (facile da mockare nei test), la base astratta rende `process()` **`final`** così il wrapper try/catch che classifica le eccezioni non è aggirabile — stesso motivo per cui `AbstractStreamProcessor` di TFPEventIngester lo fa.

### Tre correzioni allo sketch del design doc

- **Via `supports(rule)`.** Se la UI lega la regola al processore per id, `supports()` è un secondo meccanismo di selezione implicito che può contraddire quello esplicito. Sostituito da `validateRule(rule, params)`: il processore non *sceglie*, *dichiara* di saper lavorare con quella regola, e lo fa **al salvataggio della regola** (400 nella UI, feedback immediato) oltre che al boot.
- **Via `void process(...)`.** Un ritorno `void` rende il successo silenzioso indistinguibile da un no-op silenzioso — esattamente ciò che il doc vieta ("nessun silent failure").
- **`ParsedMail` non è una `jakarta.mail.Message`.** La mail viene **materializzata in un record immutabile prima di chiudere la folder**; i processori non importano mai `jakarta.mail`. Altrimenti la connessione IMAP resterebbe aperta durante la chiamata HTTP a BERLink (Exchange la chiude), i fetch MIME lazy avverrebbero a caso, e ogni unit test richiederebbe un server IMAP.

### L'interfaccia

```java
public interface MailSubProcessor {
    String id();                                                  // costante scritta a mano, es. "train-departure"
    default Set<String> aliasIds() { return Set.of(); }           // id precedenti, dopo una rinomina
    String displayName();
    default String description() { return ""; }
    default List<ParamSpec> paramSpecs() { return List.of(); }
    default void validateRule(RuleConfig rule, ProcessorParams params) { }

    /** Ritorna SUCCESS o SKIPPED. Ogni fallimento si lancia, non si ritorna. */
    ProcessingOutcome process(MailContext ctx);
}
```

**L'identificatore stabile** è una `public static final String ID` scritta a mano, **mai derivata dal nome della classe** — così sopravvive a rinomine di classe e spostamenti di package. Scartati: FQCN (si rompe a ogni move/rename, e finirebbe in una tendina utente), nome del bean Spring (stessa fragilità), enum (due file da toccare per ogni processore), id numerico in tabella (una migrazione dati per aggiungere un processore).

Le rinomine dell'**id stesso** si gestiscono con `aliasIds()`: il registry indicizza anche gli alias, risolverne uno logga `WARN ... aggiornare la regola #N`, e la API di salvataggio riscrive sempre l'id canonico, così gli alias decadono da soli. Il registry lancia al boot su id duplicato **o** collisione alias/canonico.

### Registry

```java
@Component
public class MailSubProcessorRegistry {
    public MailSubProcessorRegistry(List<MailSubProcessor> processors) { ... }  // Spring inietta tutte le impl
    @PostConstruct void init();                     // indicizza; duplicati -> IllegalStateException
    Optional<MailSubProcessor> find(String id);     // risolve alias, WARN
    MailSubProcessor require(String id);            // TerminalMailProcessingException("UNKNOWN_PROCESSOR")
    List<ProcessorDescriptor> descriptors();        // payload UI, ordinato per displayName
}
public record ProcessorDescriptor(String id, String displayName, String description, List<ParamSpec> paramSpecs) {}
```

### Tendina UI e validazione al boot

`GET /rules/api/processors` → `descriptors()`. `rules.html` la carica una volta, costruisce la `<select>` e rigenera il form parametri a ogni `change`. Se una regola punta a un id assente, la select mostra un'opzione sintetica **disabilitata e selezionata** `"<id> — NON DISPONIBILE"` e il Save è bloccato: la UI non riscrive mai silenziosamente un binding rotto.

**Al boot NON si fallisce.** È l'unica deviazione dal riflesso fail-fast, ed è motivata: la UI che ripara un `processor_id` rotto gira *dentro questo processo*: un boot fallito lascia l'operatore senza lo strumento per aggiustare il dato, costretto a entrare in `sqlite3` dentro un volume Docker. Risposta a strati:

| Momento | Comportamento |
|---|---|
| Salvataggio regola | id ignoto → **400** `{"error":"processore sconosciuto: 'x'"}`. Un id rotto può nascere solo da una rinomina lato codice. |
| Boot (`ProcessorValidationRunner`) | `log.error` per regola + un `log.error` aggregato + una notifica admin + un dettaglio `DOWN` in `/api/health/ready` sotto `checks.rules`. **Il boot prosegue.** |
| Lista regole | ogni riga porta `processorAvailable: false` → badge rosso |
| Runtime, mail che matcha una regola rotta | `TerminalMailProcessingException("UNKNOWN_PROCESSOR")` → riga `DEAD_LETTER`, MIME grezzo archiviato, admin notificato. **Mai uno skip silenzioso.** |

### Parametri per-regola: `ParamSpec` + form auto-generato

**Si costruisce `ParamSpec`**, non una textarea JSON grezza. L'argomento che di solito uccide gli auto-form ("pochi utenti, dagli il JSON") qui si inverte: con i formati ignoti, i parametri sono l'**unica** manopola tra "arriva la mail" e "si cambia il codice", quindi verranno modificati spesso e da chi non ha scritto la classe. Con una textarea, un typo `trainNo` vs `train_no` si manifesta ore dopo come mail dead-lettered.

E poi: la metà costosa non è il form, è `ProcessorParams.of(raw, specs)` — required-check, coercizione di tipo, rifiuto delle chiavi sconosciute — che **serve comunque lato server** (funziona anche chiamando la API direttamente). Una volta che esiste, il generatore di form sono ~40 righe di JS.

La disciplina che lo tiene economico: **il modello è piatto e chiuso.** Niente oggetti annidati, niente array di oggetti, niente visibilità condizionale, niente validazione cross-field. Un processore che ha bisogno di struttura dichiara un parametro `type = JSON` e valida il blob da sé. In più un toggle **"JSON grezzo"** nel form come via di fuga.

```java
public record ParamSpec(String key, String label, ParamType type, boolean required,
                        String defaultValue, List<String> options, String help) {
    public enum ParamType { STRING, TEXT, INT, BOOL, SELECT, JSON }
    // factory: string(), text(), integer(), bool(), select(), json()
}

public final class ProcessorParams {
    public static ProcessorParams of(Map<String,Object> raw, List<ParamSpec> specs);  // IllegalArgumentException
    public static ProcessorParams ofJson(String paramsJson, List<ParamSpec> specs);
    public String requireString(String k);  public String  getString(String k, String def);
    public int    requireInt(String k);     public int     getInt(String k, int def);
    public boolean getBool(String k, boolean def);  public Map<String,Object> asMap();
}
```

`of()` **rifiuta le chiavi sconosciute**: un parametro che il processore non legge è una bugia nella UI, e intercettarlo al salvataggio è la differenza tra "la config è sbagliata" e "il codice è sbagliato".

### Dove vive il MIME / allegati / charset

In un `@Component MailContentExtractor` nel package `service`, **non** come metodi della base astratta. Tre motivi: (1) gira **una volta per mail, prima di qualsiasi processore**, mentre la folder è aperta — la pipeline ne ha bisogno, non solo i processori; (2) deve essere testabile da fixture `.eml` senza processore e senza Spring; (3) serve anche a `/ruletest` (modalità upload `.eml`) e a `/logs` ("vedi parsed").

Responsabilità concentrate lì: walk ricorsivo `multipart/*`; preferenza `text/plain` con fallback `text/html` ridotto a testo; `Content-Disposition: attachment` **oppure** filename presente ⇒ allegato; `MimeUtility.decodeText` per i filename RFC 2047; catena charset *dichiarato → `windows-1252` → UTF-8 con `REPLACE`* (i mittenti Exchange italiani emettono CP1252 molto più spesso di quanto lo dichiarino); cap per-allegato (`switchmail.mail.max-attachment-bytes`, default 5 MB) che **tronca e registra un warning** invece di andare in OOM.

`ParsedMail` porta `List<String> extractionWarnings`: fallback di charset, troncamento, parte non decodificabile finiscono in `mail_processing_log.message` e sono visibili in UI. Il charset indovinato di nascosto è l'archetipo del silent failure.

La base astratta espone solo helper sottili, così le sottoclassi non vedono mai un `byte[]` o un `Charset`:
```java
protected String requireAttachmentText(MailContext ctx, String fileNameRegex);  // Terminal se assente/ambiguo
protected String requireBodyText(MailContext ctx);                             // Terminal se vuoto
protected List<String> lines(String text);
```

### Tipo di ritorno — il "nessun silent failure" reso strutturale

```java
public record ProcessingOutcome(Status status, String message, String extractedJson,
                                String actionRef, Map<String,Object> details) {
    public enum Status { SUCCESS, SKIPPED }                     // nessuna costante di fallimento
    public static ProcessingOutcome success(String message, Object extracted, String actionRef);
    public static ProcessingOutcome skipped(String reason);
}
```

Niente costruttore pubblico, e l'enum non ammette fallimenti: **è impossibile segnalare un errore ritornando**. Punti di applicazione:

1. `Status` ha solo `SUCCESS` / `SKIPPED`.
2. `success()` esige `extracted` non-null → non puoi dichiarare successo senza registrare *cosa* hai estratto (finisce in `extracted_json`, visibile in UI).
3. `success()` esige `message` non-blank; `skipped()` esige una ragione non-blank — uno skip è un evento consultabile, non un ritorno muto.
4. `process()` è `final`; un `null` di ritorno diventa `TerminalMailProcessingException("NULL_OUTCOME")`.
5. Il catch-all della pipeline trasforma qualsiasi `Throwable` in `DEAD_LETTER` + notifica admin. **Non esiste un percorso da "mail presa in carico" a "nessuna riga di log"**: la riga di claim è inserita *prima* dell'elaborazione, quindi anche un kill del JVM lascia una riga `IN_PROGRESS` che lo sweeper recupera.

Chiuso anche il silenzio a livello parser (il classico `if (m.find()) {...}` senza `else`): la base dà al percorso rumoroso la grafia più corta — `requireGroup(pattern, text, group, cosa)`, `parseDate(s, pattern, cosa)`, `terminal(code, msg)`, `retry(code, msg, cause)`.

### Base astratta

```java
public abstract class AbstractMailSubProcessor implements MailSubProcessor {
    @Override public final ProcessingOutcome process(MailContext ctx) {
        try {
            ProcessingOutcome out = handle(ctx);
            if (out == null) throw new TerminalMailProcessingException("NULL_OUTCOME", id()+" ha restituito null", null);
            return out;
        } catch (MailProcessingException e) { throw e; }                       // già classificata
        catch (RuntimeException e) { throw new TerminalMailProcessingException("UNEXPECTED", ..., e); }
        finally { log.debug(...); }
    }
    protected abstract ProcessingOutcome handle(MailContext ctx);
    protected BerlinkApiClient api(MailContext ctx) { return ctx.dryRun() ? dryRunClient : liveClient; }
}
```

`api(ctx)` è **l'unico** accesso alla rete — la sottoclasse non ha un campo `BerlinkApiClient`. Un processore quindi **non può fisicamente** fare una chiamata BERLink reale dalla schermata di test.

### Oggetti di dominio

```java
public record MailAttachment(String fileName, String contentType, Charset charset, byte[] content) {
    String asText();  long sizeBytes();
}

public record ParsedMail(
        long accountId, String accountName, String folder,
        long uidValidity, long uid, String internetMessageId,
        String fromAddress, String fromDisplayName, List<String> toAddresses,
        String subject, Instant sentAt, Instant receivedAt,
        String bodyText,                      // mai null; "" se davvero vuoto
        String bodyHtml,                      // null se assente
        List<MailAttachment> attachments, int rawSizeBytes,
        List<String> extractionWarnings) {
    Optional<MailAttachment> attachment(String fileNameRegex);
    List<String> attachmentNames();
    String dedupKey();                        // "<accountId>:<folder>:<uidValidity>:<uid>"
}

public record MailContext(ParsedMail mail, RuleConfig rule, ProcessorParams params,
                          int attempt, boolean dryRun) {}
```

### Retry vs terminale

```java
public abstract class MailProcessingException extends RuntimeException {
    String errorType();            // codice stabile: BAD_FORMAT, BERLINK_5XX, ...
    abstract boolean retryable();
}
// RetryableMailProcessingException (true) / TerminalMailProcessingException (false)
```

Tre sedi, in ordine di autorità:

1. **`BerlinkApiClient` classifica l'HTTP gratis**: timeout/`IOException`/502/503/504/429 → Retryable; 4xx (tranne 429) → Terminal; altri 5xx → Retryable. Un autore che chiama solo `api(ctx).post(...)` ottiene la semantica giusta senza pensarci — la proprietà più importante, perché la maggior parte dei processori fallirà solo su HTTP.
2. **Il processore**, per le decisioni di dominio: allegato illeggibile → `terminal("BAD_FORMAT")`; entità non ancora creata in BERLink → `retry("DEPENDENCY_MISSING")`.
3. **Il catch-all della pipeline**: non classificato → **terminale**.

Default "ignoto ⇒ terminale" (contrario alla saggezza comune) perché: una NPE in un parser non migliora al terzo tentativo, ma *fa partire* tre chiamate BERLink e tre notifiche. E la controbiezione usuale ("un guasto transitorio mascherato da eccezione strana ⇒ terminale perde la mail") non si applica: **nulla va perso** — il MIME grezzo è archiviato, la riga sta nella dead-letter e `/logs` ha un bottone **Riprova**. Terminal-by-default è sicuro *proprio perché* esiste quel bottone.

Backoff: `next_retry_at = now + min(60s · 2^(attempt-1), 30min)` ±10% jitter, fino a `mail_rule.max_attempts` (default 3); esaurito → `DEAD_LETTER`.

### Gli stub, resi utili

Uno stub che ritorna `success` è una bugia; uno che lancia sempre riempie la dead-letter di rumore. Entrambi noti-cattivi. Invece ogni stub dichiara un parametro `mode`:

```java
@Component
public class TrainDepartureProcessor extends AbstractMailSubProcessor {
    public static final String ID = "train-departure";
    @Override public String id() { return ID; }
    @Override public String displayName() { return "Avvisi partenza treno (allegato .txt)"; }
    @Override public String description() {
        return "Estrae numero treno e data dall'allegato .txt. Formato non ancora definito.";
    }
    @Override public List<ParamSpec> paramSpecs() {
        return List.of(
            ParamSpec.select("mode","Modalità",true,"COLLECT",List.of("COLLECT","PROCESS"),
                "COLLECT: archivia la mail per analisi senza agire. PROCESS: parsing reale (non implementato)."),
            ParamSpec.string("attachmentPattern","Allegato (regex nome file)",true,"(?i).*\\.txt$",null),
            ParamSpec.select("dateFormat","Formato data nell'oggetto",true,"dd/MM/yyyy",
                List.of("dd/MM/yyyy","yyyy-MM-dd"),null));
    }
    @Override public void validateRule(RuleConfig rule, ProcessorParams p) {
        if (!rule.requireAttachment())
            throw new IllegalArgumentException("Questo processore richiede un allegato: abilita 'Richiedi allegato'.");
    }
    @Override protected ProcessingOutcome handle(MailContext ctx) {
        String text = requireAttachmentText(ctx, ctx.params().requireString("attachmentPattern"));
        if ("COLLECT".equals(ctx.params().getString("mode","COLLECT")))
            return ProcessingOutcome.skipped("Modalità COLLECT: allegato acquisito ("+text.length()+" char), "
                + "formato non ancora definito. MIME grezzo archiviato per l'analisi.");
        throw terminal("NOT_IMPLEMENTED","Parsing .txt treno non ancora implementato");
    }
}
```

`TerminalForecastProcessor` è identico con `requireBodyText`.

---

## 2. Schema SQLite

In `src/main/resources/db/schema.sql`, eseguito con `ScriptUtils.executeSqlScript` — **non** inline in un metodo `@Bean` come fa `GeofencingDbConfig`: 7 tabelle e 12 indici inline sono illeggibili e non diffabili.

Timestamp **TEXT ISO-8601 UTC con millisecondi** (`2026-09-17T14:03:11.482Z`), ordinabili lessicograficamente. **Prodotti in Java** da `util/TimestampUtil` sopra un bean `Clock` iniettato — **nessun `DEFAULT` di colonna con `strftime`/`datetime('now')`**. Tre motivi: `datetime('now')` ha risoluzione al secondo e senza marcatore di timezone (due mail nello stesso secondo diventano non ordinabili); `strftime` è l'SQLite-ismo più diffuso e legherebbe a SQLite ogni INSERT (§8); e con il `Clock` i test 8–9 dell'IT GreenMail avanzano il tempo invece di dormire. Vale per `created_at`, `updated_at`, `claimed_at`, `processed_at`, `resolved_at`, `stored_at`, `started_at`, `finished_at`, `next_retry_at`.

Convenzione nello schema: ogni costrutto non portabile porta sulla riga sopra un commento `-- PG: <equivalente Postgres>`. Sono esattamente i punti elencati in §8.

### `mail_account`
Colonne: `id`, `name UNIQUE`, `host`, `port` (993), `use_ssl`, `start_tls`, `trust_all_certs`, `username`, `password_encrypted BLOB`, `folder` ('INBOX'), `access_mode CHECK IN ('READ_ONLY','OWNED')`, `post_action CHECK IN ('NONE','MARK_SEEN','MOVE','DELETE')`, `post_action_folder`, `poll_cron`, `max_messages_per_poll` (50), `initial_lookback_days` (1), `connect_timeout_ms`, `read_timeout_ms`, `enabled`, `last_poll_at/_status/_error/_fetched`, `consecutive_failures`, `created_at`, `updated_at`.

```sql
-- La riga portante dello schema: un account READ_ONLY non può MAI mutare la casella
-- del dipendente. Invariante di dato, non solo di codice: nessun refactor futuro,
-- nessun chiamante API e nessuna riga editata a mano può romperla.
CHECK (access_mode = 'OWNED' OR post_action = 'NONE'),
CHECK (post_action <> 'MOVE' OR (post_action_folder IS NOT NULL AND post_action_folder <> ''))
```

`password_encrypted` è un BLOB `[0x01 versione][IV 12 byte][ciphertext || tag GCM 16 byte]`, chiave = 32 byte base64 da `SWITCHMAIL_CREDS_KEY`. Contratto write-only come FlowCenter: il `GET` ritorna `passwordSet` e mai il valore; la password si scrive solo con `POST .../accounts` (create) o `PUT .../accounts/{id}/password`. Il `PUT .../{id}` generico **non ha** il campo password, così un round-trip della UI non può svuotarla.

### `mail_folder_state`
`(account_id, folder)` PK, `uid_validity`, `last_uid`, `updated_at`. Derivabile da `MAX(uid)` sul log, ma una riga a due interi rende il confronto UIDVALIDITY una point-read invece di un'aggregazione su tabella che cresce, e dà all'handler di invalidazione un posto ovvio dove scrivere.

### `mail_rule`
`id`, `name UNIQUE`, `description`, `account_id` (NULL = tutti gli account), `enabled`, `priority` (100, **ASC**: numero più basso = valutata prima), `stop_on_match` (1), poi tre terne `FieldCheck` modellate su FlowCenter — `sender_pattern / sender_match / sender_case_sensitive`, idem `subject_*` e `attachment_*`, con `*_match CHECK IN ('EQUALS','CONTAINS','REGEX')` — più `require_attachment`, `processor_id`, `params_json` ('{}'), `max_attempts` (3), timestamp.

```sql
-- Nessun catch-all accidentale: almeno un campo deve vincolare qualcosa.
CHECK ( (sender_pattern IS NOT NULL AND sender_pattern <> '')
     OR (subject_pattern IS NOT NULL AND subject_pattern <> '')
     OR (attachment_pattern IS NOT NULL AND attachment_pattern <> '') )
```
Indici: `(enabled, priority, id)`, `(account_id)`, `(processor_id)`.

**Nessuna colonna endpoint / metodo / template HTTP** — per scelta l'azione vive nel processore Java; `params_json` è l'unico hand-off configurabile, governato da `paramSpecs()`.

### `mail_processing_log` — una riga per mail, è il registro di dedup
Identità dedup (`account_id`, `folder`, `uid_validity`, `uid`, `internet_message_id`); snapshot mail denormalizzato (`mail_from`, `mail_from_name`, `mail_subject`, `mail_sent_at`, `mail_received_at`, `attachment_names` JSON, `mail_size_bytes`) che sopravvive alla cancellazione dalla casella; snapshot binding (`rule_id ON DELETE SET NULL`, `rule_name`, `matched_rule_ids` JSON, `processor_id`); esito (`status`, `attempt`, `max_attempts`, `message`, `extracted_json`, `action_ref`, `warnings` JSON, `error_type`, `error_message`, `error_stack`, `duration_ms`); tempi (`claimed_at` NOT NULL, `processed_at`, `next_retry_at`, `resolved_at`, `resolved_note`, `created_at`).

```sql
status CHECK IN ('IN_PROGRESS','SUCCESS','SKIPPED','NO_RULE','RETRY_SCHEDULED','DEAD_LETTER','RESOLVED')

-- *** Il vincolo di dedup. ***
-- Lo UID è unico solo dentro (casella, folder, UIDVALIDITY): servono tutte e quattro le colonne.
-- Un INSERT ... ON CONFLICT DO NOTHING RETURNING id contro questo indice È il claim atomico:
-- 1 riga = la mail è nostra (e l'id arriva con la stessa statement), 0 righe = qualcuno ce l'ha già.
-- Niente race read-then-write, niente lock applicativo. Sintassi identica su Postgres (§8).
CREATE UNIQUE INDEX ux_mpl_dedup ON mail_processing_log(account_id, folder, uid_validity, uid);
```
Più indici su `(status, created_at DESC)`, `(account_id, created_at DESC)`, `(rule_id)`, `(internet_message_id)`, e due parziali `WHERE status='RETRY_SCHEDULED'` / `WHERE status='IN_PROGRESS'`.

Perché non dedup su `internet_message_id`: Exchange non ne garantisce la presenza su messaggi generati internamente o inoltrati, è fornito dal client e falsificabile, e la modalità A (forward) lo **riscrive** su alcune configurazioni. `UIDVALIDITY+UID` è l'identità RFC 3501 ed è ciò che il design doc prescrive; `internet_message_id` resta chiave **secondaria** usata solo dopo un reset UIDVALIDITY.

`NO_RULE` non è rumore: è il feed di scoperta. Finché i formati sono ignoti, "le mail che nessuno ha gestito" è la lista che dice quali regole scrivere. `/logs` parte filtrato su `DEAD_LETTER + NO_RULE` ("Da gestire").

### `mail_processing_attempt` — audit append-only
`log_id`, `attempt`, `rule_id`, `processor_id`, `status` (SUCCESS/SKIPPED/FAILED_RETRYABLE/FAILED_TERMINAL), `message`, `error_type`, `error_message`, `duration_ms`, `triggered_by CHECK IN ('POLL','RETRY_AUTO','RETRY_MANUAL')`, `started_at`, `finished_at`.

Tiene il log a una-riga-per-mail (così l'indice unico può *essere* il meccanismo di dedup) senza perdere la traccia: "tentativo 1 fallito BERLINK_5XX, tentativo 2 retry manuale riuscito". È anche l'unico posto dove atterrano i risultati per-regola quando `stop_on_match = 0`.

### `mail_raw` — MIME gzippato
`log_id` PK, `content_gzip BLOB`, `original_size`, `stored_at`. **Attivo di default** (`switchmail.mail.store-raw: true`, cap 2 MB, retention 30 giorni): i formati sono ignoti, e poter scaricare un `.eml` reale dal browser di log per metterlo in `src/test/resources/mail/` è l'affordance di debug con il rapporto valore/costo più alto del progetto. Rende anche il retry indipendente dal fatto che la mail sia ancora in casella. Gzip su mail testuali: 5–10×; a ~50 mail/giorno × 30 giorni × 20 KB sono pochi MB.

### `schema_meta`
`key` PK, `value`, `updated_at`. Contiene `schema_version`; `SchemaMigrations` applica passi additivi ordinati (il pattern `columnExists()` + `ALTER TABLE` di `GeofencingDbConfig`, ma versionato e loggato invece che un mucchio non ordinato di `if` come `_additive_migrations` di FlowCenter).

### Cosa succede se due regole matchano — esplicito
Insieme valutato: regole abilitate con `account_id = :accountId OR account_id IS NULL`, `ORDER BY priority ASC, id ASC`. Il tie-break su `id` rende l'ordinamento **totale e deterministico**: due regole a priorità 100 non si scambiano di posto tra un poll e l'altro.

- **Default `stop_on_match = 1`: vince la prima.** La valutazione si ferma. `rule_id` = quella regola, `matched_rule_ids = [id]`. Le regole successive non girano e non vengono loggate — ma `/ruletest` le mostra come "avrebbe matchato, oscurata dalla #N", che è dove l'informazione serve davvero.
- **`stop_on_match = 0`: la valutazione prosegue.** Tutte le regole che matchano girano **in ordine di priorità nello stesso tentativo**, producendo comunque **una sola** riga di `mail_processing_log` (dedup intatto): `rule_id` = la prima, `matched_rule_ids` = l'array completo, una riga `mail_processing_attempt` per regola. Stato complessivo `SUCCESS` solo se tutte riescono; **il primo fallimento aborta le rimanenti** e la mail va a `RETRY_SCHEDULED`/`DEAD_LETTER`. Fail-fast e non successo-parziale perché il retry rigira *tutte* le regole, e rigirare una regola già riuscita è sicuro solo se la chiamata BERLink è idempotente — cosa che non possiamo assumere. La UI mette un'icona di avviso accanto a `stop_on_match = 0` e lo scrive nell'help.
- **Nessuna regola matcha**: una riga `NO_RULE`, MIME archiviato. Mai scartata in silenzio.

---

## 3. Pipeline: poll IMAP, dedup, crash-safety

| Classe (`service/`) | Responsabilità |
|---|---|
| `MailPollScheduler` | `@Scheduled(cron)`, guardia anti-overlap, fan-out per account. Nessuna logica. |
| `MailPollService` | Per account: config → reader → ingester, aggiorna `mail_account.last_poll_*`. |
| `ImapMailReader` | Tutto `jakarta.mail`. Connette, apre, fetch per range di UID, **materializza** in `ParsedMail`, chiude. Non fa mai uscire una `Message`. |
| `MailContentExtractor` | Walk MIME, charset, allegati, warning. Prende una `MimeMessage`, ritorna `ParsedMail`. |
| `RuleMatcher` | Funzione pura. Niente Spring, niente DB. |
| `MailIngestService` | Claim → match → processore → record. Il cuore della crash-safety. |
| `RetryScheduler` | Recupero claim stantii + sweep dei retry scaduti. |
| `RetentionService` / `RawMailStore` / `AdminNotifier` / `BerlinkApiClient` (+ `Default`/`Recording`) / `RuleConfigService` / `ProcessorValidationRunner` | supporto |

### Scheduler
Guardia: una `ConcurrentHashMap<Long, AtomicBoolean>` **per account** (`compareAndSet`), non un flag globale — un host Exchange irraggiungibile bloccato su un read timeout di 30 s non deve affamare gli altri account. Più un `Semaphore(3)` globale perché N account non aprano N connessioni insieme.

`SchedulingConfig` imposta un `ThreadPoolTaskScheduler` esplicito (`poolSize=3`, prefisso `sm-sched-`). **Trappola**: il `TaskScheduler` di default di Spring è mono-thread, quindi lo sweeper dei retry si accoderebbe dietro un poll IMAP lento e i due interferirebbero in silenzio. Con `setWaitForTasksToCompleteOnShutdown(true)` + attesa 30 s, un SIGTERM durante un poll finisce la mail in volo invece di lasciare una riga `IN_PROGRESS` orfana.

Deploy a **una sola replica**: il dedup è già sicuro tra processi (lo garantisce l'indice unico), ma due repliche raddoppierebbero i login IMAP verso Exchange per zero benefici.

### READ_ONLY: quattro garanzie indipendenti che `\Seen` non venga settato
1. `folder.open(Folder.READ_ONLY)` emette `EXAMINE`, non `SELECT`.
2. **`props.put("mail.imaps.peek", "true")`** — quella che morde davvero. `getContent()` di jakarta.mail emette normalmente `FETCH BODY[...]`, che **setta \Seen lato server anche su una folder che credi read-only**, sui server che lo permettono. `peek=true` forza `BODY.PEEK[...]`. Da impostare **sempre**, in entrambe le modalità.
3. Assert dopo l'apertura: `if (accessMode == READ_ONLY && folder.getMode() != Folder.READ_ONLY) throw ...` — aborta il poll invece di rischiare.
4. `folder.close(false)` sempre: **un solo** call-site, in un `finally`, con `false` hardcoded.

Più il `CHECK` di §2 che rende `post_action <> 'NONE'` impossibile su un account READ_ONLY, più il test GreenMail che asserisce `!msg.isSet(Flags.Flag.SEEN)` dopo un poll completo.

Altre property: `mail.store.protocol=imaps`, `ssl.enable`, `connectiontimeout`/`timeout`/`writetimeout` dalla riga account, `ssl.checkserveridentity=true` (rilassata solo se `trust_all_certs`, che logga `WARN` a ogni poll così non lo si dimentica), `fetchsize=1048576`. `FetchProfile` con `UID` + `ENVELOPE` per lo screening di mittente/oggetto in un round-trip.

### Fetch incrementale
```java
long uidValidity = f.getUIDValidity();
MailFolderState st = folderStateDao.get(accountId, folder);
if (st == null)                              // primo poll: NON ingerire tutta la casella storica
    // ReceivedDateTerm(GE, now - initial_lookback_days), prendi il max UID come baseline
else if (st.uidValidity() != uidValidity)    // reset, vedi sotto
    handleUidValidityReset(...);
else
    Message[] msgs = f.getMessagesByUID(st.lastUid()+1, UIDFolder.LASTUID);
    // TRAPPOLA: getMessagesByUID(start, LASTUID) ritorna SEMPRE almeno il messaggio con UID
    // più alto, anche se start > di esso. Filtrare con `f.getUID(m) > st.lastUid()`,
    // altrimenti si rielabora l'ultima mail a ogni poll a vuoto.
```
Batch limitato a `max_messages_per_poll`, elaborazione per UID crescente, e `last_uid` avanzato **solo a fine batch, al massimo UID effettivamente *claimato*** (non al massimo riuscito). Se il processo muore a metà batch, `last_uid` è vecchio, il poll successivo rifà il fetch di quegli UID e l'indice unico scarta quelli già presi. Corretto, al costo di una FETCH in più.

### Reset UIDVALIDITY
Nessuna delle due opzioni ingenue va bene: rielaborare tutto rifà le azioni BERLink, saltare al nuovo high-water perde mail. Policy: `log.error` + notifica admin (è un evento operativo che un umano deve vedere) → upsert `mail_folder_state` con nuovo `uid_validity` e `last_uid = 0` → flag one-shot in memoria che, al poll successivo, prima del claim controlla anche `internet_message_id` (indice dedicato): hit → riga `SKIPPED` "già processata prima del reset UIDVALIDITY"; miss → elaborazione normale. Le mail più vecchie di `reset-lookback-days` (7) → `SKIPPED` con ragione `UIDVALIDITY_RESET_TOO_OLD`. Best-effort, ma ogni ramo è loggato e consultabile, e il modo di fallire è una riga `SKIPPED` visibile, non una doppia azione silenziosa.

### Ordinamento "elabora poi logga"
```
1. ImapMailReader materializza ParsedMail; folder.close(false); store.close().
   *** Da qui in poi nessun handle IMAP è aperto. ***
2. INSERT INTO mail_processing_log (..., status='IN_PROGRESS', attempt=0, claimed_at=:now)
     ON CONFLICT (account_id, folder, uid_validity, uid) DO NOTHING
     RETURNING id
   0 righe -> già presa in carico -> skip.     <-- LA decisione di dedup, una sola statement atomica
   1 riga  -> è nostra; RETURNING dà l'id senza un secondo round-trip.
   (SQLite: ON CONFLICT da 3.24, RETURNING da 3.35 - xerial 3.45.3.0 li ha. Identica su Postgres.)
3. RawMailStore.store(logId, raw)              -- best effort, un fallimento si logga e non blocca
4. Match regole; risolvi processore; esegui.
   *** Deliberatamente NON dentro una transazione DB: un POST HTTP non è transazionale, e tenere
   l'unico writer SQLite per 10 s di rete bloccherebbe la UI e ogni altro poll. ***
5. UPDATE mail_processing_log SET status,... + INSERT mail_processing_attempt   (un solo @Transactional)
6. Fine batch: UPDATE mail_folder_state SET last_uid = :maxClaimedUid
```

| Punto di crash | Risultato | Recupero |
|---|---|---|
| prima di 2 | niente preso in carico | il poll successivo rifà il fetch |
| tra 2 e 4 | riga `IN_PROGRESS`, nessuna azione | sweep dei claim stantii → retry |
| **dopo la chiamata BERLink, prima di 5** | riga `IN_PROGRESS`, **azione già eseguita** | → **BERLink chiamato due volte** |
| dopo 5 | tutto registrato | il claim ritorna 0 righe → skip |

La terza riga è irriducibile: questo è **at-least-once**, non exactly-once, e sostenere il contrario sarebbe disonesto. Due mitigazioni, entrambe economiche:

- `DefaultBerlinkApiClient` invia `X-Idempotency-Key: switchmail:<accountId>:<uidValidity>:<uid>:<ruleId>` su ogni scrittura. BERLink oggi non lo onora; quando lo farà la finestra si chiude senza toccare nulla qui. Va annotato in `CLAUDE.md` come punto aperto lato BERLink.
- Il recupero dei claim stantii marca la riga con `error_type='INTERRUPTED'` e messaggio *"interrotta a metà: l'azione BERLink potrebbe essere già stata eseguita — verificare prima del retry"*. Con `switchmail.retry.auto-retry-interrupted = false` (**default**) va in `DEAD_LETTER` e richiede un click umano: una scrittura potenzialmente duplicata è una decisione, non un comportamento automatico.

`StaleClaimRecovery` gira sia come `ApplicationRunner` al boot (intercetta subito il caso SIGKILL) sia ogni 5 minuti, su `status='IN_PROGRESS' AND claimed_at < now - stale-claim-timeout` (10 min).

### RuleMatcher
Classe semplice, senza dipendenze DB, per essere testabile direttamente: `evaluate(mail, rules)` ritorna la traccia completa (per `/ruletest`), `selectFor(mail, rules)` è l'hot path.

```java
public record FieldCheck(String pattern, MatchMode mode, boolean caseSensitive) {
    public enum MatchMode { EQUALS, CONTAINS, REGEX }
    public boolean isWildcard();                  // pattern null o blank -> matcha tutto
    public boolean matches(String value);         // valore null -> false
}
```

Tre trappole gestite esplicitamente:
- **`find()` e non `matches()`** per `REGEX` (semantica substring): l'operatore scrive `AVVISI PARTENZA TRENO` e si aspetta che colpisca. L'help lo dice e suggerisce `^...$` per l'ancoraggio. Sbagliarlo in silenzio è un ticket di supporto per regola.
- **Backtracking catastrofico**: una regex fornita da un operatore è un vettore di DoS anche senza malizia. `RegexUtil.safeMatch(pattern, input, maxSteps)` avvolge l'input in una `CharSequence` che conta gli accessi e lancia dopo `maxSteps` (100 000): budget esaurito → la regola non matcha, `log.warn`, badge di avviso nella UI. ~15 righe, e trasforma un poller appeso in una regola visibilmente sbagliata. Oggetti troncati a 512 char prima del match.
- **Mittente**: match sia sull'indirizzo nudo (`mario.rossi@ferrovie.it`) sia sul `From` completo (`"Mario Rossi" <mario.rossi@...>`); vale l'uno o l'altro e `RuleMatchResult.reason` dice quale. Altrimenti la prima regola di ogni operatore fallisce per un motivo invisibile.

`Pattern` compilati in cache `ConcurrentHashMap` limitata (512 voci, svuotata a ogni scrittura di regola), chiave `pattern + '\0' + caseSensitive`, flag `CASE_INSENSITIVE | UNICODE_CASE`.

---

## 4. UI — 4 pagine

Tutte shape (B): shell vuota + `fetch`. Header e dropdown hamburger ripetuti in ogni file.

| Pagina | Route | Cosa fa |
|---|---|---|
| **Log elaborazioni** | `/logs` (`/` ci redirige) | home |
| **Regole** | `/rules` | CRUD regole |
| **Test regola** | `/ruletest` | matcher + dry-run, non persiste nulla |
| **Caselle** | `/accounts` | CRUD account + test connessione |

### `/logs`
Barra filtri (account, stato multi-select con preset di default **"Da gestire" = `DEAD_LETTER` + `NO_RULE`**, regola, testo libero su oggetto/mittente, intervallo date). Tabella con paginazione keyset (`created_at DESC, id DESC`), 50/pagina. Riga espandibile: `message`, `warnings`, `extracted_json` pretty-printed, `action_ref`, errore + stack collassato, e la timeline dei `mail_processing_attempt`.

Azioni: **Riprova** (`POST /logs/api/{id}/retry`, ricostruisce `ParsedMail` da `mail_raw`, fallback re-fetch per UID; vale anche per `SKIPPED` e `NO_RULE`, così una regola appena creata si applica a una mail vecchia — **è questo il ciclo che rende iterativa la scrittura delle regole**); **Riprova selezionate**; **Segna risolta** (`RESOLVED` + nota, svuota la dead-letter senza fingere che la mail sia riuscita); **Scarica .eml**; **Vedi parsed** (il JSON di `ParsedMail` come l'ha visto l'extractor — risponde a "il parser ha visto l'allegato?" senza debugger).

Contatori in testata (`DEAD_LETTER` / `NO_RULE` / `RETRY_SCHEDULED`) aggiornati ogni 30 s.

### `/rules`
Tabella ordinata per priorità: toggle abilitata · priorità (input inline che salva al blur) · nome · account (`Tutte` se NULL) · i tre `FieldCheck` come chip `MODE: "pattern"` (icona `Aa` se case-sensitive) · processore · icona `stop_on_match` · conteggio hit a 7 giorni (dal log).

Modale nuovo/modifica: tre blocchi `FieldCheck` (pattern + mode + case-sensitive, con hint "wildcard" quando il pattern è vuoto), `require_attachment`, `<select>` processore con la sua `description()` sotto, e **form parametri auto-generato** da `paramSpecs` (`STRING`→input, `TEXT`→textarea, `INT`→number, `BOOL`→check, `SELECT`→select, `JSON`→textarea monospace con `JSON.parse` lato client; `required`→asterisco rosso; `help`→`form-text`) più il toggle **"JSON grezzo"**. In fondo: **"Prova questa regola"** → apre `/ruletest?ruleId=N` precompilato.

Il salvataggio chiama `ProcessorParams.of(...)` e `processor.validateRule(...)` lato server; l'`IllegalArgumentException` diventa 400 e il messaggio appare in rosso sotto il campo.

### `/ruletest` — il tester non persistente
Due modalità di input: **manuale** (mittente, oggetto, nomi allegato ripetibili, account, body opzionale) e **upload `.eml`** (multipart, passa dal vero `MailContentExtractor`, quindi valida anche charset e allegati). Un file scaricato da `/logs` più questa pagina = ciclo completo di sviluppo formati offline.

Output, sempre:
1. **Pannello mail parsata** — cosa ha visto l'extractor: from/oggetto/data, tabella allegati con dimensioni e charset rilevati, anteprima body, `extractionWarnings` come alert gialli.
2. **Traccia completa** — ogni regola abilitata in ordine di valutazione, verde/grigia, con verdetto per campo e motivo del fallimento:
   `subject REGEX "^AVVISI PARTENZA TRENO (\d+)" ✗ (valore: "AVVISO PARTENZA TRENO 4521")`
   Il **"perché no"** è tutto il punto. Le regole che matchano dopo la vincitrice sono grigie con "oscurata da #N".
3. **Card vincitrice** — regola, processore, parametri effettivi (con i default applicati), e **"Esegui in dry-run"**: mostra il `ProcessingOutcome` (o l'eccezione con `errorType` e flag retryable) più **ogni chiamata che il `RecordingBerlinkApiClient` avrebbe fatto** (metodo, path, header senza la chiave, body). Nulla viene scritto nel log e nessuna HTTP esce dal processo.

### `/accounts`
Tabella: nome · `host:porta` · username · folder · badge modalità (`READ_ONLY` verde "non distruttivo" / `OWNED` blu) · abilitato · ultimo poll (tempo relativo + badge OK/ERROR + tooltip errore) · fallimenti consecutivi · numero regole.

Modale: campi account, password `type=password` con placeholder `"•••••• (impostata)"` quando `passwordSet` e vuoto = lascia invariata, `post_action` disabilitato se non `OWNED` (specchia il `CHECK` del DB), `trust_all_certs` con avviso rosso.

**"Testa connessione"** (`POST /accounts/api/test` col body ad-hoc *prima* di salvare, come FlowCenter; oppure `.../{id}/test` con la password memorizzata) → ritorna `ok`, `openedMode`, `uidValidity`, conteggi, folder, ultime mail, durata. La UI mostra `openedMode` in evidenza: **"Cartella aperta in READ_ONLY — nessun flag modificato"**. È la rassicurazione per cui esiste tutta la modalità B, e mostrarla trasforma una promessa architetturale in un fatto osservabile.

**"Anteprima ultime 10"** — fetch solo `ENVELOPE` (niente body, niente `\Seen`): finché i formati sono ignoti, è così che l'operatore scopre per cosa scrivere una regola. Ogni riga ha **"Crea regola da questa mail"** che apre `/rules` precompilato.

Banner rosso su ogni pagina se `CredentialCipher.isConfigured() == false`: *"SWITCHMAIL_CREDS_KEY non configurata: impossibile salvare o usare credenziali."* — mai un salvataggio che fallisce in silenzio più tardi.

---

## 5. Decisioni infrastrutturali e trappole dei progetti fratelli

### DataSource primario, non pool nascosto
`GeofencingDbConfig` nasconde il suo `DataSource` per un motivo preciso, scritto nel suo Javadoc: un secondo bean `DataSource` farebbe il backoff di `DataSourceAutoConfiguration` lasciando `ActiveJDBCConfig` senza il Postgres. **SwitchMail non ha né Postgres né ActiveJDBC**, quindi quel motivo non esiste e mantenere il workaround costerebbe: `DataSourceHealthIndicator` (senza, l'`HEALTHCHECK` del container direbbe "healthy" con il DB corrotto o il volume smontato), le metriche `hikaricp.*`, il `DataSourceTransactionManager` (serve al passo 5 della pipeline), e il `JdbcTemplate` auto-configurato senza `@Qualifier` in ogni costruttore.

Quindi: `SwitchMailDbConfig` espone **un solo `@Bean DataSource`**.

PRAGMA: `connectionInitSql` di Hikari accetta **una** statement, quindi il set completo va sull'URL JDBC dove il driver xerial lo applica a ogni connessione fisica:
```
jdbc:sqlite:file:/app/data/switchmail.db?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000&journal_size_limit=536870912&wal_autocheckpoint=2000
```
più `connectionInitSql("PRAGMA foreign_keys = ON")` come cintura-e-bretelle, `maximumPoolSize(1)`, `poolName("SwitchMail-SQLite-Pool")`, `Files.createDirectories(parent)` prima di costruire (da `GeofencingDbConfig`).

Pool 1 basta: un poll ogni 2 minuti e una manciata di utenti UI. Se il browser di log dovesse rallentare su tabella grande, la soluzione è un **secondo pool read-only** (in WAL i lettori girano insieme allo scrittore), non alzare il pool di scrittura.

Corollario: con `foreign_keys=ON` applicato davvero, `ON DELETE CASCADE` è affidabile. `GeofencingService.deleteMap` fa un cascade manuale perché non si fida della pragma — **non copiarlo**; coprirlo invece con un test che cancella un account e verifica che regole e log siano spariti.

### Segreti — la trappola più grossa dei fratelli
`TFPEventIngester/src/main/resources/application.yml` committa in git una API key BERLink viva **senza alcun override da env**, una password reale come default di `${TFP_API_PASSWORD:...}`, e una `health.api-key` con default funzionante (quindi la prod ci gira sopra per sempre).

SwitchMail: **nessun segreto ha un default.**
```yaml
switchmail:
  security:
    creds-key: ${SWITCHMAIL_CREDS_KEY}     # nessun default -> il boot fallisce rumorosamente
berlink:
  api:
    base-url: ${BERLINK_BASE_URL:http://backend:8080}
    api-key:  ${BERLINK_API_KEY}           # nessun default
health:
  api-key: ${HEALTH_API_KEY}               # nessun default
```
Un placeholder senza default fa fallire il context con `Could not resolve placeholder` — comportamento desiderato; `CredentialCipher` aggiunge un check `@PostConstruct` con nel messaggio la one-liner per generare la chiave. I valori veri vivono solo nel `application.yml` bind-mountato da `/opt/berlink/switchmail/data/`, che è già la convenzione di deploy e non è in git.

### Altre trappole
- **Actuator**: `include: health,info,metrics` e basta. Mai `env`, `configprops`, `heapdump` — `env` stamperebbe `SWITCHMAIL_CREDS_KEY` a chiunque sia sulla rete Docker, e senza auth sul servizio è una compromissione completa delle credenziali.
- **Publish su loopback**: `127.0.0.1:8105:8080`, **non** `8105:8080`. La UI non ha auth e permette di editare password di caselle: pubblicarla su `0.0.0.0` la espone all'intera LAN dell'host Docker.
- **Test nella build Docker**: i Dockerfile fratelli hardcodano `-DskipTests`, quindi l'unica build che qualcuno lancia non esegue mai un test e la suite marcisce in un mese. Qui `ARG SKIP_TESTS=false` + `task build-sm-fast` per l'emergenza. E il layer di cache delle dipendenze (`COPY pom.xml` → `mvn dependency:go-offline` → `COPY src`) che i fratelli dichiarano in un commento ma non fanno.
- **`ENTRYPOINT` con `exec`**: senza, SIGTERM non arriva al JVM e lo shutdown hook che finisce la mail in volo non gira mai.
- **Log delle password**: `mail.debug=true` di jakarta.mail stampa il comando `LOGIN` **password inclusa**. Dietro `switchmail.imap.debug` (default false), con un commento che spiega perché.
- **`version: '3.8'`** nei compose fratelli è obsoleto in Compose v2 e genera un warning a ogni comando: omesso.
- **`.dockerignore`** (assente nei fratelli, che spediscono `.git` e `target/` nel build context): `target/`, `.git/`, `data/`, `*.md`.
- **Timezone**: UTC ovunque nello storage, `spring.jackson.time-zone: UTC`, `TZ=Europe/Rome` nel compose per leggibilità dei log, formattazione in locale **nel browser** (`toLocaleString('it-IT')`), mai in Java. Metà della confusione nel mail processing è confusione di timezone.

---

## 6. File da creare e ordine di implementazione

```
SwitchMail/
├── pom.xml  Dockerfile  .dockerignore  docker-compose.yml  Taskfile.yml
├── CLAUDE.md  README.md  .gitignore          (.gitignore: target/, data/, *.db, *.db-wal, *.db-shm, application-local.yml)
├── MAIL_PROCESSOR_DESIGN.md                  (esistente: aggiungere header "Stato implementazione")
├── IMPLEMENTATION_PLAN.md                    (questo file)
└── src/
    ├── main/java/it/gruppobernardini/switchmail/
    │   ├── SwitchMailApplication.java
    │   ├── config/      SwitchMailDbConfig, SwitchMailProperties, BerlinkApiConfig,
    │   │                SchedulingConfig, JacksonConfig, SchemaMigrations
    │   ├── controller/  HealthController, ProcessingLogController, RuleController,
    │   │                RuleTestController, AccountController
    │   ├── dao/         MailAccountDao, MailFolderStateDao, RuleDao,
    │   │                ProcessingLogDao, ProcessingAttemptDao, RawMailDao
    │   ├── dto/         ProcessorDescriptor, TestConnectionResult, MailPreview,
    │   │                RuleTestRequest, RuleTestResult, DryRunResult, LogPage
    │   ├── model/       MailAccount, MailFolderState, RuleConfig, FieldCheck, RuleMatchResult,
    │   │                MatchedRules, ParsedMail, MailAttachment, ProcessingStatus, ProcessingLogEntry
    │   ├── processor/   MailSubProcessor, AbstractMailSubProcessor, MailSubProcessorRegistry,
    │   │                MailContext, ProcessingOutcome, ParamSpec, ProcessorParams,
    │   │                MailProcessingException, Retryable~, Terminal~,
    │   │                TrainDepartureProcessor, TerminalForecastProcessor
    │   ├── service/     AccountService, RuleConfigService, ProcessingLogService, ImapMailReader,
    │   │                MailContentExtractor, RuleMatcher, MailPollService, MailPollScheduler,
    │   │                MailIngestService, RetryScheduler, RetentionService, RawMailStore,
    │   │                BerlinkApiClient, DefaultBerlinkApiClient, RecordingBerlinkApiClient,
    │   │                AdminNotifier, RuleTestService, ProcessorValidationRunner
    │   └── util/        CredentialCipher, RegexUtil, JsonUtil, TimestampUtil
    ├── main/resources/  application.yml, db/schema.sql,
    │                    templates/{logs,rules,ruletest,accounts}.html
    └── test/java/it/gruppobernardini/switchmail/
        │             FieldCheckTest, RuleMatcherTest, MailContentExtractorTest,
        │             ProcessorParamsTest, MailSubProcessorRegistryTest, ProcessingOutcomeTest,
        │             CredentialCipherTest, BerlinkApiClientClassificationTest,
        │             MailIngestServiceTest, ImapPollGreenMailIT,
        │             support/{TestDb,RecordingProcessor,MailFixtures}.java
        └── resources/  application-test.yml, mail/*.eml
```

~55 file. Cinque tappe reviewabili una alla volta — si può dire *"implementa la tappa N"*:

1. **Scaffold** — `pom.xml`, `SwitchMailApplication`, `SwitchMailDbConfig`, `db/schema.sql`, `SwitchMailProperties`, `application.yml` → *`mvn compile` passa, il DB si crea con tutte le tabelle*
2. **`processor/`** — SPI, base astratta, registry, `ParamSpec`/`ProcessorParams`, eccezioni, i 2 stub + i relativi test unitari
3. **`dao/` + `service/`** — i 6 DAO (tutto lo SQL), poi extractor, matcher, `ImapMailReader`, `MailIngestService`, retry, `BerlinkApiClient` + test unitari + `ImapPollGreenMailIT`
4. **`controller/` + i 4 template**
5. **Deploy** — `Dockerfile`, `.dockerignore`, `docker-compose.yml`, `Taskfile.yml`, `CLAUDE.md`, `README.md`, `.gitignore`

---

## 7. Verifica

### Unit (senza Spring, senza IO) — il grosso
`FieldCheckTest` (EQUALS/CONTAINS/REGEX × case × pattern vuoto/null × valore null; `find()` non `matches()`; case folding accentato) · `RuleMatcherTest` (ordinamento priorità + tie-break id, regole globali `account_id IS NULL`, disabilitate, `require_attachment`, `stop_on_match` 0 e 1, mittente su indirizzo **e** su `From` completo, budget regex esaurito → nessun match + warning senza appendersi) · `MailContentExtractorTest` (fixture `.eml`: body semplice, multipart/alternative, multipart/mixed con `.txt`, CP1252 non dichiarato, filename RFC 2047, multipart annidato, allegato sovradimensionato → troncamento + warning, solo-HTML) · `ProcessorParamsTest` (required mancante con la chiave nel messaggio, coercizione, default, chiave sconosciuta rifiutata, valore SELECT fuori da `options`) · `MailSubProcessorRegistryTest` (id duplicato → boot fallito, alias + WARN, collisione alias/canonico) · `ProcessingOutcomeTest` · `CredentialCipherTest` (round-trip, chiave sbagliata, byte del ciphertext alterato → `AEADBadTagException`, env mancante → messaggio con la one-liner per generare la chiave) · `BerlinkApiClientClassificationTest` (`MockRestServiceServer`: 4xx→Terminal, 429/5xx/timeout→Retryable, header `X-API-Key` e `X-Idempotency-Key` presenti).

Fixture in `src/test/resources/mail/*.eml`, caricate con `new MimeMessage(Session.getInstance(new Properties()), stream)` — nessun server. Quando arriveranno i formati veri, le prime due mail scaricate da `/logs` diventano fixture pari-pari.

### GreenMail — sì
`com.icegreen:greenmail-junit5` scope `test`, **linea 2.x** (2.1.x): GreenMail 1.x è `javax.mail` e non interopera con Boot 3 / `jakarta.mail`. Toglie l'IT dal percorso critico di tutta la "Fase 2" del design doc e trasforma la garanzia di non-distruttività da promessa ad assert in CI.

`ImapPollGreenMailIT`, un `@Test` per passo, con un bean `Clock` iniettato ovunque si legga il tempo (`Clock.fixed` nei test, altrimenti i passi 8–9 richiedono `Thread.sleep`):

1. **Seed** — `MimeMessage` con `From: A`, `Subject: AVVISI PARTENZA TRENO 4521 17/09/2026`, allegato `treno.txt`.
2. **Config** — riga `mail_account` su `127.0.0.1:greenMail.getImap().getPort()`, `use_ssl=0`, `access_mode='READ_ONLY'`, password cifrata con chiave di test via `@DynamicPropertySource`; regola legata a un `RecordingProcessor` registrato come `@TestConfiguration @Bean`.
3. **Poll** — `mailPollService.pollAccount(id)`.
4. **Assert elaborata** — esattamente una riga, `SUCCESS`, `uid_validity`/`uid` giusti, `extracted_json` non null; il `RecordingProcessor` ha visto il testo dell'allegato e i parametri effettivi.
5. **Assert READ_ONLY** ← *il test che il design doc chiede.* Riconnettersi separatamente, aprire `INBOX` in **`READ_WRITE`**, e asserire `!msg.isSet(SEEN)`, `!msg.isSet(DELETED)`, `getMessageCount() == 1`. Ripetere in un secondo test con `mail.imaps.peek` messo a `false` e verificare che **fallisca**: così la property resta inchiodata e nessuno la cancella come "ridondante".
6. **Idempotenza** — secondo poll: ancora 1 riga, processore invocato ancora 1 volta.
7. **High-water mark** — seconda mail, poll: 2 righe, e conferma che solo il nuovo UID sia stato fetchato (copre la trappola `LASTUID`).
8. **Crash-safety** — riga `IN_PROGRESS` con `claimed_at = now - 20min`, `recoverStaleClaims()` → `DEAD_LETTER` con `error_type='INTERRUPTED'` + riga attempt.
9. **Retry dead-letter** — processore che lancia Retryable al tentativo 1 e riesce al 2: poll → `RETRY_SCHEDULED` con `next_retry_at`; avanza il `Clock`; sweep → `SUCCESS`, `attempt=2`, due righe attempt.
10. **`NO_RULE`** — mail che non matcha nulla → una riga `NO_RULE`, `mail_raw` popolata.
11. **Reset UIDVALIDITY** — GreenMail non lo cambia a comando, quindi si testa il ramo: `UPDATE mail_folder_state SET uid_validity = uid_validity + 1`, poll, assert `SKIPPED` via `internet_message_id` + notifica admin tentata.

### End-to-end manuale
```bash
task sm && task up && task logs-sm          # build + run
open http://127.0.0.1:8105/accounts         # crea casella, "Testa connessione" -> deve dire READ_ONLY
                                            # "Anteprima ultime 10" -> "Crea regola da questa mail"
open http://127.0.0.1:8105/ruletest         # incolla mittente+oggetto -> traccia di valutazione
                                            # oppure carica un .eml -> dry-run, zero HTTP in uscita
open http://127.0.0.1:8105/logs             # dopo un poll: righe NO_RULE = cosa manca di regole
curl -H "X-API-Key: $HEALTH_API_KEY" http://127.0.0.1:8105/api/health/ready   # checks.rules
```

Il ciclo che chiude il progetto: `/logs` → **Scarica .eml** → `src/test/resources/mail/` → test del parser → implementa `mode=PROCESS` → **Riprova** sulla stessa mail dal log.

---

## 8. Portabilità Postgres

Il piano resta su SQLite (file singolo, zero infrastruttura, deploy indipendente da BERLink). Se un giorno
si passa a Postgres, il lavoro è **circoscritto a tre file** — non serve un ORM per ottenerlo, serve che lo
SQL stia tutto in un posto solo.

| Punto | SQLite oggi | Postgres domani |
|---|---|---|
| URL, PRAGMA, pool | `jdbc:sqlite:...?journal_mode=WAL…`, `maximumPoolSize(1)` | URL PG, pool 5–10, niente PRAGMA |
| Chiave primaria | `INTEGER PRIMARY KEY AUTOINCREMENT` | `BIGINT GENERATED ALWAYS AS IDENTITY` (o sequenza `s_*`, come i fratelli) |
| MIME gzippato | `BLOB` | `bytea` — lato Java `setBytes`/`getBytes`, invariato |
| Booleani | `INTEGER` 0/1 | `boolean` — i DAO usano solo `rs.getBoolean()`/`setBoolean()`, invariato |
| Timestamp | `TEXT` ISO-8601 UTC da `TimestampUtil` | `timestamptz` — la conversione `Instant ↔ colonna` sta solo in `TimestampUtil` |
| Claim atomico | `ON CONFLICT (…) DO NOTHING RETURNING id` | identica |
| JSON (`params_json`, `warnings`, …) | `TEXT` + Jackson | `TEXT` o `jsonb`; con Jackson lato Java, `TEXT` basta |
| Indici parziali, `CHECK`, `ON DELETE CASCADE`, paginazione keyset | — | già portabili |

File da toccare: `config/SwitchMailDbConfig` (URL + pool), `resources/db/schema.sql` (gemello
`schema-postgres.sql`, guidato dai commenti `-- PG:`), `util/TimestampUtil` (mappatura del tipo temporale).
**Nessun service, nessun controller, nessun processore**: non vedono mai una `Connection`, un `ResultSet`
o una stringa SQL.

Il seam si verifica meccanicamente, non a fiducia:
```bash
grep -rn "JdbcTemplate" src/main/java | grep -v "/dao/\|SwitchMailDbConfig"   # deve essere vuoto
grep -rniE "strftime|INSERT OR IGNORE|PRAGMA" src/main/java                    # solo SwitchMailDbConfig
```

---

## Note operative

- **Git**: `SwitchMail/` non è un repo a sé. Il repo è `Processors/` (`git rev-parse --show-toplevel` → `/mnt/c/Projects/GIT/BERLinkPlatform/Processors`) e `SwitchMail/` è una sua sottodirectory **non tracciata** — il primo commit aggiunge l'intero albero. Il branch corrente del repo `Processors` si chiama `FlowCenter` (default: `main`) e **non ha niente a che vedere** col progetto FlowCenter, che è un repo annidato separato in `BERLinkPlatform/FlowCenter` (Python, branch `master`) usato qui solo come riferimento per SQLite e per il modello delle regole. Il working tree di `Processors` ha molte modifiche non correlate su altri processori: non toccarle, e committare solo i file di `SwitchMail/`.
- **GreenMail richiede rete** alla prima build (non è nella cache `~/.m2`). `angus-mail`, `sqlite-jdbc 3.45.3.0` e `spring-boot-starter-parent 3.4.3` ci sono già.
- Punti del design doc che restano aperti e **non** vengono chiusi qui (per costruzione): formato del `.txt` treno e del body forecast, endpoint BERLink reali. Gli stub in `mode=COLLECT` sono progettati proprio per raccogliere i dati che serviranno a chiuderli.

# BERLink Mail Processor — Documento di Design

> Microservizio separato che processa in automatico le email di aggiornamento
> inviate da sistemi esterni, oggi lette a mano da un dipendente.
> Stato: **design approvato**, implementazione da avviare. Alcuni dettagli
> (mailbox, formati) in attesa di verifica con l'IT / definizione formati.

---

## 1. Contesto e obiettivo

Alcuni sistemi esterni inviano aggiornamenti via **email** alla casella di un
dipendente (mail server **Microsoft Exchange on-premise**). Oggi un umano legge
quelle mail e agisce a mano.

Serve un **automatismo** che:
- lavori sugli **stessi dati** della casella del dipendente,
- in modo **non distruttivo** — la casella resta viva e intatta, l'umano continua
  a usarla senza accorgersi di nulla,
- **classifichi** le mail per mittente + oggetto,
- e per ciascun tipo esegua un'**azione** (scrivere in DB / chiamare API BERLink).

### Tipi di mail noti oggi
I formati esatti si definiscono più avanti (sono specifici di ogni sub-processore):

| Tipo | Oggetto | Mittente | Dati |
|------|---------|----------|------|
| (a) Avvisi partenza treno | `AVVISI PARTENZA TRENO <treno> <gg/mm/aaaa>` | A | **allegato .txt** |
| (b) Forecast terminal | `FORECAST OF TERMINAL / INFO: <terminal> <id>` | B | **body della mail** |

Il sistema deve gestire **più tipi**, estendibile con nuovi sub-processori.

---

## 2. Decisioni prese

| Tema | Decisione | Motivo |
|------|-----------|--------|
| Dove gira | **Microservizio separato**, NON dentro BERLink | Isola le credenziali mail, deploy indipendente. Può riusare classi BERLink dove comodo. |
| AI vs classico | **Solo parsing classico** ora | Formati fissi e strutturati → parser deterministico, zero costo, testabile, niente allucinazioni. AI eventuale fallback futuro per formati sconosciuti. |
| Azioni | **Scrivere in DB / chiamare API BERLink** (via API key) | Le mail alimentano dati di dominio reali. |
| Linguaggio | **Java / Spring Boot** | Allineato al team e al backend BERLink. |
| Accesso mail | Exchange on-prem → **niente Microsoft Graph / Azure AD**. **IMAP4** (`jakarta.mail`) | Graph esiste solo per M365/cloud. On-prem = IMAP (o EWS). |

---

## 3. Accesso alla mailbox (in verifica con IT)

Vincolo chiave: **non "rubare" le mail** al dipendente. Due modalità, entrambe via
IMAP, **selezionabili da configurazione**:

### Modalità A — Casella dedicata con forward *(preferita)*
L'account del dipendente **inoltra una copia** delle mail-target (regola
server-side per mittente/oggetto) a una **casella dedicata** che il microservizio
possiede. Il processore è padrone di quella casella → può cancellare/archiviare
dopo il processing, marcare, ecc. La casella del dipendente resta **totalmente
intatta**.

### Modalità B — Read-only sulla casella del dipendente *(fallback)*
Il microservizio apre la cartella in **`Folder.READ_ONLY`**: per costruzione
**non marca come letto (\Seen), non cancella, non sposta**. Dedup basata su
`UIDVALIDITY` + `UID` persistito nel DB del microservizio.

> In entrambi i casi serve un **service account** (non la password personale del
> dipendente): in modalità A possiede la casella dedicata; in modalità B ha
> permesso di **Full Access read** sulla casella del dipendente.

---

## 4. Architettura

Microservizio **Spring Boot standalone**. Pipeline di elaborazione:

```
IMAP poll (cron, jakarta.mail)  →  nuovo messaggio (per UID)
  → dedup (già processato? skip)                     [tabella processing log]
  → match REGOLA (mittente + regex oggetto)          [config/tabella regole]
  → SUB-PROCESSOR del tipo estrae il payload         [SPI pluggabile per-tipo]
       (a) parse allegato .txt   (b) parse body
  → mappa ad AZIONE → chiama API BERLink (X-API-Key)
  → scrivi esito nel processing log (idempotenza + audit)
  → in caso di errore → notifica admin + dead-letter (mai perdere una mail)
```

### Componenti

- **`ImapMailReader`** — apre `Store`/`Folder` (IMAPS 993 + TLS), poll
  config-driven (READ_ONLY o casella posseduta). Pattern scheduler mutuato da
  `WaynetHistoryScheduler` di BERLink (cron + open/close connessione).
- **Rule matcher** — regole `{ sender, subjectRegex, type, enabled }`. Si parte
  con config **YAML statica**; eventuale tabella DB per gestione a runtime dopo
  (schema ispirato a `ntf_automation_rules`, §46 di BERLink).
- **`MailSubProcessor` (interfaccia SPI)** — `boolean supports(rule)` +
  `void process(ParsedMail)`. Un'implementazione per tipo:
  `TrainDepartureProcessor` (a), `TerminalForecastProcessor` (b). **Stub ora**,
  parsing reale quando i formati sono definiti.
- **`BerlinkApiClient`** — chiama gli endpoint di scrittura BERLink con header
  **`X-API-Key`** (auth già supportata lato server: `ApiKeyAuthenticationFilter`).
  Endpoint target da definire col formato (probabili: `/api/bookings` per il treno,
  terminal-info per il forecast).
- **Processing log** — tabella `mail_processing_log`
  (`message_id`/`internetMessageId`, `uid`, `uidvalidity`, `rule`, `status`,
  `extracted_json`, `error`, `ts`). Serve a **idempotenza** (dedup), **audit** e
  **dead-letter**. **DB proprio del microservizio**, non tocca lo schema BERLink.
- **Error handling** — nessun *silent failure*: log + notifica admin (riusando
  `POST /api/notifications/send` di BERLink con API key, buffer async Valkey) +
  retry limitato.

### Riuso da BERLink (senza dipendere dal suo deploy)

- **Auth verso BERLink**: API key (`X-API-Key`) — infrastruttura già pronta.
- **Notifiche errori**: `POST /api/notifications/send` (API key scope `cd`).
- **Pattern di codice copiabili**: scheduler poll (`WaynetHistoryScheduler`),
  motore regole (§46: `RowFilterEvaluator`/`AutomationConditionEvaluator` se le
  regole diventano data-driven), client HTTP/OIDC (`integration/*Client`).

---

## 5. Fasi di realizzazione

1. **Scaffold del microservizio** — buildabile subito, indipendente da IT/formati:
   progetto Spring Boot, `ImapMailReader` config-driven, scheduler poll, rule
   matcher (YAML), SPI `MailSubProcessor`, tabella `mail_processing_log` + dedup,
   `BerlinkApiClient` (X-API-Key), gestione errori/notifica. Sub-processor **stub**.
2. **Wiring mailbox** — dopo l'IT: credenziali/host/porta, scelta modalità A o B,
   test di connessione IMAP reale su una casella di prova.
3. **Sub-processor per-tipo** — dopo la definizione dei formati: parser .txt treno
   + parser body forecast + mappatura azione/endpoint BERLink reali.

---

## 6. Checklist per il tecnico IT

1. **IMAP4 è abilitato** sul server Exchange on-prem? Se no, si può abilitare?
   (in alternativa EWS, ma la libreria Java è vecchia/non manutenuta — meglio IMAP).
2. Si può creare un **account/casella dedicata** che riceve il **forward** (regola
   server-side per mittente/oggetto) delle mail-target? *(modalità A, preferita)*
3. In alternativa, un **service account con Full Access read** sulla casella del
   dipendente? *(modalità B — così non si usa la password dell'umano)*
4. **IMAPS 993 + TLS** raggiungibile dalla rete dove gira il microservizio
   (firewall/VLAN)? La **basic-auth** è ammessa? (on-prem di norma sì).
5. **Versione di Exchange** (2013 / 2016 / 2019)? (rileva se serve ripiegare su EWS).
6. Sulla casella dedicata: si può **cancellare/archiviare** dopo il processing, o
   solo leggere? (dimensiona retention e strategia di dedup).
7. Quanta **autonomia** ho io nel creare account/regole vs richiesta all'IT.

---

## 7. Verifica (quando si costruisce)

- **Fase 1**: `mvn test` sul microservizio — unit test di rule matcher + parser
  (stile funzione pura, input in / valore out) + dedup log. Nessun IMAP reale nei
  test unit.
- **Fase 2**: test di integrazione IMAP contro casella di prova (mail seed, poll,
  dedup, read-only verificato = flag \Seen non settato).
- **Fase 3**: end-to-end — mail reale → poll → parse → chiamata BERLink
  (stub/reale) → riga scritta / notifica. Verifica **idempotenza** (ripoll = nessun
  doppione).

---

## 8. Punti ancora aperti (si decidono più avanti)

- Formato esatto del .txt treno e del body forecast (specifico per sub-processore).
- Endpoint/azione BERLink precisi per ciascun tipo di mail.
- Regole in YAML statico vs tabella DB gestibile a runtime.

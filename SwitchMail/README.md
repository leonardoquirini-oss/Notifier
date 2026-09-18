# SwitchMail

Legge le mail di aggiornamento inviate da sistemi esterni alla casella di un dipendente, le
classifica e per ogni tipo esegue un'azione verso BERLink — **senza toccare la casella**: non marca
come letto, non sposta, non cancella.

## Come funziona

```
poll IMAP (cron per casella)
  → materializza la mail (ParsedMail) e chiude la connessione
  → claim atomico (INSERT ... ON CONFLICT DO NOTHING RETURNING id)   ← è la dedup
  → archivia il MIME grezzo gzippato
  → match delle regole (mittente / oggetto / allegato)
  → sub-processore Java → chiamata a BERLink (X-API-Key)
  → esito nel registro: SUCCESS / SKIPPED / NO_RULE / RETRY_SCHEDULED / DEAD_LETTER
```

Nessuna mail viene scartata in silenzio: se nessuna regola corrisponde resta una riga `NO_RULE` con
il MIME archiviato, ed è da lì che si capisce quali regole scrivere.

## Le quattro pagine

| Pagina | Route | A cosa serve |
|---|---|---|
| Log elaborazioni | `/logs` (home) | cosa è arrivato e com'è finito; **Riprova**, **Scarica .eml**, **Vedi parsed** |
| Regole | `/rules` | CRUD con form dei parametri generato dal processore scelto |
| Test regola | `/ruletest` | valuta senza persistere, spiega **perché** una regola non ha corrisposto, esegue in dry-run |
| Caselle | `/accounts` | CRUD, **Testa connessione** (mostra `READ_ONLY`), anteprima delle ultime 10 |

## Avvio

```bash
export SWITCHMAIL_CREDS_KEY=$(openssl rand -base64 32)   # cifratura delle password IMAP
export BERLINK_API_KEY=...                               # scritture verso BERLink
export HEALTH_API_KEY=...                                # /api/health/ready

task sm && task up            # build (con i test) + run
task logs-sm
open http://127.0.0.1:8105/   # la UI è pubblicata solo su loopback: non ha autenticazione
```

In locale, senza Docker: `mvn spring-boot:run` con le stesse variabili — `task up` serve la
configurazione in `/opt/berlink/switchmail/data/application.yml`, che esiste solo sulla macchina di
deploy (in produzione i valori veri stanno lì, e le variabili d'ambiente diventano superflue).

## Modalità di accesso alla casella

- **READ_ONLY** (casella del dipendente): la cartella si apre con `EXAMINE` e i fetch usano
  `BODY.PEEK`. Nessuna azione post-elaborazione è possibile — lo impedisce un `CHECK` dello schema,
  non solo il codice.
- **OWNED** (casella dedicata che riceve un forward): sono ammesse `MARK_SEEN`, `MOVE`, `DELETE`.

Il bottone **Testa connessione** mostra in che modalità la cartella è stata aperta davvero.

## Stato

I due sub-processori (`train-departure`, `terminal-forecast`) partono in modalità **COLLECT**:
acquisiscono e archiviano senza agire, perché i formati esatti non sono ancora definiti. Dopo
qualche giorno di raccolta si scaricano gli `.eml` reali da `/logs`, si scrive il parser con quelle
fixture e si passa a `mode=PROCESS`.

Dettagli e motivazioni: `MAIL_PROCESSOR_DESIGN.md`, `IMPLEMENTATION_PLAN.md`, `CLAUDE.md`.

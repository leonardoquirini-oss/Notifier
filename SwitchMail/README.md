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
export HEALTH_API_KEY=$(openssl rand -hex 24)            # protegge /api/health/ready (facoltativa)

task sm && task up            # build (con i test) + run
task logs-sm
open http://127.0.0.1:8105/   # la UI è pubblicata solo su loopback: non ha autenticazione
```

In locale, senza Docker: `mvn spring-boot:run` con le stesse variabili — `task up` serve la
configurazione in `/opt/berlink/switchmail/data/application.yml`, che esiste solo sulla macchina di
deploy (in produzione i valori veri stanno lì, e le variabili d'ambiente diventano superflue).

## Tenere piccolo l'archivio

`/logs` → **Manutenzione archivio** mostra quanto occupa il database (righe, `.eml` archiviati
compressi e originali, dimensione del file, spazio recuperabile) e offre due operazioni diverse.
Si cancella **solo dall'archivio di SwitchMail: le mail nella casella non vengono toccate.**

| Operazione | Cosa elimina | Conseguenza |
|---|---|---|
| **Elimina .eml** | solo il MIME archiviato | nessuna controindicazione, ed è ciò che libera quasi tutto lo spazio. Si perde il download dell'`.eml` e la possibilità di rielaborare senza ripescare la mail dalla casella |
| **Elimina righe** | log, tentativi e `.eml` | sparisce anche la **memoria di dedup** di quelle mail: finché il segnaposto degli UID resta non vengono riscaricate, ma dopo un cambio di UIDVALIDITY non sarebbero più riconosciute come già viste |

La pulizia per criteri (stati, età in giorni, casella) **conta prima e cancella dopo**: il numero
esatto di righe interessate viene mostrato e confermato. Le righe `IN_PROGRESS` non si cancellano
mai: sono mail in elaborazione in quel momento.

SQLite non rimpicciolisce il file da solo dopo una cancellazione — le pagine liberate restano nel
file e vengono riusate. **Compatta database** (VACUUM) le restituisce al disco.

Oltre a questo c'è già una pulizia automatica: `.eml` più vecchi di `switchmail.mail.raw-retention-days`
(30) e righe riuscite o risolte oltre `log-retention-days` (365).

## Health check

Conforme al contratto di piattaforma (`BERLink/prompt/HEALTH_CONTRACT.md`), così FlowCenter lo
interroga come gli altri servizi:

```bash
curl http://127.0.0.1:8105/api/health/live                              # pubblico, sempre 200
curl -H "X-API-Key: $HEALTH_API_KEY" http://127.0.0.1:8105/api/health/ready
# {"status":"UP","service":"switchmail","version":"1.0.0","timestamp":"...",
#  "checks":{"database":"UP","rules":"UP","credentials":"UP"}}
```

`rules` va DOWN quando una regola punta a un processore che non esiste più: il servizio continua a
funzionare (la UI che ripara quella regola gira lì dentro) ma il monitor lo vede. `HEALTH_API_KEY` è
una chiave di SwitchMail, non di BERLink: se manca, `/ready` risponde 503 dicendolo e tutto il resto
funziona lo stesso.

## Casella condivisa (WebTop, Cyrus, Dovecot)

Se la casella da leggere è stata **condivisa in sola lettura** con il tuo account, non serve nessuna
login speciale: ti autentichi con le **tue** credenziali e la casella altrui compare come *cartella*
in un namespace separato. Il nome esatto cambia da installazione a installazione — `Other Users/mario`,
`Altri utenti/mario`, `user/mario`, `shared/mario@dominio.it` — quindi non va indovinato: nella
schermata **Caselle**, accanto al campo *Cartella*, il bottone 📂 elenca tutte le cartelle visibili
con quelle credenziali, raggruppate per namespace (personale / altri utenti / condivise), con il
conteggio dei messaggi. Si clicca quella giusta e il nome finisce nel campo.

Modalità di accesso: **READ_ONLY**. È la casella di qualcun altro, e in quella modalità un `CHECK` del
database rende impossibile qualsiasi azione che la modifichi.

> Su Exchange la condivisione funziona diversamente: lì si usa una login di delega
> (`dominio/servizio/dipendente` nel campo *Utente*, con `FullAccess` concesso dall'IT) e la cartella
> resta `INBOX`.

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

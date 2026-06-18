# Notifier Service — API REST

Servizio per l'invio di notifiche email. Oltre al consumo eventi via Valkey Stream,
espone un endpoint REST per inviare email da app esterne.

---

## Autenticazione

Tutte le richieste a `POST /email` richiedono l'header:

```
X-API-Key: <chiave>
```

La chiave viene confrontata con la lista configurata in `email.api.keys`
(vedi [Configurazione](#configurazione)). Possono esistere **più chiavi** valide.

L'autenticazione è gestita da `ApiKeyAuthFilter` (Spring Security) **prima** che la
richiesta raggiunga il controller. Il filtro agisce solo sul path `/email`; gli altri
endpoint (health) restano gestiti dalla propria logica.

**Componenti** (`src/main/java/.../security/`):

| Classe | Ruolo |
|--------|-------|
| `SecurityConfig` | `SecurityFilterChain` stateless, CSRF off, registra il filtro |
| `ApiKeyAuthFilter` | Valida `X-API-Key` su `/email`, scrive errori JSON 401/503 |

---

## `POST /email`

Invia un'email diretta (senza template). Usa lo stesso motore d'invio dell'evento
stream `email:send` (`EmailService.sendDirectEmail`); l'esito è tracciato nella
tabella `email_send_log`.

### Headers

| Header | Obbligatorio | Valore |
|--------|--------------|--------|
| `X-API-Key` | Sì | Una delle chiavi in `email.api.keys` |
| `Content-Type` | Sì | `application/json` |

### Body (JSON)

| Campo | Tipo | Obbligatorio | Default | Descrizione |
|-------|------|--------------|---------|-------------|
| `to` | string[] | **Sì** | — | Destinatari principali |
| `subject` | string | **Sì** | — | Oggetto |
| `body` | string | No | `""` | Corpo (testo o HTML) |
| `cc` | string[] | No | — | Copia conoscenza |
| `ccn` | string[] | No | — | Copia nascosta (BCC) |
| `from` | string | No | `email.from.address` | Mittente |
| `sender_name` | string | No | `email.from.name` | Nome mittente |
| `is_html` | boolean | No | auto-detect | Se `true`, body trattato come HTML. Se assente: auto-detect (body con `<` e `>` → HTML) |
| `attachments` | int[] | No | — | ID allegati da scaricare dal backend e allegare |
| `delete_attachments` | boolean | No | `false` | Elimina gli allegati dopo invio riuscito |

> I nomi dei campi sono in `snake_case`, coerenti col payload dello stream `email:send`.

### Risposte

| HTTP | Quando | Body |
|------|--------|------|
| `200 OK` | Email inviata | `{"status":"sent","logId":123,"timestamp":"..."}` |
| `400 Bad Request` | Body mancante o `to`/`subject` assenti | `{"status":"error","error":"...","timestamp":"..."}` |
| `401 Unauthorized` | `X-API-Key` mancante o non valida | `{"status":"error","error":"API key mancante o non valida","timestamp":"..."}` |
| `500 Internal Server Error` | Errore invio (SMTP, allegati, ecc.) | `{"status":"error","error":"...","timestamp":"..."}` |
| `503 Service Unavailable` | `email.api.keys` non configurato sul server | `{"status":"error","error":"API key non configurate sul server","timestamp":"..."}` |

### Esempio — email HTML con CC

```bash
curl -X POST http://localhost:8080/email \
  -H "X-API-Key: change-me-email-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "to": ["mario@example.it", "luigi@example.it"],
    "cc": ["pm@example.it"],
    "subject": "Report giornaliero",
    "body": "<h1>Report</h1><p>Tutto ok.</p>",
    "is_html": true
  }'
```

Risposta:

```json
{ "status": "sent", "logId": 42, "timestamp": "2026-06-18T10:00:00Z" }
```

### Esempio — testo semplice con allegati

```bash
curl -X POST http://localhost:8080/email \
  -H "X-API-Key: change-me-email-api-key" \
  -H "Content-Type: application/json" \
  -d '{
    "to": ["cliente@example.it"],
    "subject": "Documenti",
    "body": "In allegato i documenti richiesti.",
    "attachments": [12, 34],
    "delete_attachments": true
  }'
```

> Gli `attachments` sono **ID** di allegati già presenti nel backend BERLink; il servizio
> li scarica via `backend.api`. Se anche un solo download fallisce, l'invio è bloccato.

---

## Configurazione

In `src/main/resources/application.yml`:

```yaml
email:
  api:
    keys: ${EMAIL_API_KEYS:change-me-email-api-key}
```

- **Default file**: una chiave (`change-me-email-api-key`) — **da cambiare in produzione**.
- **Più chiavi in produzione**: variabile d'ambiente `EMAIL_API_KEYS` con valori separati
  da virgola:

  ```
  EMAIL_API_KEYS=key-app-a,key-app-b,key-app-c
  ```

- Se la lista è vuota, l'endpoint risponde `503` (auth impossibile).

Binding gestito da `EmailApiProperties` (`@ConfigurationProperties(prefix = "email.api")`).

---

## Alternativa: invio via Valkey Stream

Lo stesso invio è ottenibile pubblicando un messaggio sullo stream `events-stream`
con `eventType = email:send` e un campo `parameters` (JSON con gli stessi campi del body
sopra). Vedi `notifications.yml` (mapping `direct-email: true`) e `ValkeyStreamListener`.

```bash
redis-cli XADD events-stream '*' \
  eventType email:send \
  parameters '{"to":["test@x.it"],"subject":"Hi","body":"Hello"}'
```

| | REST `POST /email` | Stream `email:send` |
|---|---|---|
| Trigger | HTTP sincrono | Messaggio Valkey asincrono |
| Auth | `X-API-Key` | Accesso allo stream |
| Esito immediato | Sì (`logId` in risposta) | No (solo `email_send_log`) |

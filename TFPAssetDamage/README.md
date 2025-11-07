# TFP Asset Damage Collector

Batch scheduler service per la raccolta giornaliera di dati di danno asset da TFP API e scrittura su Redis Stream.

## Descrizione

Questo servizio Node.js esegue job programmati giornalmente per:
1. Autenticarsi all'API TFP
2. Recuperare i dati di asset damage
3. Elaborare i dati ricevuti
4. Scrivere i risultati (successo o errore) su Redis Stream

Il servizio garantisce robustezza con retry automatici, gestione completa degli errori, logging strutturato e graceful shutdown.

## Stack Tecnologico

- **Runtime**: Node.js 18+ (Alpine Linux)
- **Scheduler**: node-cron
- **Database**: Redis (Stream)
- **HTTP Client**: axios
- **Logging**: winston
- **Container**: Docker

## Struttura Progetto

```
TFPAssetDamageCollector/
├── Dockerfile              # Immagine Docker multi-stage
├── .dockerignore          # Esclusioni build Docker
├── docker-compose.yml     # Stack locale (app + Redis)
├── package.json           # Dipendenze Node.js
├── .env.example          # Template variabili d'ambiente
├── README.md             # Questa documentazione
└── src/
    ├── index.js          # Entry point con scheduler
    ├── job.js            # Logica principale del job
    ├── api-client.js     # Client TFP API con auth
    ├── redis-client.js   # Client Redis Stream
    ├── logger.js         # Logger Winston
    └── config.js         # Gestione configurazione
```

## Prerequisiti

- **Docker** 20.10+
- **Docker Compose** 2.0+
- Credenziali API TFP valide

## Configurazione

### 1. Crea il file `.env`

Copia il file `.env.example` e compila con i tuoi valori:

```bash
cp .env.example .env
```

### 2. Modifica le variabili necessarie

Apri `.env` e configura almeno:

```bash
# OBBLIGATORIO: Credenziali TFP
TPF_API_URL=https://your-tfp-api.com
TPF_API_USERNAME=your_username
TPF_API_PASSWORD=your_password

# Opzionale: Scheduler (default: 18:00 ogni giorno)
CRON_SCHEDULE=0 18 * * *
TZ=Europe/Rome

# Opzionale: Redis
REDIS_STREAM_KEY=asset-damage-results

# Opzionale: Logging
LOG_LEVEL=info
```

## Esecuzione Locale

### Con Docker Compose (Raccomandato)

Avvia sia il servizio che Redis:

```bash
docker-compose up -d
```

Visualizza i log:

```bash
docker-compose logs -f tfp-collector
```

Ferma i servizi:

```bash
docker-compose down
```

### Build manuale

```bash
# Build immagine
docker build -t tfp-asset-damage-collector .

# Esegui container
docker run -d \
  --name tfp-collector \
  --env-file .env \
  --network host \
  tfp-asset-damage-collector
```

## Deploy in Produzione

### Con Portainer

1. **Crea uno Stack** in Portainer
2. **Incolla il contenuto** di `docker-compose.yml`
3. **Configura le variabili d'ambiente** nella sezione "Environment variables"
4. **Deploy** dello stack

### Variabili d'Ambiente da Configurare

In produzione, assicurati di impostare:

```bash
TPF_API_URL=<URL_API_PRODUZIONE>
TPF_API_USERNAME=<USERNAME_PRODUZIONE>
TPF_API_PASSWORD=<PASSWORD_PRODUZIONE>
REDIS_HOST=<REDIS_HOST_PRODUZIONE>
REDIS_PORT=<REDIS_PORT_PRODUZIONE>
CRON_SCHEDULE=0 18 * * *
TZ=Europe/Rome
LOG_LEVEL=info
```

### Health Check

Il servizio registra nel log quando è pronto:

```
Service is running, waiting for scheduled jobs...
```

Puoi monitorare il contenitore con:

```bash
docker logs -f <container_name>
```

## Formato Dati Redis Stream

I risultati vengono scritti nel Redis Stream con questa struttura:

```json
{
  "jobId": "uuid-v4",
  "timestamp": "2025-11-06T18:00:00.000Z",
  "status": "success|error",
  "executionTime": 1234,
  "data": {
    "recordCount": 42,
    "processedAt": "2025-11-06T18:00:01.234Z",
    "records": [...]
  },
  "error": {
    "message": "Error description",
    "code": "ERROR_CODE",
    "details": {}
  }
}
```

### Lettura dallo Stream

Per leggere i risultati da Redis:

```bash
# Ultimi 10 messaggi
docker exec -it tfp-redis redis-cli XREVRANGE daily-job-results + - COUNT 10

# Oppure con redis-cli
redis-cli XREAD STREAMS daily-job-results 0
```

## Comandi Utili

### Gestione Container

```bash
# Visualizza stato servizi
docker-compose ps

# Visualizza log in tempo reale
docker-compose logs -f

# Restart servizio
docker-compose restart tfp-collector

# Stop senza rimuovere
docker-compose stop

# Rimuovi completamente (incluso volume Redis)
docker-compose down -v
```

### Debug

```bash
# Entra nel container
docker exec -it tfp-asset-damage-collector sh

# Controlla variabili d'ambiente
docker exec tfp-asset-damage-collector env

# Controlla log Redis
docker-compose logs redis

# Test connessione Redis
docker exec -it tfp-redis redis-cli PING
```

### Testing Manuale Job

Per testare il job senza aspettare lo scheduler, puoi temporaneamente modificare `CRON_SCHEDULE` per eseguire ogni minuto:

```bash
CRON_SCHEDULE=* * * * *
```

Oppure eseguire il job direttamente nel container:

```bash
docker exec -it tfp-asset-damage-collector node -e "require('./src/job').executeJob()"
```

## Logging

Il servizio utilizza Winston per logging strutturato JSON. Livelli disponibili:

- **error**: Errori critici
- **warn**: Avvisi
- **info**: Informazioni operative (default)
- **debug**: Debug dettagliato

Per abilitare debug logging:

```bash
LOG_LEVEL=debug
```

### Esempi di Log

```
2025-11-06 18:00:00 [info]: Job started {"jobId":"abc-123"}
2025-11-06 18:00:01 [info]: Fetching asset damage data
2025-11-06 18:00:02 [info]: Asset damage data fetched successfully {"recordCount":42}
2025-11-06 18:00:03 [info]: Job completed successfully {"jobId":"abc-123"}
2025-11-06 18:00:04 [info]: Result written to Redis Stream {"jobId":"abc-123","status":"success"}
```

## Gestione Errori

Il servizio implementa gestione errori robusta:

1. **Retry automatico**: 3 tentativi con backoff esponenziale (1s, 2s, 4s)
2. **Token refresh**: Ri-autenticazione automatica se il token scade
3. **Scrittura garantita**: Risultati sempre scritti su Redis, anche in caso di errore
4. **Graceful shutdown**: Chiusura pulita delle connessioni su SIGTERM/SIGINT

### Esempio Errore

Se l'API fallisce, il risultato conterrà:

```json
{
  "status": "error",
  "error": {
    "message": "Request failed with status code 500",
    "code": "ECONNREFUSED",
    "details": {...}
  }
}
```

## Sicurezza

- Container eseguito con utente **non-root** (nodejs:1001)
- **dumb-init** per gestione corretta dei segnali
- Secrets gestiti tramite variabili d'ambiente (non committare `.env`)
- Immagine Alpine minimale per ridurre superficie d'attacco

## Troubleshooting

### Il job non parte

1. Verifica la validità del cron schedule:
   ```bash
   docker-compose logs tfp-collector | grep "Invalid cron"
   ```

2. Controlla il timezone:
   ```bash
   docker exec tfp-asset-damage-collector date
   ```

### Errori di connessione Redis

```bash
# Verifica che Redis sia up
docker-compose ps redis

# Test connessione
docker exec -it tfp-redis redis-cli PING
```

### Errori API

Controlla:
- URL API corretto (senza trailing slash)
- Credenziali valide
- API raggiungibile dal container

```bash
# Test connettività
docker exec tfp-asset-damage-collector wget -O- https://your-api.com
```

## Manutenzione

### Pulizia Log

I log Docker sono limitati a 10MB x 3 file (configurato in docker-compose.yml).

Per pulire manualmente:

```bash
docker-compose logs --no-log-prefix tfp-collector > backup.log
docker-compose restart tfp-collector
```

### Backup Redis

```bash
# Backup dati Redis
docker exec tfp-redis redis-cli SAVE
docker cp tfp-redis:/data/dump.rdb ./backup-$(date +%Y%m%d).rdb
```

## Sviluppo

### Installazione dipendenze

```bash
npm install
```

### Esecuzione locale (senza Docker)

1. Avvia Redis localmente
2. Configura `.env`
3. Esegui:

```bash
npm start
```

### Watch mode per sviluppo

```bash
npm run dev
```

## Licenza

ISC

## Supporto

Per problemi o domande, contattare il team di sviluppo.

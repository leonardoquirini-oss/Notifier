# Prompt per Claude Code: Servizio Batch Scheduler Docker

Crea un servizio batch scheduler in Node.js da eseguire in Docker per elaborazioni giornaliere programmate.

## Requisiti Funzionali

1. **Scheduler giornaliero** che esegue un job a orario configurabile (default: 18:00)
2. **Chiamata API esterna** per recuperare dati
3. **Elaborazione dati** ricevuti dall'API
4. **Scrittura su Redis Stream** del risultato elaborato
5. **Gestione errori robusta**: in caso di fallimento, scrivere comunque in Redis un oggetto con le informazioni dell'errore
6. **Logging strutturato** di tutte le operazioni

## Specifiche Tecniche

### Stack
- **Node.js 18+** (Alpine per immagine Docker leggera)
- **node-cron** per scheduling
- **ioredis** per interazione con Redis Stream
- **axios** per chiamate HTTP
- **winston** per logging

### Struttura Progetto
```
TFPAssetDamageCollector/
├── Dockerfile
├── .dockerignore
├── package.json
├── .env.example
├── src/
│   ├── index.js          # Entry point con scheduler
│   ├── job.js            # Logica principale del job
│   ├── api-client.js     # Client per API esterna
│   ├── redis-client.js   # Client Redis con gestione stream
│   ├── logger.js         # Configurazione winston
│   └── config.js         # Gestione configurazione da env
└── docker-compose.yml    # Per test locale
```

### Configurazione (Variabili d'Ambiente)
```
# Scheduling
CRON_SCHEDULE=0 8 * * *
TZ=Europe/Rome

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_STREAM_KEY=daily-job-results

# API Esterna
TPF_API_URL=https://api.example.com/data    
TPF_API_TIMEOUT=30000
TPF_API_RETRY_ATTEMPTS=3
TPF_API_USERNAME=xxx
TPF_API_PASSWORD=xxx

# Logging
LOG_LEVEL=info
```

### Oggetto Risultato in Redis

L'oggetto scritto nel Redis Stream deve avere questa struttura:
```json
{
  "jobId": "uuid-v4",
  "timestamp": "2025-11-06T08:00:00.000Z",
  "status": "success|error",
  "executionTime": 1234,
  "data": { /* dati elaborati se successo */ },
  "error": {
    "message": "Error description",
    "code": "ERROR_CODE",
    "details": {}
  }
}
```

### Funzionalità Richieste

1. **Scheduler con node-cron**:
   - Esecuzione programmata giornaliera
   - Orario configurabile da env
   - Graceful shutdown su SIGTERM/SIGINT

2. **Client API**:
   - Timeout configurabile
   - Retry automatico (3 tentativi con backoff esponenziale)
   - Gestione errori HTTP dettagliata
   - Login su url TFP_API_URL + "core/auth/" in POST passando un body cosi fatto { "username":"user", "password":"pwd" } . La risposta contiene un attributo "token" che rappresenta il token da usare per le chiamate successive.
   - Prima API da implementare: "asset damage" (url : TFP_API_URL + "units-tracking/assetdamage/browse"). Per questa API deve essere passato il Bearer token ottenuto dal login, un header Content-Type : application/json ed un body cosi fatto:

```
{
    "offset": 0,
    "limit": 200,
    "filter": {
        "enabled": 1,
        "assetType": "UNIT",
        "status": "OPEN"
    },
    "sortingList": [
        {
            "column": "reportTime",
            "direction": "DESC"
        }
    ]
}   
```    

3. **Elaborazione Dati**:
   - Funzione modulare `processData()` facilmente estendibile;
   - Validazione dati ricevuti
   - Trasformazione in formato appropriato

4. **Redis Stream Writer**:
   - Connessione a Redis con retry automatico
   - Scrittura su stream con XADD
   - Gestione errori di connessione
   - Chiusura connessione pulita

5. **Logging**:
   - Log strutturati JSON
   - Livelli: error, warn, info, debug
   - Include timestamp, jobId, e contesto
   - Output su console (per Docker logs)

6. **Gestione Errori**:
   - Try-catch su tutte le operazioni async
   - **SEMPRE scrivere in Redis**, sia in caso di successo che errore
   - Log dettagliato degli errori
   - Non bloccare l'applicazione in caso di singolo fallimento

7. **Docker**:
   - Immagine multi-stage per ottimizzazione dimensioni
   - Health check endpoint opzionale (HTTP server minimale)
   - `restart: unless-stopped` nel compose
   - Non eseguire come root

### Esempio Flow del Job
```javascript
async function executeJob() {
  const jobId = uuidv4();
  const startTime = Date.now();
  
  logger.info('Job started', { jobId });
  
  const result = {
    jobId,
    timestamp: new Date().toISOString(),
    status: 'success',
    data: null,
    error: null,
    executionTime: 0
  };

  try {
    // 1. Chiamata API
    const apiData = await apiClient.fetchData();
    
    // 2. Elaborazione
    const processedData = processData(apiData);
    
    // 3. Prepara risultato successo
    result.data = processedData;
    result.status = 'success';
    
  } catch (error) {
    // 4. Gestione errore
    logger.error('Job failed', { jobId, error: error.message });
    result.status = 'error';
    result.error = {
      message: error.message,
      code: error.code || 'UNKNOWN_ERROR',
      details: error.response?.data || {}
    };
    
  } finally {
    // 5. SEMPRE scrivere in Redis
    result.executionTime = Date.now() - startTime;
    await redisClient.writeResult(result);
    logger.info('Job completed', { jobId, status: result.status, executionTime: result.executionTime });
  }
}
```

### Docker Compose per Test

Includi un docker-compose.yml che avvii:
- Il servizio batch-scheduler
- Un container Redis per test
- Network condiviso

### File .env.example

Crea un file .env.example con tutte le variabili necessarie e valori di esempio.

### README.md

Includi un README con:
- Descrizione del servizio
- Prerequisiti
- Come configurare
- Come eseguire localmente
- Come deployare in produzione
- Esempi di comandi utili (logs, restart, etc.)

## Note Aggiuntive

- Usa **async/await** consistentemente
- Gestisci la timezone correttamente (Europe/Rome)
- Aggiungi commenti dove necessario per chiarezza
- Valida le variabili d'ambiente all'avvio
- Implementa graceful shutdown per chiudere connessioni pulite

## Output Atteso

Tutti i file del progetto pronti per essere committati e deployati in un ambiente Docker con Portainer.
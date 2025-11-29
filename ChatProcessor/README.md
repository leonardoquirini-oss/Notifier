# Chat Processor Service

Consumer Valkey Stream per il logging delle chiamate al chatbot AI.

## Descrizione

Il **Chat Processor** è un microservizio Spring Boot che consuma eventi dallo stream Valkey `chatbot-log-stream` e persiste i dati nella tabella PostgreSQL `ai_chatbot_log_calls`.

Il servizio gestisce due tipi di eventi:
- `chatbot:request` - Richiesta inviata al chatbot → INSERT nella tabella
- `chatbot:response` - Risposta ricevuta dal chatbot → UPDATE della riga corrispondente

## Architettura

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   BERLink BE    │     │     Valkey      │     │ Chat Processor  │
│ ChatController  │────▶│  chatbot-log-   │────▶│   Consumer      │
│                 │     │     stream      │     │                 │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                                                         ▼
                                               ┌─────────────────┐
                                               │   PostgreSQL    │
                                               │ ai_chatbot_log_ │
                                               │     calls       │
                                               └─────────────────┘
```

### Flusso Dati

1. **ChatController** (backend) riceve richiesta chatbot
2. Genera `correlationId` (UUID) e ottiene `id_employee`
3. Pubblica evento `chatbot:request` su Valkey stream
4. **ChatProcessor** riceve evento → INSERT nella tabella
5. **ChatController** chiama DbAgent e ottiene risposta
6. Pubblica evento `chatbot:response` su Valkey stream
7. **ChatProcessor** riceve evento → UPDATE della riga con stesso `correlationId`

## Struttura Progetto

```
ChatProcessor/
├── src/main/java/com/containermgmt/chatprocessor/
│   ├── ChatProcessorApplication.java    # Main Spring Boot
│   ├── config/
│   │   ├── ActiveJDBCConfig.java        # Connessione PostgreSQL
│   │   ├── ValkeyConfig.java            # Connessione Valkey
│   │   └── JacksonConfig.java           # ObjectMapper JSON
│   ├── listener/
│   │   └── ChatLogStreamListener.java   # Consumer Valkey Stream
│   ├── service/
│   │   └── ChatLogService.java          # Logica INSERT/UPDATE
│   ├── model/
│   │   └── AiChatbotLogCall.java        # ActiveJDBC Model
│   └── dto/
│       └── ChatEvent.java               # DTO evento
├── src/main/resources/
│   └── application.yml                   # Configurazione
├── Dockerfile                            # Multi-stage build
└── pom.xml                               # Dipendenze Maven
```

## Configurazione

### application.yml

```yaml
spring:
  application:
    name: chat-processor-service
  datasource:
    url: jdbc:postgresql://${DB_HOST:postgres-service}:${DB_PORT:5432}/${DB_NAME:berlinkdb}
    username: ${DB_USER:berlink}
    password: ${DB_PASSWORD:berlink}
    driver-class-name: org.postgresql.Driver

valkey:
  host: ${VALKEY_HOST:valkey-service}
  port: ${VALKEY_PORT:6379}
  database: 0
  password: ${VALKEY_PASSWORD:}

chatprocessor:
  stream: chatbot-log-stream
  consumer-group: chatprocessor-group
```

## Variabili d'Ambiente

| Variabile | Default | Descrizione |
|-----------|---------|-------------|
| `DB_HOST` | postgres-service | Hostname PostgreSQL |
| `DB_PORT` | 5432 | Porta PostgreSQL |
| `DB_NAME` | berlinkdb | Nome database |
| `DB_USER` | berlink | Username database |
| `DB_PASSWORD` | berlink | Password database |
| `VALKEY_HOST` | valkey-service | Hostname Valkey/Redis |
| `VALKEY_PORT` | 6379 | Porta Valkey |
| `VALKEY_PASSWORD` | (vuoto) | Password Valkey (opzionale) |

## Build e Deploy

### Build Locale

```bash
cd Processors/ChatProcessor
mvn clean package -DskipTests
```

### Esecuzione Locale

```bash
java -jar target/chat-processor-1.0.0.jar \
  --spring.datasource.url=jdbc:postgresql://localhost:5432/berlinkdb \
  --valkey.host=localhost
```

## Docker

### Build Image

```bash
docker build -t chat-processor:latest .
```

### Run Container

```bash
docker run -d \
  --name chat-processor \
  --network berlink-network \
  -e DB_HOST=postgres-service \
  -e VALKEY_HOST=valkey-service \
  chat-processor:latest
```

### Docker Compose

Aggiungi al `docker-compose.yml` principale:

```yaml
services:
  chat-processor:
    build: ./Processors/ChatProcessor
    container_name: chat-processor
    environment:
      - DB_HOST=postgres-service
      - DB_PORT=5432
      - DB_NAME=berlinkdb
      - DB_USER=berlink
      - DB_PASSWORD=berlink
      - VALKEY_HOST=valkey-service
      - VALKEY_PORT=6379
    depends_on:
      - postgres-service
      - valkey-service
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 3s
      retries: 3
```

## Tabella Database

### ai_chatbot_log_calls

```sql
CREATE TABLE ai_chatbot_log_calls (
  id_ai_chatbot_log_req integer PRIMARY KEY,
  correlation_id varchar(100) NOT NULL,
  request_timestamp timestamp NOT NULL,
  id_user_request integer NOT NULL REFERENCES emp_employees(id_employee),
  request_url varchar(200) NOT NULL,
  request text NOT NULL,
  answer_timestamp timestamp,
  answer text
);

CREATE SEQUENCE s_ai_chatbot_log_calls START 1000;
```

### Indici

```sql
-- Full-text search
CREATE INDEX idx_chatbot_request_tsv ON ai_chatbot_log_calls USING GIN(request_tsv);
CREATE INDEX idx_chatbot_answer_tsv ON ai_chatbot_log_calls USING GIN(answer_tsv);

-- Trigram per LIKE/ILIKE
CREATE INDEX idx_chatbot_request_trgm ON ai_chatbot_log_calls USING GIN(request gin_trgm_ops);
CREATE INDEX idx_chatbot_answer_trgm ON ai_chatbot_log_calls USING GIN(answer gin_trgm_ops);

-- B-tree
CREATE INDEX idx_chatbot_request_timestamp ON ai_chatbot_log_calls(request_timestamp);
CREATE INDEX idx_chatbot_user_request ON ai_chatbot_log_calls(id_user_request);
```

## Eventi Valkey

### Stream: `chatbot-log-stream`

### Consumer Group: `chatprocessor-group`

### Evento `chatbot:request`

```json
{
  "eventType": "chatbot:request",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "requestUrl": "/api/chat",
  "request": "Quanti ordini ci sono oggi?",
  "requestTimestamp": 1701234567890,
  "userId": 123
}
```

### Evento `chatbot:response`

```json
{
  "eventType": "chatbot:response",
  "correlationId": "550e8400-e29b-41d4-a716-446655440000",
  "answer": "Ci sono 15 ordini per oggi...",
  "answerTimestamp": 1701234568890,
  "success": true,
  "sqlQuery": "SELECT COUNT(*) FROM orders WHERE date = CURRENT_DATE",
  "modelUsed": "gpt-4",
  "userId": 123
}
```

## Logica di Elaborazione

### chatbot:request → INSERT

```sql
INSERT INTO ai_chatbot_log_calls (
  id_ai_chatbot_log_req,
  correlation_id,
  request_timestamp,
  id_user_request,
  request_url,
  request
) VALUES (
  nextval('s_ai_chatbot_log_calls'),
  :correlationId,
  to_timestamp(:requestTimestamp / 1000),
  :userId,
  :requestUrl,
  :request
);
```

### chatbot:response → UPDATE

```sql
UPDATE ai_chatbot_log_calls
SET answer_timestamp = to_timestamp(:answerTimestamp / 1000),
    answer = :answer
WHERE correlation_id = :correlationId;
```

### Edge Case: Response senza Request

Se l'UPDATE non trova righe (request mai ricevuta), viene eseguito un INSERT con dati parziali:

```sql
INSERT INTO ai_chatbot_log_calls (
  id_ai_chatbot_log_req,
  correlation_id,
  request_timestamp,
  id_user_request,
  request_url,
  request,
  answer_timestamp,
  answer
) VALUES (
  nextval('s_ai_chatbot_log_calls'),
  :correlationId,
  to_timestamp(:answerTimestamp / 1000),
  :userId,
  '/api/chat',
  '[REQUEST MANCANTE]',
  to_timestamp(:answerTimestamp / 1000),
  :answer
);
```

## Health Check

Il servizio espone endpoint di health check tramite Spring Boot Actuator:

```
GET http://localhost:8080/actuator/health
GET http://localhost:8080/actuator/info
GET http://localhost:8080/actuator/metrics
```

## Logging

### Livelli di Log

| Logger | Livello |
|--------|---------|
| `root` | INFO |
| `com.containermgmt.chatprocessor` | DEBUG |
| `org.javalite.activejdbc` | INFO |
| `org.springframework.data.redis` | INFO |

### Log Utili

```bash
# Seguire i log del container
docker logs -f chat-processor

# Filtrare per correlationId
docker logs chat-processor 2>&1 | grep "correlationId=abc123"

# Filtrare per errori
docker logs chat-processor 2>&1 | grep -i error
```

## Troubleshooting

### Il consumer non riceve messaggi

1. Verifica che lo stream esista:
   ```bash
   redis-cli XINFO STREAM chatbot-log-stream
   ```

2. Verifica il consumer group:
   ```bash
   redis-cli XINFO GROUPS chatbot-log-stream
   ```

3. Verifica i pending messages:
   ```bash
   redis-cli XPENDING chatbot-log-stream chatprocessor-group
   ```

### Errore connessione database

1. Verifica le credenziali in `application.yml`
2. Verifica che PostgreSQL sia raggiungibile:
   ```bash
   docker exec chat-processor curl -v postgres-service:5432
   ```

### Messaggi non persistiti

1. Controlla i log per errori SQL
2. Verifica che la tabella esista:
   ```sql
   SELECT * FROM ai_chatbot_log_calls LIMIT 1;
   ```

3. Verifica la sequence:
   ```sql
   SELECT last_value FROM s_ai_chatbot_log_calls;
   ```

## Scaling

Il servizio supporta scaling orizzontale grazie ai consumer groups di Valkey:

```bash
# Docker Compose
docker-compose up -d --scale chat-processor=3

# Kubernetes
kubectl scale deployment chat-processor --replicas=3
```

Ogni istanza riceverà una porzione dei messaggi, garantendo che ogni messaggio sia elaborato esattamente una volta.

## Tecnologie

- **Java 17**
- **Spring Boot 3.1.5**
- **Spring Data Redis** (Lettuce)
- **ActiveJDBC 3.0**
- **PostgreSQL**
- **Valkey/Redis Streams**
- **Docker** (multi-stage build)

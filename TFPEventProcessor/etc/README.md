# TFP Event Processor

Spring Boot application che consuma eventi da code Apache Artemis e li persiste su PostgreSQL usando ActiveJDBC ORM.

## Tech Stack

- **Framework**: Spring Boot 3.1.5
- **Messaging**: Apache Artemis (JMS)
- **ORM**: JavaLite ActiveJDBC 3.0
- **Database**: PostgreSQL
- **Java**: 17
- **Deployment**: Docker

## Struttura Progetto

```
TFPEventProcessor/
├── src/main/java/com/containermgmt/tfpeventprocessor/
│   ├── TfpEventProcessorApplication.java
│   ├── config/
│   │   ├── ActiveJDBCConfig.java
│   │   ├── ArtemisConfig.java
│   │   └── JacksonConfig.java
│   ├── listener/
│   │   └── EventListener.java
│   ├── service/
│   │   └── EventProcessorService.java
│   ├── model/
│   │   └── Event.java
│   ├── dto/
│   │   └── EventMessage.java
│   └── exception/
│       └── EventProcessingException.java
├── src/main/resources/
│   ├── application.yml
│   └── application-docker.yml
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## Configurazione

### Environment Variables

| Variable | Default | Descrizione |
|----------|---------|-------------|
| `ARTEMIS_HOST` | localhost | Host del broker Artemis |
| `ARTEMIS_PORT` | 61616 | Porta del broker Artemis |
| `ARTEMIS_USER` | admin | Username Artemis |
| `ARTEMIS_PASSWORD` | admin | Password Artemis |
| `DB_HOST` | localhost | Host PostgreSQL |
| `DB_PORT` | 5432 | Porta PostgreSQL |
| `DB_NAME` | berlinkdb | Nome database |
| `DB_USER` | berlink | Username database |
| `DB_PASSWORD` | berlink | Password database |
| `EVENT_QUEUE_PRIMARY` | events.queue | Nome coda principale |

### Code Artemis

Le code da ascoltare sono configurabili in `application.yml`:

```yaml
event-processor:
  queues:
    primary: events.queue
    # secondary: events.queue.secondary  # Aggiungi altre code se necessario
```

## Build & Run

### Build con Maven

```bash
./mvnw clean package
```

### Run locale

```bash
java -jar target/tfp-event-processor-*.jar
```

### Run con Docker Compose

```bash
docker-compose up -d --build
```

### Logs

```bash
docker-compose logs -f tfp-event-processor
```

## Database

### Schema tabella (esempio)

```sql
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    source VARCHAR(255),
    event_timestamp TIMESTAMP WITH TIME ZONE,
    payload JSONB,
    metadata JSONB,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_events_event_id ON events(event_id);
CREATE INDEX idx_events_event_type ON events(event_type);
CREATE INDEX idx_events_processed_at ON events(processed_at);
```

**Nota**: Aggiornare il modello `Event.java` e l'annotazione `@Table` quando viene definita la struttura finale del database.

## Formato Eventi

### EventMessage (JSON)

```json
{
  "eventId": "evt-12345",
  "eventType": "ORDER_CREATED",
  "source": "order-service",
  "timestamp": "2025-12-15T10:30:00Z",
  "payload": {
    "orderId": "ORD-001",
    "customerId": "CUST-123",
    "amount": 99.99
  },
  "metadata": {
    "version": "1.0",
    "region": "EU"
  }
}
```

## Health Check

- **Endpoint**: `http://localhost:8080/actuator/health`
- **Metrics**: `http://localhost:8080/actuator/metrics`

## Aggiungere Nuove Code

Per ascoltare una nuova coda, aggiungere un nuovo metodo nel `EventListener.java`:

```java
@JmsListener(
    destination = "${event-processor.queues.secondary:events.queue.secondary}",
    containerFactory = "jmsListenerContainerFactory"
)
public void onSecondaryQueueMessage(EventMessage eventMessage) {
    log.debug("Received message from secondary queue: eventId={}",
        eventMessage.getEventId());
    processWithRetry(eventMessage);
}
```

## Retry Logic

Il processor implementa retry automatico:
- **Max tentativi**: 3 (configurabile)
- **Delay tra tentativi**: 5000ms (configurabile)

Configurazione in `application.yml`:
```yaml
event-processor:
  retry-attempts: 3
  retry-delay-ms: 5000
```

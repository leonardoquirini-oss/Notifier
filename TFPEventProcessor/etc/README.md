# TFP Event Processor

Spring Boot application che consuma eventi da multicast addresses Apache Artemis (topic con durable subscriptions) e li persiste su PostgreSQL usando ActiveJDBC ORM.

## Tech Stack

- **Framework**: Spring Boot 3.1.5
- **Messaging**: Apache Artemis 2.31.2 (multicast/FQQN)
- **ORM**: JavaLite ActiveJDBC 3.0
- **Database**: PostgreSQL
- **Java**: 17
- **Deployment**: Docker

## Architettura Messaging

Il processor usa **multicast addresses** con **durable subscriptions** via FQQN (Fully Qualified Queue Name), con semantica simile a Kafka consumer groups.

```
Producer (GUI Artemis / TFP) ──► Address MULTICAST (es. BERNARDINI_ASSET_DAMAGES)
                                          │
                                          ├──► tfp-processor.BERNARDINI_ASSET_DAMAGES    (Processor A)
                                          ├──► tfp-audit.BERNARDINI_ASSET_DAMAGES         (Processor B)
                                          └──► tfp-analytics.BERNARDINI_ASSET_DAMAGES     (Processor C)
```

### Concetti chiave

| Concetto | Kafka equivalente | Descrizione |
|----------|-------------------|-------------|
| **Address (multicast)** | Topic | L'indirizzo a cui i messaggi vengono inviati |
| **subscriber-name** | Consumer Group ID | Identifica il gruppo di consumer |
| **Subscription queue** | Consumer Group partition | Coda dedicata per ogni subscriber-name |
| **FQQN** | — | `ADDRESS::SUBSCRIBER_NAME.ADDRESS` — formato Artemis per indirizzare la subscription queue |

### Comportamento

- **Stesso `subscriber-name`** → il processor riprende dall'ultimo messaggio consumato (come Kafka)
- **Nuovo `subscriber-name`** → nuova subscription indipendente, riceve solo i nuovi messaggi
- **Processor offline** → i messaggi si accumulano nella subscription queue e vengono consegnati al riavvio

## Configurazione

### Environment Variables

| Variable | Default | Descrizione |
|----------|---------|-------------|
| `SUBSCRIBER_NAME` | tfp-processor | Nome subscriber (consumer group ID) |
| `ARTEMIS_HOST` | localhost | Host del broker Artemis |
| `ARTEMIS_PORT` | 61616 | Porta del broker Artemis |
| `ARTEMIS_USER` | admin | Username Artemis |
| `ARTEMIS_PASSWORD` | admin | Password Artemis |
| `DB_HOST` | localhost | Host PostgreSQL |
| `DB_PORT` | 5432 | Porta PostgreSQL |
| `DB_NAME` | berlinkdb | Nome database |
| `DB_USER` | berlink | Username database |
| `DB_PASSWORD` | berlink | Password database |

### application.yml

```yaml
event-processor:
  # Multicast addresses (comma-separated)
  addresses: BERNARDINI_UNIT_POSITIONS_MESSAGE, BERNARDINI_ASSET_DAMAGES

  # Subscriber name (consumer group ID)
  subscriber-name: ${SUBSCRIBER_NAME:tfp-processor}

  concurrency: 3-10
  retry-attempts: 3
  retry-delay-ms: 5000
```

### broker.xml (Artemis)

Le addresses devono essere definite come **multicast** in `artemis-config/broker.xml`:

```xml
<address name="BERNARDINI_ASSET_DAMAGES">
   <multicast />
</address>
```

Il file viene montato nel container Artemis via docker-compose:
```yaml
volumes:
  - ./artemis-config/broker.xml:/var/lib/artemis-instance/etc-override/broker.xml
```

## Aggiungere una nuova Address

1. Aggiungere l'address multicast in `artemis-config/broker.xml`:
   ```xml
   <address name="NUOVA_ADDRESS">
      <multicast />
   </address>
   ```

2. Aggiungere l'address in `application.yml`:
   ```yaml
   event-processor:
     addresses: BERNARDINI_UNIT_POSITIONS_MESSAGE, BERNARDINI_ASSET_DAMAGES, NUOVA_ADDRESS
   ```

3. (Opzionale) Creare un handler specifico nel package `handler/`:
   ```java
   @Component
   @Order(1)
   public class NuovoEventHandler implements EventTypeHandler {
       @Override
       public Set<String> supportedEventTypes() {
           return Set.of("NUOVA_ADDRESS");
       }
       @Override
       public boolean supports(String eventType) {
           return "NUOVA_ADDRESS".equalsIgnoreCase(eventType);
       }
       @Override
       public void handle(EventMessage eventMessage) {
           // logica specifica
       }
   }
   ```

4. Riavviare Artemis (per applicare broker.xml) e il processor.

## Uso del Subscriber Name

### Produzione
```bash
# Subscriber fisso — riprende sempre da dove si era fermato
SUBSCRIBER_NAME=tfp-processor docker-compose up -d
```

### Dev/test — nuova subscription (ri-legge tutto)
```bash
# Genera un nome univoco — crea una nuova subscription queue
SUBSCRIBER_NAME=tfp-dev-$(date +%s) docker-compose up -d
```

### Processori multipli indipendenti
```bash
# Ogni subscriber-name diverso riceve TUTTI i messaggi indipendentemente
SUBSCRIBER_NAME=tfp-processor     # processor principale
SUBSCRIBER_NAME=tfp-audit         # audit processor parallelo
```

## Inviare messaggi di test

### Dalla GUI Artemis (http://localhost:8161)

1. Aprire la console web
2. Navigare a **Queues** → trovare la subscription queue (es. `tfp-processor.BERNARDINI_ASSET_DAMAGES`)
3. Oppure: navigare a **Addresses** → selezionare l'address → **Send Message**
4. Inserire il JSON del messaggio e inviare

**IMPORTANTE**: Inviare all'**Address** (non alla Queue). Artemis lo distribuisce a tutte le subscription queues.

### Dal sistema TFP esterno

Il sender deve usare `createTopic()` (non `createQueue()`):
```java
// Corretto: invia a multicast address
Destination dest = session.createTopic("BERNARDINI_ASSET_DAMAGES");

// SBAGLIATO: creerebbe una coda anycast separata
// Destination dest = session.createQueue("BERNARDINI_ASSET_DAMAGES");
```

## Build & Run

```bash
# Build
task build-ep

# Run con Docker Compose
docker-compose up -d --build

# Logs
docker-compose logs -f tfp-event-processor

# Stop
docker-compose down
```

### Prima volta / reset completo

Se cambi il broker.xml, devi ricreare il container Artemis per applicare la nuova config:
```bash
docker-compose down
docker volume ls | grep artemis  # identifica il volume
docker volume rm <volume-name>   # rimuovi i dati broker
docker-compose up -d --build
```

## Retry Logic

Il processor implementa retry applicativo (prima del rollback JMS):
- **Max tentativi**: 3 (configurabile)
- **Delay tra tentativi**: 5000ms (configurabile)

Se tutti i retry falliscono → JMS rollback → Artemis redelivery con delay 5s → dopo 10 tentativi il messaggio va in DLQ.

## Health Check

- **Health**: `http://localhost:8080/actuator/health`
- **Metrics**: `http://localhost:8080/actuator/metrics`

## Troubleshooting

### Messaggi non arrivano
1. Verificare che l'address sia **multicast** nella console Artemis
2. Verificare nei log: `Registering JMS listener ... via FQQN: ADDRESS::subscriber.ADDRESS`
3. Controllare che il producer invii all'address (topic), non a una queue (anycast)
4. Verificare che la subscription queue esista: `tfp-processor.BERNARDINI_*`

### Subscription queue non si crea
1. Verificare `auto-create-queues=true` nel broker.xml (address-settings per `BERNARDINI_#`)
2. Controllare i log Artemis per errori di permessi

### Messaggi duplicati su DB
Il DB usa `ON CONFLICT (message_id) DO UPDATE` — i duplicati vengono gestiti automaticamente via upsert.

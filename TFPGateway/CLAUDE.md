# TFP Gateway - Claude Code Reference

Spring Boot application che consuma eventi da Apache Artemis e li persiste su PostgreSQL usando ActiveJDBC ORM. Supporta due modalita' di consumo: **FQQN multicast** (durable subscriptions, Kafka-like consumer groups) e **DIRECT anycast** (competing consumers, round-robin).

## Tech Stack

- **Framework**: Spring Boot 3.1.5
- **Messaging**: Apache Artemis 2.31.2 (dual-mode: FQQN multicast o DIRECT anycast)
- **ORM**: JavaLite ActiveJDBC 3.0
- **Database**: PostgreSQL
- **Java**: 17
- **Deployment**: Docker

## Struttura Progetto

```
TFPGateway/
├── src/main/java/com/containermgmt/tfpgateway/
│   ├── TfpGatewayApplication.java
│   ├── config/
│   │   ├── ActiveJDBCConfig.java
│   │   ├── ArtemisConfig.java
│   │   ├── GatewayProperties.java          # @ConfigurationProperties per gateway.*
│   │   ├── GatewayPropertiesInitializer.java
│   │   ├── JacksonConfig.java
│   │   └── ValkeyConfig.java
│   ├── controller/
│   │   ├── EventBrowserController.java     # UI browse eventi
│   │   └── GatewayConfigController.java    # Runtime config / status
│   ├── listener/
│   │   └── EventListener.java
│   ├── service/
│   │   ├── EventProcessorService.java      # Upsert raw event + dispatch
│   │   ├── EventBrowserService.java
│   │   ├── GatewayLifecycleManager.java
│   │   └── ValkeyStreamPublisher.java      # Publish su Valkey stream
│   ├── handler/
│   │   ├── EventTypeHandler.java           # Strategy interface
│   │   ├── EventHandlerRegistry.java       # Dispatcher (O(1) HashMap)
│   │   ├── DefaultEventTypeHandler.java    # Catch-all fallback
│   │   ├── AssetDamageEventHandler.java    # BERNARDINI_ASSET_DAMAGES
│   │   ├── UnitEventEventHandler.java      # BERNARDINI_UNIT_EVENTS
│   │   └── UnitPositionEventHandler.java   # BERNARDINI_UNIT_POSITIONS_MESSAGE
│   ├── model/
│   │   └── RawEvent.java
│   ├── dto/
│   │   ├── EventMessage.java
│   │   ├── GatewayRuntimeConfig.java
│   │   └── GatewayStatusInfo.java
│   └── exception/
│       └── EventProcessingException.java
├── src/main/resources/
│   ├── application.yml
│   ├── db/
│   │   └── 01_setup.sql                   # Schema evt_raw_events
│   └── templates/
│       ├── events.html
│       └── gateway.html
├── artemis-config/
│   └── broker.xml              # Broker config (multicast addresses)
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```


## Configurazione

### Environment Variables

| Variable | Default | Descrizione |
|----------|---------|-------------|
| `SUBSCRIBER_NAME` | _(vuoto)_ | Nome subscriber. Se valorizzato attiva FQQN multicast; se vuoto/assente attiva DIRECT anycast |
| `ARTEMIS_HOST` | localhost | Host del broker Artemis |
| `ARTEMIS_PORT` | 61616 | Porta del broker Artemis |
| `ARTEMIS_USER` | admin | Username Artemis |
| `ARTEMIS_PASSWORD` | admin | Password Artemis |
| `DB_HOST` | localhost | Host PostgreSQL |
| `DB_PORT` | 5432 | Porta PostgreSQL |
| `DB_NAME` | berlinkdb | Nome database |
| `DB_USER` | berlink | Username database |
| `DB_PASSWORD` | berlink | Password database |

### Addresses e Modalita' di Consumo (Artemis)

Le addresses da ascoltare sono configurabili in `application.yml`:

```yaml
gateway:
  addresses: BERNARDINI_UNIT_POSITIONS_MESSAGE, BERNARDINI_ASSET_DAMAGES
  subscriber-name: ${SUBSCRIBER_NAME:}   # vuoto = DIRECT anycast
```

La modalita' di consumo e' determinata da `subscriber-name`:

| `subscriber-name` | Modalita' | `pubSubDomain` | Comportamento |
|---|---|---|---|
| valorizzato (es. `tfp-gateway-dev-01`) | **FQQN multicast** | `true` | Durable subscription, ogni consumer group riceve tutti i messaggi (Kafka-like) |
| vuoto / assente | **DIRECT anycast** | `false` | Competing consumers, round-robin tra le istanze |

La logica e' gestita in `GatewayLifecycleManager.startAll()` e `GatewayPropertiesInitializer.init()`.

Le addresses devono essere definite in `artemis-config/broker.xml` con il routing-type corrispondente alla modalita' scelta.

## Raw Event Pipeline

Ogni evento ricevuto da Artemis viene persistito in `evt_raw_events` tramite upsert idempotente su `message_id` (`EventProcessorService.upsertRawEvent()`).

**Colonne principali:**
- `message_id` — chiave di deduplicazione (UNIQUE)
- `payload` — JSON originale (JSONB)
- `checksum` — MD5 hex del payload JSON (32 char), calcolato lato applicazione prima dell'upsert. Utile per verificare integrità e confrontare payload senza parsing JSONB
- `processed_at` — timestamp di elaborazione

Dopo l'upsert, l'evento viene dispatchato all'handler specifico e pubblicato su Valkey stream.

## Aggiungere Nuove Addresses

1. Aggiungere l'address multicast in `artemis-config/broker.xml`
2. Aggiungere il nome in `application.yml` sotto `gateway.addresses`
3. (Opzionale) Creare un handler specifico nel package `handler/`
4. Riavviare Artemis e il processor

## Aggiungere Nuovi Event Handlers

Per gestire un nuovo tipo di evento, creare una classe `@Component` nel package `handler/`:

```java
@Component
@Order(10)
public class MyNewEventHandler implements EventTypeHandler {

    @Override
    public Set<String> supportedEventTypes() {
        return Set.of("MY_EVENT_TYPE");
    }

    @Override
    public boolean supports(String eventType) {
        return "MY_EVENT_TYPE".equalsIgnoreCase(eventType);
    }

    @Override
    public void handle(EventMessage eventMessage) {
        // logica specifica
    }
}
```

Il `EventHandlerRegistry` lo scopre automaticamente via component scan e O(1) HashMap lookup.

## Retry Logic

Il processor implementa retry automatico:
- **Max tentativi**: 3 (configurabile)
- **Delay tra tentativi**: 5000ms (configurabile)

Configurazione in `application.yml`:
```yaml
gateway:
  retry-attempts: 3
  retry-delay-ms: 5000
```

## Comandi Utili

```bash
# Build
task build-ep

# Logs
docker-compose logs -f tfp-gateway

# Console Artemis
# http://localhost:8161 (admin/admin)
```

## Documentazione

Documentazione dettagliata in `prompt/`:
- `API.md` - Endpoints API

---

## For Claude Code

## Workflow
- Start complex tasks in Plan mode
- Get plan approval before implementation
- Break large changes into reviewable chunks

### When Creating New Features
1. Seguire i pattern esistenti in controller/service simili
2. Usare DTO per separare API layer da database entities
3. Aggiungere `@Valid` per validazione request body
4. Wrappare response in `ApiResponse<T>`
5. Cerca di mantenere le funzioni piccole: <= 100 righe di codice. Se una funzione fa troppe cose, spezzala in funzioni helper piu' piccole
6. Applica principio DRY e NON DUPLICARE CODICE. Se una logica esiste in due posti, rifattorizzala in una funzione comune (o chiarisci perche' servono due implementazioni differenti se esiste un motivo valido)
7. Implementa un mini-agile cycle: proponi -> ottieni feedback -> implementa -> review
8. Verifica sempre il file prompt/API.md e le API esposte per capire se si puo' riusare qualche metodo o se e' necessario implementare nuove API

### When encountering a bug or failing test
1. First explain possible causes step-by-step. 
2. Check assumptions, inputs, and relevant code paths.

### When Fixing Bugs
1. Verificare i log backend (`docker-compose logs -f backend`)
2. Con bug critici aggiungi log (sia console che nel backend) per isolare la issue
3. Controllare console browser per errori frontend
4. No Silent Failures: Do not swallow exceptions silently. Always surface errors either by throwing or logging them.
5. Verificare token JWT e permessi Keycloak
6. Testare con ruoli diversi (cd, prisma_pm, prisma_user)

### When Refactoring
1. Mantenere backward compatibility API
2. Aggiornare DTO se cambiano response
3. Verificare che frontend gestisca nuovi campi

### When adding / updating API
1. Aggiorna il file API.md
2. Non rimuovere endpoint ma rendili deprecati (per backward compatibility)
3. For API changes, test with `curl` or Postman

### Questions to Ask Human
- Business logic requirements non chiari
- Preferenze UI/UX non specificate
- Requisiti di performance
- Considerazioni di sicurezza
- Nuovi ruoli o permessi necessari

---

## Keep This Updated

**When to update this file:**
- Dopo aggiunta di nuove dipendenze major
- Dopo modifiche architetturali
- Dopo cambio convenzioni di codice
- Quando emergono nuovi pattern

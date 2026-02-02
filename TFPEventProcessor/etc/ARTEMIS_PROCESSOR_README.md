# Apache Artemis Event Processor - Setup Guide

## Project Overview
Spring Boot application that consumes events from Apache Artemis queues and persists them to an existing PostgreSQL database using JavaLite ActiveJDBC ORM.

**Tech Stack:**
- **Framework**: Spring Boot 3.x
- **Messaging**: Apache Artemis (JMS)
- **ORM**: JavaLite ActiveJDBC
- **Database**: PostgreSQL (existing)
- **Deployment**: Docker + Docker Compose
- **Java Version**: 17 or 21

---

## Project Structure

```
artemis-processor/
├── src/
│   ├── main/
│   │   ├── java/com/processor/
│   │   │   ├── ArtemisProcessorApplication.java
│   │   │   ├── config/
│   │   │   │   ├── ArtemisConfig.java
│   │   │   │   └── ActiveJDBCConfig.java
│   │   │   ├── listener/
│   │   │   │   └── EventListener.java
│   │   │   ├── service/
│   │   │   │   └── EventProcessorService.java
│   │   │   ├── model/
│   │   │   │   └── Event.java (ActiveJDBC model)
│   │   │   ├── dto/
│   │   │   │   └── EventMessage.java
│   │   │   └── exception/
│   │   │       └── EventProcessingException.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-docker.yml
│   │       └── logback-spring.xml
│   └── test/
│       └── java/com/processor/
│           └── EventListenerTest.java
├── docker/
│   └── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## Maven Dependencies (pom.xml)

### Core Dependencies
```xml
<properties>
    <java.version>17</java.version>
    <spring-boot.version>3.2.1</spring-boot.version>
    <activejdbc.version>3.0</activejdbc.version>
</properties>

<dependencies>
    <!-- Spring Boot Starters -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>
    
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-artemis</artifactId>
    </dependency>
    
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    
    <!-- Artemis Client -->
    <dependency>
        <groupId>org.apache.activemq</groupId>
        <artifactId>artemis-jms-client-all</artifactId>
        <version>2.31.2</version>
    </dependency>
    
    <!-- JavaLite ActiveJDBC -->
    <dependency>
        <groupId>org.javalite</groupId>
        <artifactId>activejdbc</artifactId>
        <version>${activejdbc.version}</version>
    </dependency>
    
    <!-- PostgreSQL Driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    
    <!-- HikariCP Connection Pool -->
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
    </dependency>
    
    <!-- JSON Processing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    
    <!-- Lombok (Optional but recommended) -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    
    <!-- Testing -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId>
        </plugin>
        
        <!-- ActiveJDBC Instrumentation Plugin -->
        <plugin>
            <groupId>org.javalite</groupId>
            <artifactId>activejdbc-instrumentation</artifactId>
            <version>${activejdbc.version}</version>
            <executions>
                <execution>
                    <phase>process-classes</phase>
                    <goals>
                        <goal>instrument</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

---

## Configuration Files

### application.yml
```yaml
spring:
  application:
    name: artemis-processor
  
  artemis:
    mode: native
    broker-url: tcp://localhost:61616
    user: admin
    password: admin
    pool:
      enabled: true
      max-connections: 10
    
  jms:
    listener:
      auto-startup: true
      acknowledge-mode: auto
      concurrency: 3-10

# Database Configuration (existing PostgreSQL)
database:
  url: jdbc:postgresql://localhost:5432/your_database_name
  username: postgres
  password: postgres
  pool:
    maximum-pool-size: 10
    minimum-idle: 2
    connection-timeout: 30000
    idle-timeout: 600000
    max-lifetime: 1800000

# Event Processing Configuration
event-processor:
  queue-name: events.queue
  retry-attempts: 3
  retry-delay-ms: 5000

# Actuator Endpoints
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  level:
    root: INFO
    com.processor: DEBUG
    org.javalite: DEBUG
    org.springframework.jms: DEBUG
```

### application-docker.yml
```yaml
spring:
  artemis:
    broker-url: tcp://artemis:61616

database:
  url: jdbc:postgresql://postgres:5432/your_database_name
  username: ${DB_USERNAME:postgres}
  password: ${DB_PASSWORD:postgres}

logging:
  level:
    root: INFO
    com.processor: INFO
```

---

## Java Implementation

### 1. ActiveJDBC Configuration


Vedi come e' implementato il file /mnt/c/Projects/GIT/BERLinkPlatform/BERLink/backend/src/main/java/com/containermgmt/config/ActiveJDBCConfig.java

### 2. Artemis Configuration

**ArtemisConfig.java:**
```java
package com.processor.config;

import jakarta.jms.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;
import org.springframework.jms.support.converter.MessageConverter;
import org.springframework.jms.support.converter.MessageType;

@Configuration
@EnableJms
public class ArtemisConfig {

    @Value("${spring.jms.listener.concurrency:3-10}")
    private String concurrency;

    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        
        DefaultJmsListenerContainerFactory factory = 
            new DefaultJmsListenerContainerFactory();
        
        factory.setConnectionFactory(connectionFactory);
        factory.setConcurrency(concurrency);
        factory.setSessionTransacted(true);
        factory.setMessageConverter(messageConverter());
        
        // Error handling
        factory.setErrorHandler(t -> {
            System.err.println("Error in JMS listener: " + t.getMessage());
        });
        
        return factory;
    }

    @Bean
    public MessageConverter messageConverter() {
        MappingJackson2MessageConverter converter = 
            new MappingJackson2MessageConverter();
        converter.setTargetType(MessageType.TEXT);
        converter.setTypeIdPropertyName("_type");
        return converter;
    }
}
```

### 3. ActiveJDBC Model

Per i modelli ti daro' io la struttura del database sulla quale costruirli.

**Event.java:**
```java
package com.processor.model;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;

@Table("evt_events") // Specify existing table name
public class Event extends Model {
    
    // ActiveJDBC uses dynamic attributes
    // No need to declare fields explicitly
    
    // Example helper methods
    public String getEventType() {
        return getString("event_type");
    }
    
    public void setEventType(String eventType) {
        set("event_type", eventType);
    }
    
    public String getPayload() {
        return getString("payload");
    }
    
    public void setPayload(String payload) {
        set("payload", payload);
    }
    
    // Validation (optional)
    static {
        validatePresenceOf("event_type", "payload");
    }
}
```



### 4. Event DTO

Ecco la struttura dell'evento generico, tieni presente pero' che quando avro' a disposizione le strutture json degli eventi che arrivano potrei passartele per costruire dto specifici.

**EventMessage.java:**
```java
package com.processor.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventMessage {
    private String eventId;
    private String eventType;
    private String source;
    private Instant timestamp;
    private Map<String, Object> payload;
    private Map<String, String> metadata;
}
```

### 5. Event Processor Service

**EventProcessorService.java:**
```java
package com.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processor.dto.EventMessage;
import com.processor.exception.EventProcessingException;
import com.processor.model.Event;
import org.javalite.activejdbc.Base;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;

@Service
public class EventProcessorService {

    private static final Logger logger = 
        LoggerFactory.getLogger(EventProcessorService.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    public void processEvent(EventMessage eventMessage) {
        logger.info("Processing event: {}", eventMessage.getEventId());
        
        try {
            // Open ActiveJDBC connection
            Base.open(dataSource);
            
            // Create new event record
            Event event = new Event();
            event.set("event_id", eventMessage.getEventId());
            event.set("event_type", eventMessage.getEventType());
            event.set("source", eventMessage.getSource());
            event.set("timestamp", eventMessage.getTimestamp());
            event.set("payload", 
                objectMapper.writeValueAsString(eventMessage.getPayload()));
            event.set("metadata", 
                objectMapper.writeValueAsString(eventMessage.getMetadata()));
            event.set("processed_at", java.time.Instant.now());
            
            // Validate and save
            if (!event.save()) {
                throw new EventProcessingException(
                    "Validation failed: " + event.errors());
            }
            
            // Commit transaction
            Base.commitTransaction();
            
            logger.info("Successfully processed event: {}", 
                eventMessage.getEventId());
            
        } catch (Exception e) {
            // Rollback on error
            Base.rollbackTransaction();
            logger.error("Error processing event: {}", 
                eventMessage.getEventId(), e);
            throw new EventProcessingException(
                "Failed to process event: " + eventMessage.getEventId(), e);
        } finally {
            // Always close connection
            Base.close();
        }
    }
}
```

### 6. JMS Listener

**EventListener.java:**
```java
package com.processor.listener;

import com.processor.dto.EventMessage;
import com.processor.service.EventProcessorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

@Component
public class EventListener {

    private static final Logger logger = 
        LoggerFactory.getLogger(EventListener.class);

    @Autowired
    private EventProcessorService eventProcessorService;

    @Value("${event-processor.retry-attempts:3}")
    private int maxRetries;

    @Value("${event-processor.retry-delay-ms:5000}")
    private long retryDelay;

    @JmsListener(destination = "${event-processor.queue-name}")
    public void onMessage(EventMessage eventMessage) {
        logger.debug("Received message from queue: {}", 
            eventMessage.getEventId());
        
        int attempts = 0;
        boolean success = false;
        
        while (attempts < maxRetries && !success) {
            try {
                eventProcessorService.processEvent(eventMessage);
                success = true;
            } catch (Exception e) {
                attempts++;
                logger.warn("Processing attempt {} failed for event {}: {}", 
                    attempts, eventMessage.getEventId(), e.getMessage());
                
                if (attempts < maxRetries) {
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(
                            "Retry interrupted for event: " + 
                            eventMessage.getEventId(), ie);
                    }
                } else {
                    logger.error("Max retries reached for event: {}", 
                        eventMessage.getEventId());
                    // Send to DLQ or alert system
                    throw new RuntimeException(
                        "Failed to process event after " + maxRetries + 
                        " attempts: " + eventMessage.getEventId(), e);
                }
            }
        }
    }

    // Alternative: Direct String message processing
    @JmsListener(destination = "${event-processor.queue-name}.raw")
    public void onRawMessage(String messageJson) {
        logger.debug("Received raw message");
        // Parse and process JSON string
    }
}
```

### 7. Exception Handling

**EventProcessingException.java:**
```java
package com.processor.exception;

public class EventProcessingException extends RuntimeException {
    
    public EventProcessingException(String message) {
        super(message);
    }
    
    public EventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### 8. Main Application

**ArtemisProcessorApplication.java:**
```java
package com.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ArtemisProcessorApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(ArtemisProcessorApplication.class, args);
    }
}
```

---

## Docker Configuration

### Dockerfile
```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Add a non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy the JAR file
COPY target/artemis-processor-*.jar app.jar

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", \
    "app.jar"]

EXPOSE 8080
```

### docker-compose.yml
```yaml
version: '3.8'

services:
  # Apache Artemis Broker (for testing)
  artemis:
    image: quay.io/artemiscloud/activemq-artemis-broker:1.0.25
    container_name: artemis-broker
    environment:
      AMQ_USER: admin
      AMQ_PASSWORD: admin
      AMQ_ROLE: admin
      AMQ_QUEUES: events.queue,events.queue.raw
      AMQ_ADDRESSES: events
    ports:
      - "61616:61616"  # Artemis Core
      - "8161:8161"    # Web Console
    networks:
      - processor-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8161/console"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Event Processor Application
  processor:
    build:
      context: .
      dockerfile: docker/Dockerfile
    container_name: artemis-processor
    depends_on:
      artemis:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_USERNAME: ${DB_USERNAME:-postgres}
      DB_PASSWORD: ${DB_PASSWORD:-postgres}
      JAVA_OPTS: "-Xms256m -Xmx512m"
    ports:
      - "8080:8080"
    networks:
      - processor-network
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 5s
      retries: 3

networks:
  processor-network:
    driver: bridge
```

### .env file example
```env
DB_USERNAME=postgres
DB_PASSWORD=your_secure_password
```

---

## Database Schema (Existing)

### Expected table structure:
```sql
CREATE TABLE IF NOT EXISTS events (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    source VARCHAR(255),
    timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
    payload JSONB NOT NULL,
    metadata JSONB,
    processed_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_events_event_type ON events(event_type);
CREATE INDEX idx_events_timestamp ON events(timestamp);
CREATE INDEX idx_events_processed_at ON events(processed_at);
```

**Note**: ActiveJDBC will work with your existing table structure. Just ensure the model name matches the table name.

---

## Build and Run

### Local Development

**1. Start PostgreSQL (if not running):**
```bash
# Your existing PostgreSQL instance should be accessible
```

**2. Start Artemis locally (optional for testing):**
```bash
docker run -d --name artemis \
  -p 61616:61616 -p 8161:8161 \
  -e AMQ_USER=admin -e AMQ_PASSWORD=admin \
  quay.io/artemiscloud/activemq-artemis-broker:1.0.25
```

**3. Build application:**
```bash
./mvnw clean package
```

**4. Run application:**
```bash
java -jar target/artemis-processor-*.jar
```

### Docker Deployment

**1. Build and run with Docker Compose:**
```bash
docker-compose up -d --build
```

**2. View logs:**
```bash
docker-compose logs -f processor
```

**3. Stop services:**
```bash
docker-compose down
```

---

## Testing

### Send Test Message to Artemis

**Using Artemis Web Console:**
1. Open http://localhost:8161
2. Login with admin/admin
3. Navigate to queues → events.queue
4. Send message with JSON payload:

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

### Using JMS Client Code:
```java
@Test
public void sendTestEvent() throws Exception {
    ConnectionFactory cf = new ActiveMQConnectionFactory(
        "tcp://localhost:61616", "admin", "admin");
    
    try (Connection conn = cf.createConnection();
         Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
        
        MessageProducer producer = session.createProducer(
            session.createQueue("events.queue"));
        
        EventMessage event = new EventMessage(/* ... */);
        String json = objectMapper.writeValueAsString(event);
        
        TextMessage message = session.createTextMessage(json);
        producer.send(message);
    }
}
```

---

## Monitoring and Health Checks

### Actuator Endpoints

**Health:** http://localhost:8080/actuator/health
```json
{
  "status": "UP",
  "components": {
    "jms": {"status": "UP"},
    "db": {"status": "UP"}
  }
}
```

**Metrics:** http://localhost:8080/actuator/metrics
- jvm.memory.used
- jms.connections.active
- events.processed.total
- events.failed.total

---

## Important ActiveJDBC Notes

1. **Instrumentation Required**: The ActiveJDBC Maven plugin MUST run to instrument model classes
2. **Connection Management**: Always use try-finally to close connections:
   ```java
   try {
       Base.open(dataSource);
       // ... operations
       Base.commitTransaction();
   } finally {
       Base.close();
   }
   ```
3. **No Annotations**: ActiveJDBC models don't need field declarations - all is dynamic
4. **Table Naming**: Use `@Table("table_name")` if table name differs from model name
5. **Lazy Loading**: Use `include()` to prevent N+1 queries

---

## Configuration for Existing Database

### Update application.yml with your database info:
```yaml
database:
  url: jdbc:postgresql://your-db-host:5432/your_db_name
  username: your_username
  password: your_password
```

### If using external Artemis:
```yaml
spring:
  artemis:
    broker-url: tcp://your-artemis-host:61616
    user: your_username
    password: your_password
```

---

## Error Handling and Retry Strategy

The implementation includes:
- **Automatic retry** (3 attempts with 5s delay)
- **Transaction rollback** on failure
- **Logging** of all attempts
- **Dead Letter Queue** support (can be configured)

To add DLQ support:
```java
@JmsListener(destination = "events.queue")
public void onMessage(EventMessage event, 
                      @Header(JmsHeaders.REDELIVERED) Boolean redelivered) {
    if (redelivered != null && redelivered) {
        logger.warn("Redelivered message: {}", event.getEventId());
    }
    // Process...
}
```

---

## Performance Tuning

### JMS Concurrency
```yaml
spring:
  jms:
    listener:
      concurrency: 5-20  # Min-Max concurrent consumers
```

### Database Pool
```yaml
database:
  pool:
    maximum-pool-size: 20
    minimum-idle: 5
```

### JVM Options (in Dockerfile or docker-compose)
```bash
JAVA_OPTS: >
  -Xms512m -Xmx1024m
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
```

---

## Security Considerations

1. **Credentials**: Use environment variables, never hardcode
2. **SSL/TLS**: Configure for production Artemis connections
3. **Database**: Use read-only user if processor only inserts
4. **Validation**: Validate all incoming event data

---

## Troubleshooting

### Connection Issues
```bash
# Check Artemis connectivity
docker exec -it artemis-processor curl -f http://artemis:61616

# Check PostgreSQL connectivity
docker exec -it artemis-processor pg_isready -h postgres -p 5432
```

### ActiveJDBC Instrumentation
```bash
# Verify models are instrumented
jar tf target/artemis-processor-*.jar | grep Event.class
# Should show instrumented bytecode
```

### View Application Logs
```bash
docker logs -f artemis-processor
```

---

## Next Steps

1. Implement custom event validators
2. Add metrics export to Prometheus/Grafana
3. Implement Dead Letter Queue handling
4. Add event deduplication logic
5. Create monitoring dashboards
6. Implement circuit breaker pattern
7. Add distributed tracing (OpenTelemetry)
8. Configure log aggregation (ELK stack)

---

**Created for**: Apache Artemis → PostgreSQL Event Processor  
**Date**: 2025-12-15  
**Purpose**: Production-ready event processing with Spring Boot + ActiveJDBC
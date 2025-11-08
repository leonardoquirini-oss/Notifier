# Email Notifier Service

Microservizio Spring Boot che ascolta eventi da Valkey Streams e invia notifiche email basate su template configurabili.

## Indice

- [Panoramica](#panoramica)
- [Architettura](#architettura)
- [Requisiti](#requisiti)
- [Configurazione](#configurazione)
- [Installazione](#installazione)
- [Utilizzo](#utilizzo)
- [Template Email](#template-email)
- [Testing](#testing)
- [Deployment](#deployment)
- [Troubleshooting](#troubleshooting)

## Panoramica

Il servizio Notifier è un processo event-driven che:

1. Si registra su uno o più **Valkey Streams** (Redis-compatible)
2. Ascolta eventi specifici configurati tramite YAML
3. Per ogni evento ricevuto:
   - Carica il template email dal database PostgreSQL
   - Sostituisce le variabili `{{variable}}` con i valori dall'evento
   - Recupera i destinatari dalle liste email associate
   - Invia l'email tramite SMTP
   - Registra l'operazione in `email_send_log` per audit

### Perché Spring Boot (e non Spring Batch)?

- **Event-Driven**: Il servizio deve reagire in tempo reale agli eventi dello stream, non processare batch schedulati
- **Long-Running**: Processo sempre attivo che ascolta continuamente
- **Consumer Groups**: Supporto nativo per Valkey consumer groups per affidabilità e scaling
- **Leggero**: Meno overhead rispetto a Spring Batch per questo use case

## Architettura

### Componenti Principali

```
┌─────────────────────────────────────────────────────────────┐
│                    Valkey Stream                            │
│  purchase-orders: {event_type: "PO_CREATED", id: 1021...}  │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│            ValkeyStreamListener                             │
│  - Consumer Group Registration                              │
│  - Message Reception & ACK                                  │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│            NotificationService                              │
│  - Event → Template Mapping                                 │
│  - Variable Extraction                                      │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│               EmailService                                  │
│  - Template Rendering ({{variables}})                      │
│  - SMTP Sending                                             │
│  - Audit Logging                                            │
└─────────────────────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│          PostgreSQL Database                                │
│  - email_templates                                          │
│  - email_lists / email_list_recipients                     │
│  - email_send_log (audit)                                   │
└─────────────────────────────────────────────────────────────┘
```

### Struttura Progetto

```
Notifier/
├── src/main/java/com/containermgmt/notifier/
│   ├── NotifierApplication.java          # Main class
│   ├── config/
│   │   ├── ValkeyConfig.java             # Connessione Valkey
│   │   ├── ActiveJDBCConfig.java         # Connessione PostgreSQL
│   │   └── NotificationConfigProperties.java  # Mappings YAML
│   ├── listener/
│   │   └── ValkeyStreamListener.java     # Stream consumer
│   ├── service/
│   │   ├── NotificationService.java      # Business logic
│   │   ├── EmailService.java             # Email sending
│   │   └── TemplateRenderer.java         # Variable substitution
│   ├── model/                            # ActiveJDBC models
│   │   ├── EmailTemplate.java
│   │   ├── EmailList.java
│   │   ├── EmailListRecipient.java
│   │   ├── EmailSendLog.java
│   │   └── TemplateEmailList.java
│   └── dto/
│       └── StreamEvent.java              # Event wrapper
├── src/main/resources/
│   ├── application.yml                   # Config generale
│   └── notifications.yml                 # Event mappings
├── Dockerfile                            # Container image
├── docker-compose.yml                    # Testing setup
└── pom.xml                              # Maven dependencies
```

## Requisiti

### Software

- **Java 17** o superiore
- **Maven 3.8+** (per build locale)
- **Docker** (opzionale, per containerizzazione)

### Servizi Esterni

- **PostgreSQL 12+** con schema email_notifications (vedere `008_EMAIL_NOTIFICATIONS.sql`)
- **Valkey/Redis** con supporto Streams
- **SMTP Server** (Gmail, SendGrid, Mailgun, ecc.)

## Configurazione

### 1. Database Configuration (`application.yml`)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/container_mgmt
    username: postgres
    password: postgres
    driver-class-name: org.postgresql.Driver
```

### 2. Valkey Configuration (`application.yml`)

```yaml
valkey:
  host: localhost
  port: 6379
  database: 0
  password: # Lasciare vuoto se nessuna password
```

### 3. SMTP Configuration (`application.yml`)

**Gmail (con App Password):**

```yaml
spring:
  mail:
    host: smtp.gmail.com
    port: 587
    username: your-email@gmail.com
    password: your-app-password  # Generare da Google Account
    properties:
      mail:
        smtp:
          auth: true
          starttls:
            enable: true
```

**Altri provider:**

- **SendGrid**: host: `smtp.sendgrid.net`, port: 587
- **Mailgun**: host: `smtp.mailgun.org`, port: 587
- **AWS SES**: host: `email-smtp.region.amazonaws.com`, port: 587

### 4. Event Mappings (`notifications.yml`)

Configura quali eventi attivano quali template:

```yaml
notifications:
  event-mappings:
    - stream: "purchase-orders"              # Nome stream Valkey
      event-type: "PURCHASE_ORDER_CREATED"   # Valore campo event_type
      template-code: "PO_CREATED"            # Codice template nel DB
      consumer-group: "notifier-group"       # Consumer group
      event-type-field: "event_type"         # Campo che contiene il tipo
      auto-ack: true                         # Auto-acknowledge messaggi

    - stream: "purchase-orders"
      event-type: "PURCHASE_ORDER_APPROVED"
      template-code: "PO_APPROVED"
      consumer-group: "notifier-group"
      event-type-field: "event_type"
      auto-ack: true
```

**Parametri:**

- `stream`: Nome dello stream Valkey
- `event-type`: Valore del campo che identifica l'evento
- `template-code`: Codice del template in `email_templates.template_code`
- `consumer-group`: Consumer group per consumer multipli (scaling)
- `event-type-field`: Nome campo nell'evento (default: "event_type")
- `auto-ack`: Se true, acknowledge automatico dopo processing

## Installazione

### Opzione 1: Build Locale

```bash
# Clone repository
cd /path/to/ContainerManagementSystem/Processors/Notifier

# Build con Maven
mvn clean package

# Run
java -jar target/notifier-1.0.0.jar
```

### Opzione 2: Docker

```bash
# Build image
docker build -t email-notifier:latest .

# Run container
docker run -d \
  --name email-notifier \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host:5432/db \
  -e VALKEY_HOST=valkey-host \
  -e SPRING_MAIL_HOST=smtp.gmail.com \
  -e SPRING_MAIL_USERNAME=your-email@gmail.com \
  -e SPRING_MAIL_PASSWORD=your-password \
  email-notifier:latest
```

### Opzione 3: Docker Compose (Testing)

```bash
# Avvia tutto (notifier, postgres, valkey)
docker-compose up -d

# Visualizza logs
docker-compose logs -f notifier

# Stop
docker-compose down
```

## Utilizzo

### 1. Setup Database

Eseguire lo schema `008_EMAIL_NOTIFICATIONS.sql` sul database PostgreSQL:

```bash
psql -U postgres -d container_mgmt -f 008_EMAIL_NOTIFICATIONS.sql
```

### 2. Creare Template Email

```sql
INSERT INTO email_templates (
    template_code, template_name, subject, body, is_html, is_active
) VALUES (
    'PO_CREATED',
    'Purchase Order Created',
    'New Purchase Order {{id_purchase_order}}',
    'A new purchase order has been created:\n\n' ||
    'ID: {{id_purchase_order}}\n' ||
    'Status: {{id_purchase_order_status}}\n' ||
    'Supplier: {{id_supplier}}\n' ||
    'Description: {{description}}',
    false,
    true
);
```

### 3. Creare Liste Email

```sql
-- Crea lista
INSERT INTO email_lists (list_code, list_name, is_active)
VALUES ('PURCHASING_TEAM', 'Purchasing Team', true);

-- Aggiungi recipients
INSERT INTO email_list_recipients (id_list, email_address, recipient_name, recipient_type, is_active)
VALUES
    (1, 'buyer1@company.com', 'John Buyer', 'TO', true),
    (1, 'manager@company.com', 'Jane Manager', 'CC', true);
```

### 4. Associare Template a Liste

```sql
INSERT INTO template_email_lists (id_template, id_list, is_active)
VALUES (1, 1, true);
```

### 5. Pubblicare Eventi su Valkey

```bash
# Connetti a Valkey/Redis
redis-cli  # o valkey-cli

# Pubblica evento
XADD purchase-orders * \
  event_type "PURCHASE_ORDER_CREATED" \
  id_purchase_order "1021" \
  id_purchase_order_status "DRAFT" \
  id_supplier "1003" \
  description "Office supplies"
```

### 6. Verificare Invio

Controlla i log del servizio:

```bash
docker-compose logs -f notifier
```

Output atteso:

```
Processing stream event: stream=purchase-orders, messageId=1234-0, eventType=PURCHASE_ORDER_CREATED
Email sent successfully: template=PO_CREATED, logId=42, messageId=1234-0
```

Verifica audit nel database:

```sql
SELECT * FROM email_send_log ORDER BY sent_at DESC LIMIT 10;
```

## Template Email

Il servizio utilizza **Handlebars** come template engine, permettendo la creazione di email dinamiche con variabili, iterazioni su array, condizionali e formattazione date.

### Indice Template
- [Variabili Semplici](#variabili-semplici)
- [Oggetti Nested](#oggetti-nested)
- [Iterazione su Array (foreach)](#iterazione-su-array-foreach)
- [Tag Data/Ora](#tag-dataora-now)
- [Condizionali](#condizionali)
- [Esempi Completi](#esempi-completi)

---

### Variabili Semplici

Usa `{{variableName}}` per stampare variabili dai dati dell'evento:

**Evento Valkey:**
```json
{
  "eventType": "PURCHASE_ORDER_CREATED",
  "data": {
    "id_purchase_order": 1021,
    "description": "Office supplies",
    "supplier_name": "ACME Corp",
    "total_amount": "1500.00"
  }
}
```

**Template:**
```handlebars
Subject: Order {{data.id_purchase_order}} - {{data.supplier_name}}

Body:
New Purchase Order Created
--------------------------
ID: {{data.id_purchase_order}}
Description: {{data.description}}
Supplier: {{data.supplier_name}}
Total: €{{data.total_amount}}
```

**Output:**
```
Subject: Order 1021 - ACME Corp

Body:
New Purchase Order Created
--------------------------
ID: 1021
Description: Office supplies
Supplier: ACME Corp
Total: €1500.00
```

---

### Oggetti Nested

Accedi a proprietà nested usando la **dot notation** (`.`):

**Evento:**
```json
{
  "eventType": "ORDER_SHIPPED",
  "data": {
    "order": {
      "id": 1021,
      "date": "2025-11-08"
    },
    "supplier": {
      "name": "ACME Corp",
      "contact": {
        "email": "contact@acme.com",
        "phone": "+39 123456789"
      }
    }
  }
}
```

**Template:**
```handlebars
Order ID: {{data.order.id}}
Order Date: {{data.order.date}}
Supplier: {{data.supplier.name}}
Email: {{data.supplier.contact.email}}
Phone: {{data.supplier.contact.phone}}
```

---

### Iterazione su Array (foreach)

Usa `{{#each arrayName}}...{{/each}}` per iterare su liste/array:

**Evento:**
```json
{
  "eventType": "DAMAGE_REPORT",
  "data": {
    "report_date": "2025-11-08",
    "report_data": [
      {
        "plate": "AG06243",
        "report_notes": "Ammaccatura porta posteriore"
      },
      {
        "plate": "AG11111",
        "report_notes": "Graffio parafango anteriore"
      },
      {
        "plate": "BH22222",
        "report_notes": "Specchietto rotto"
      }
    ]
  }
}
```

**Template:**
```handlebars
Subject: Report Danni - {{data.report_date}}

Body:
Report Danni del {{data.report_date}}
=====================================

{{#each data.report_data}}
Targa: {{plate}}
Problema: {{report_notes}}
---
{{/each}}

Totale veicoli danneggiati: {{data.report_data.length}}
```

**Output:**
```
Subject: Report Danni - 2025-11-08

Body:
Report Danni del 2025-11-08
=====================================

Targa: AG06243
Problema: Ammaccatura porta posteriore
---
Targa: AG11111
Problema: Graffio parafango anteriore
---
Targa: BH22222
Problema: Specchietto rotto
---

Totale veicoli danneggiati: 3
```

**Iterazione con oggetti nested:**

```handlebars
{{#each data.orders}}
Order {{id}}:
  Supplier: {{supplier.name}}
  Items:
  {{#each items}}
    - {{name}}: €{{price}}
  {{/each}}
{{/each}}
```

---

### Tag Data/Ora (`now`)

Usa `{{now "formato"}}` per stampare la data/ora corrente:

**Formati supportati:**

| Sintassi | Esempio Output | Descrizione |
|----------|----------------|-------------|
| `{{now "DD/MM/YYYY"}}` | 08/11/2025 | Solo data |
| `{{now "YYYY-MM-DD"}}` | 2025-11-08 | Data ISO |
| `{{now "DD/MM/YYYY HH:mm"}}` | 08/11/2025 18:30 | Data e ora (24h) |
| `{{now "DD/MM/YYYY HH:mm:ss"}}` | 08/11/2025 18:30:45 | Data, ora e secondi |
| `{{now "HH:mm"}}` | 18:30 | Solo ora |
| `{{now "DD-MM-YY"}}` | 08-11-25 | Data breve |

**Esempi:**

```handlebars
Email generata il {{now "DD/MM/YYYY"}} alle ore {{now "HH:mm"}}

Report del {{now "DD/MM/YYYY"}}
```

**Output:**
```
Email generata il 08/11/2025 alle ore 18:30

Report del 08/11/2025
```

**Note:**
- Il timezone utilizzato è quello del server
- Puoi combinare data e ora nello stesso formato
- I caratteri separatori (`/`, `-`, `:`, spazi) sono preservati

**Pattern supportati:**
- `DD` → giorno (01-31)
- `MM` → mese (01-12)
- `YYYY` → anno 4 cifre (2025)
- `YY` → anno 2 cifre (25)
- `HH` → ora 24h (00-23)
- `mm` → minuti (00-59)
- `ss` → secondi (00-59)

---

### Condizionali

Usa `{{#if}}...{{else}}...{{/if}}` per logica condizionale:

**Esempio 1: Controllo valore esistente**

```handlebars
{{#if data.invoice_number}}
  Fattura N.: {{data.invoice_number}}
{{else}}
  Fattura: Non ancora emessa
{{/if}}
```

**Esempio 2: Combinato con array**

```handlebars
{{#if data.damaged_vehicles}}
  Veicoli danneggiati:
  {{#each data.damaged_vehicles}}
    - {{plate}}: {{damage}}
  {{/each}}
{{else}}
  Nessun veicolo danneggiato oggi.
{{/if}}
```

**Esempio 3: Controllo stato**

```handlebars
Stato ordine: {{data.status}}

{{#if data.status}}
  {{#if data.approved}}
    ✓ L'ordine è stato APPROVATO
  {{else}}
    ⚠ L'ordine è in attesa di approvazione
  {{/if}}
{{/if}}
```

---

### Esempi Completi

#### Esempio 1: Email Creazione Ordine

**Template SQL:**
```sql
INSERT INTO email_templates (
    template_code, template_name, subject, body, is_html, is_active
) VALUES (
    'PO_CREATED',
    'Purchase Order Created',
    'Nuovo Ordine {{data.po_number}} - {{data.supplier_name}}',
    'Nuovo Ordine di Acquisto
=====================

Data: {{now "DD/MM/YYYY HH:mm"}}
Numero Ordine: {{data.po_number}}
Fornitore: {{data.supplier_name}}
Stato: {{data.status_description}}
Importo: €{{data.total_price}}

{{#if data.description}}
Descrizione: {{data.description}}
{{/if}}

{{#if data.user_creation}}
Creato da: {{data.user_creation}}
{{/if}}

---
Questa email è stata generata automaticamente.
',
    false,
    true
);
```

#### Esempio 2: Report Danni con Iterazione

**Template SQL:**
```sql
INSERT INTO email_templates (
    template_code, template_name, subject, body, is_html, is_active
) VALUES (
    'DAMAGE_REPORT',
    'Daily Damage Report',
    'Report Danni - {{now "DD/MM/YYYY"}}',
    'Report Danni Giornaliero
========================

Data Report: {{now "DD/MM/YYYY"}}
Ora Generazione: {{now "HH:mm"}}

{{#if data.report_data}}
VEICOLI DANNEGGIATI
-------------------

{{#each data.report_data}}
Targa: {{plate}}
Problema: {{report_notes}}
{{#if date_reported}}Data Segnalazione: {{date_reported}}{{/if}}
{{#if location}}Località: {{location}}{{/if}}

{{/each}}

Totale: {{data.report_data.length}} veicoli

{{else}}
Nessun danno segnalato oggi.
{{/if}}

---
Per ulteriori dettagli, accedi al sistema di gestione.
',
    false,
    true
);
```

#### Esempio 3: Email HTML Avanzata

**Template SQL:**
```sql
INSERT INTO email_templates (
    template_code, template_name, subject, body, is_html, is_active
) VALUES (
    'PO_APPROVED_HTML',
    'Purchase Order Approved (HTML)',
    'Ordine {{data.po_number}} APPROVATO',
    '<!DOCTYPE html>
<html>
<head>
    <style>
        body { font-family: Arial, sans-serif; }
        .header { background-color: #4CAF50; color: white; padding: 20px; }
        .content { padding: 20px; }
        table { border-collapse: collapse; width: 100%; }
        th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
        th { background-color: #4CAF50; color: white; }
        .footer { color: #666; font-size: 12px; margin-top: 20px; }
    </style>
</head>
<body>
    <div class="header">
        <h1>Ordine Approvato ✓</h1>
    </div>

    <div class="content">
        <p><strong>Data Approvazione:</strong> {{now "DD/MM/YYYY HH:mm"}}</p>
        <p><strong>Numero Ordine:</strong> {{data.po_number}}</p>
        <p><strong>Fornitore:</strong> {{data.supplier_name}}</p>

        {{#if data.items}}
        <h2>Articoli Ordinati</h2>
        <table>
            <tr>
                <th>Codice</th>
                <th>Descrizione</th>
                <th>Quantità</th>
                <th>Prezzo</th>
            </tr>
            {{#each data.items}}
            <tr>
                <td>{{code}}</td>
                <td>{{description}}</td>
                <td>{{quantity}}</td>
                <td>€{{price}}</td>
            </tr>
            {{/each}}
        </table>
        {{/if}}

        <p><strong>Totale:</strong> €{{data.total_price}}</p>

        {{#if data.user_approval}}
        <p>Approvato da: <strong>{{data.user_approval}}</strong></p>
        {{/if}}
    </div>

    <div class="footer">
        <p>Questa email è stata generata automaticamente dal sistema di gestione ordini.</p>
        <p>Data invio: {{now "DD/MM/YYYY HH:mm:ss"}}</p>
    </div>
</body>
</html>',
    true,
    true
);
```

---

### Struttura Dati Eventi

Gli eventi ricevuti da Valkey hanno tipicamente questa struttura:

```json
{
  "eventType": "tipo_evento",
  "timestamp": "2025-11-08T18:30:00Z",
  "status": "success",
  "data": {
    // I tuoi dati specifici qui
  }
}
```

**Campi disponibili per tutti gli eventi:**
- `{{eventType}}` - Tipo di evento
- `{{timestamp}}` - Timestamp evento
- `{{status}}` - Stato elaborazione
- `{{data.*}}` - Dati specifici dell'evento

**Accesso ai dati:**
- Per dati al primo livello: `{{data.campo}}`
- Per oggetti nested: `{{data.oggetto.campo}}`
- Per array: `{{#each data.array}}{{campo}}{{/each}}`

---

### Best Practices Template

1. **Usa nomi variabili descrittivi** nei dati evento
2. **Verifica sempre l'esistenza** con `{{#if}}` per campi opzionali
3. **Testa i template** prima di attivarli in produzione
4. **HTML Email**: Usa sempre `is_html = true` e CSS inline
5. **Iterazioni**: Controlla che l'array esista prima di iterare
6. **Date**: Usa `{{now}}` per timestamp di generazione email
7. **Leggibilità**: Formatta il template per facile manutenzione

### Debugging Template

Per debuggare template problematici, controlla i log dell'applicazione:

```bash
docker-compose logs -f notifier | grep "###DEBUG###"
```

I log mostreranno:
- Variabili disponibili e i loro tipi
- Struttura dati completa
- Output del rendering
- Errori di sintassi Handlebars

## Testing

### Test Manuale con Valkey CLI

```bash
# Aggiungi evento di test
redis-cli XADD purchase-orders * \
  event_type "PURCHASE_ORDER_CREATED" \
  id_purchase_order "9999" \
  description "Test order"

# Verifica consumer group
redis-cli XINFO GROUPS purchase-orders

# Verifica pending messages
redis-cli XPENDING purchase-orders notifier-group
```

### Test con curl (Health Check)

```bash
# Health check
curl http://localhost:8080/actuator/health

# Metrics
curl http://localhost:8080/actuator/metrics
```

### Logs

```bash
# Docker Compose
docker-compose logs -f notifier

# Docker standalone
docker logs -f email-notifier

# File logs (se configurato)
tail -f logs/notifier.log
```

## Deployment

### Variabili d'Ambiente

Override delle configurazioni tramite env vars:

```bash
# Database
SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/db
SPRING_DATASOURCE_USERNAME=notifier_user
SPRING_DATASOURCE_PASSWORD=secure_password

# Valkey
VALKEY_HOST=prod-valkey
VALKEY_PORT=6379
VALKEY_PASSWORD=valkey_password

# SMTP
SPRING_MAIL_HOST=smtp.sendgrid.net
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=apikey
SPRING_MAIL_PASSWORD=SG.xxxxx

# Logging
LOGGING_LEVEL_COM_CONTAINERMGMT_NOTIFIER=INFO
```

### Kubernetes Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: email-notifier
spec:
  replicas: 2  # Scaling con consumer groups
  selector:
    matchLabels:
      app: email-notifier
  template:
    metadata:
      labels:
        app: email-notifier
    spec:
      containers:
      - name: notifier
        image: email-notifier:latest
        env:
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: url
        - name: VALKEY_HOST
          value: "valkey-service"
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 30
```

### Scaling Orizzontale

Il servizio supporta scaling con **Valkey Consumer Groups**:

1. Ogni istanza usa lo stesso `consumer-group`
2. Valkey distribuisce i messaggi tra i consumer
3. Garantisce che ogni messaggio venga processato una sola volta

```bash
# Scale a 3 replicas
docker-compose up -d --scale notifier=3

# Kubernetes
kubectl scale deployment email-notifier --replicas=3
```

## Troubleshooting

### Problema: Email non inviate

**Check 1: Template esiste e è attivo**
```sql
SELECT * FROM email_templates WHERE template_code = 'PO_CREATED';
-- Verifica: is_active = true, is_deleted = false
```

**Check 2: Liste associate**
```sql
SELECT * FROM template_email_lists WHERE id_template = 1 AND is_active = true;
```

**Check 3: Recipients esistono**
```sql
SELECT * FROM email_list_recipients WHERE id_list = 1 AND is_active = true;
```

**Check 4: Logs errori**
```sql
SELECT * FROM email_send_log WHERE send_status = 'FAILED' ORDER BY sent_at DESC;
```

### Problema: Consumer group errors

```bash
# Elimina consumer group
redis-cli XGROUP DESTROY purchase-orders notifier-group

# Riavvia servizio per ricreare
docker-compose restart notifier
```

### Problema: Pending messages non processati

```bash
# Verifica pending
redis-cli XPENDING purchase-orders notifier-group

# Claim pending messages (force reprocess)
redis-cli XCLAIM purchase-orders notifier-group consumer-name 3600000 <message-id>
```

### Problema: SMTP authentication failed

- **Gmail**: Assicurati di usare **App Password**, non la password account
- **2FA**: Abilita 2FA e genera App Password da Google Account
- **Firewall**: Verifica che la porta 587 sia aperta
- **TLS**: Verifica `starttls.enable: true`

### Debug Logs

Aumenta verbosità:

```yaml
logging:
  level:
    com.containermgmt.notifier: TRACE
    org.springframework.data.redis: DEBUG
    org.javalite.activejdbc: DEBUG
```

## Metriche e Monitoring

### Health Check

```bash
curl http://localhost:8080/actuator/health
```

Response:
```json
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

### Custom Metrics (Future Enhancement)

Possibili metriche da aggiungere con Micrometer:

- `emails.sent.total` - Counter email inviate
- `emails.failed.total` - Counter email fallite
- `email.send.duration` - Timer durata invio
- `stream.messages.processed` - Counter eventi processati

## Best Practices

1. **Template Versioning**: Mantieni cronologia modifiche template
2. **Retry Logic**: Configurare retry automatico per email fallite
3. **Rate Limiting**: Limita invii per prevenire spam
4. **Dead Letter Queue**: Stream separato per eventi falliti
5. **Monitoring**: Alert su failure rate > soglia
6. **Backup**: Backup regolari del database template
7. **Testing**: Test template prima di attivare in produzione

## Licenza

Proprietario: Container Management System

---

**Autore**: Claude Code
**Versione**: 1.0.0
**Data**: 2025

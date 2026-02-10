# TFP Event Ingester - Refactor Roadmap

Code health assessment del 2026-02-09. Punteggio complessivo: **7.0 / 10**.

---

## Punteggi per categoria

| Categoria | Punteggio | Note |
|-----------|-----------|------|
| Code Quality | 7.5/10 | Architettura pulita, Strategy pattern ben implementato |
| Test Coverage | 0/10 | Nessun test, nessuna directory `src/test/` |
| Documentazione | 8.0/10 | CLAUDE.md ben mantenuto |
| Manutenibilita' | 7.0/10 | Duplicazione strutturale nei processor |
| Complessita' | 8.0/10 | Metodi medi ~15 righe, CC max ~7 |
| Sicurezza | 5.0/10 | Credenziali e API key hardcoded |

---

## Issues trovate

### R1. Externalize secrets in application.yml

**Priorita': CRITICA** | Effort: ~1h

`application.yml` contiene credenziali e API key hardcoded. Le env var definite in `docker-compose.yml` (`DB_HOST`, `DB_PORT`, ecc.) non sono referenziate nel YAML tramite placeholder `${...}`.

File coinvolti:
- `src/main/resources/application.yml` righe 6-10 (datasource), 14-17 (valkey), 32-33 (api-key)

Azione: sostituire valori statici con `${DB_HOST:postgres-service}`, `${DB_PASSWORD:berlink}`, `${BERLINK_API_KEY:}`, ecc.

---

### R2. Rimuovere JDWP debug dal Dockerfile di produzione

**Priorita': CRITICA** | Effort: ~30min

`Dockerfile:35` abilita incondizionatamente l'agent JDWP su porta 5016, consentendo debug remoto a chiunque abbia accesso di rete.

File coinvolti:
- `Dockerfile` righe 34-35

Azione: rimuovere `JAVA_TOOL_OPTIONS` dal Dockerfile e passarlo solo via docker-compose in ambiente dev.

---

### R3. Template Method per eliminare duplicazione in process()

**Priorita': ALTA** | Effort: ~4h

I due processor (`UnitEventStreamProcessor`, `UnitPositionStreamProcessor`) condividono ~40 righe identiche su ~65 totali nel metodo `process()`:

| Step | Condiviso? |
|------|-----------|
| 1. Estrai messageId, eventType, payloadJson | SI |
| 2. Valida messageId | SI |
| 3. Resend / dedup check | SI (diversa classe Model) |
| 4. Parsa payload JSON | SI |
| 5. Crea model e mappa campi | NO (specifico per processor) |
| 6. BERLink lookup + enrich | SI |
| 7. Save + log | SI |

Azione: spostare step 1-4, 6-7 in `AbstractStreamProcessor` come template method. Ogni processor implementa solo `mapToModel()` e fornisce `existsByMessageId()` / `deleteByMessageId()` tramite metodi astratti.

File coinvolti:
- `src/main/java/.../stream/AbstractStreamProcessor.java`
- `src/main/java/.../stream/UnitEventStreamProcessor.java`
- `src/main/java/.../stream/UnitPositionStreamProcessor.java`

---

### R4. Dead-letter / retry per messaggi falliti

**Priorita': ALTA** | Effort: ~4-6h

`StreamListenerOrchestrator:125-133` fa acknowledge anche quando `processor.process()` lancia eccezione. I messaggi falliti vengono persi permanentemente.

File coinvolti:
- `src/main/java/.../stream/StreamListenerOrchestrator.java` righe 125-133

Azione: implementare una delle seguenti strategie:
- Non fare acknowledge su errore (lasciare che Redis PEL gestisca i retry)

---

### R5. Aggiungere test suite

**Priorita': ALTA** | Effort: ~16-24h (in 3 fasi)

Zero test. `spring-boot-starter-test` gia' presente nel pom.xml.

**Fase 1 - Logica pura (nessun mock):**
- `AbstractStreamProcessor`: `getString`, `parseTimestamp`, `parseBigDecimal`, `parseResendFlag`, `getBoolean`, `getInteger`
- `BerlinkLookupService`: `formatContainerNumberForSearch()`, `formatGbtuFallback()`
- `BerlinkLookupService.LookupResult`: factory methods, `hasData()`
- `StreamListenerOrchestrator`: `cleanJsonValues()` (rendere package-private per testabilita')
- ~30-35 test methods

**Fase 2 - Service layer (Mockito):**
- `BerlinkLookupService.lookupUnit()`: mock RestTemplate, testare branching container/non-container/fallback
- `BerlinkApiConfig.berlinkRestTemplate()`: verificare timeout e interceptor
- ~15-20 test methods

**Fase 3 - Processor logic (Mockito + mockStatic per ActiveJDBC):**
- `UnitEventStreamProcessor.process()`: mock model statics + BerlinkLookupService
- `UnitPositionStreamProcessor.process()`: idem, + test array vuoto/null di unitPositions
- ~15-20 test methods

Dipendenza aggiuntiva necessaria: `mockito-inline` o Mockito 5+ per `mockStatic`.

---

### R6. Externalizzare stream keys, consumer group e timeout

**Priorita': MEDIA** | Effort: ~2h

Valori hardcoded:

| Valore | File | Riga |
|--------|------|------|
| `"tfp-unit-events-stream"` | `UnitEventStreamProcessor.java` | 26 |
| `"tfp-unit-positions-stream"` | `UnitPositionStreamProcessor.java` | 27 |
| `"tfp-event-ingester-group"` | entrambi i processor | 31 |
| `5000` (connect timeout) | `BerlinkApiConfig.java` | 38 |
| `10000` (read timeout) | `BerlinkApiConfig.java` | 39 |
| `Duration.ofSeconds(1)` (poll timeout) | `StreamListenerOrchestrator.java` | 82 |

Azione: creare proprieta' Spring in `application.yml` e iniettarle via `@Value` o `@ConfigurationProperties`.

---

### R7. Fix URL injection in BerlinkLookupService

**Priorita': MEDIA** | Effort: ~30min

`BerlinkLookupService:168` concatena `plateNumber` direttamente nell'URL senza encoding:
```java
String url = config.getBaseUrl() + "/api/vehicles/by-plate/" + plateNumber;
```

Un valore con `/` o `?` potrebbe manipolare la request.

Azione: usare `UriComponentsBuilder` (gia' usato altrove nello stesso file) anche qui.

---

### R8. Connection pooling per ActiveJDBC

**Priorita': MEDIA** | Effort: ~4h

`StreamListenerOrchestrator:111-116` apre e chiude una connessione JDBC per ogni messaggio. Sotto carico questo causa connection churn.

Azione: integrare HikariCP (gia' incluso con Spring Boot) o usare `ThreadLocal<Connection>` con riuso.

---

### R9. Ridurre actuator exposure

**Priorita': MEDIA** | Effort: ~15min

`application.yml:26` espone `show-details: always` sull'endpoint health, rivelando stato interno di DB, Redis, disk space.

Azione: cambiare a `show-details: when-authorized` o `never` in produzione.

---

### R10. Rimuovere codice morto in createConsumerGroup

**Priorita': BASSA** | Effort: ~15min

`StreamListenerOrchestrator:64-76` ha un try-catch esterno irraggiungibile perche' quello interno cattura gia' tutte le eccezioni.

Azione: rimuovere il try-catch esterno, lasciare solo quello interno.

---

### R11. Usare Lombok @Getter in ActiveJDBCConfig

**Priorita': BASSA** | Effort: ~15min

`ActiveJDBCConfig:46-60` ha 4 getter manuali. La classe usa gia' `@Slf4j` di Lombok.

Azione: aggiungere `@Getter` e rimuovere i getter manuali.

---

### R12. Upgrade Spring Boot

**Priorita': BASSA** | Effort: ~2-4h (include testing)

Spring Boot 3.1.5 non riceve piu' security patch (EOL). Versione corrente stabile: 3.3.x / 3.4.x.

File coinvolti:
- `pom.xml:11`

---

## Piano di esecuzione suggerito

### Settimana 1 - Quick wins
- [x] R1 - Externalize secrets
- [x] R2 - Rimuovere JDWP
- [x] R9 - Actuator exposure _(completato 2026-02-09)_
- [x] R10 - Codice morto createConsumerGroup _(completato 2026-02-09)_
- [x] R11 - Lombok @Getter _(completato 2026-02-09)_

### Settimana 2 - Refactoring architetturale
- [x] R3 - Template Method _(completato 2026-02-09)_
- [x] R7 - Fix URL injection _(completato 2026-02-09)_
- [x] R6 - Externalizzare configurazione _(completato 2026-02-09)_

### Settimana 3-4 - Test e affidabilita'
- [ ] R5 Fase 1 - Test logica pura
- [ ] R5 Fase 2 - Test service layer
- [ ] R5 Fase 3 - Test processor logic

### Mese 2
- [x] R4 - No acknowledge su errore (PEL) _(completato 2026-02-09)_
- [x] R8 - Connection pooling HikariCP _(completato 2026-02-10)_
- [x] R12 - Upgrade Spring Boot 3.1.5 → 3.4.3 _(completato 2026-02-09)_

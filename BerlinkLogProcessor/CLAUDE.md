## Stack Tecnologico

| Layer | Tecnologia | Versione |
|-------|------------|----------|
| Backend | Java + Spring Boot | 17 / 3.1.5 |
| ORM | ActiveJDBC | 3.0 |
| Cache | Valkey | 9.0 |
| Build | Maven  | - |


## Convenzioni Naming

| Contesto | Convenzione | Esempio |
|----------|-------------|---------|
| Java Classes | PascalCase | `TimesheetController`, `DeadlineService` |
| Java Methods | camelCase | `getTimesheetsByUsername()` |
| Database | snake_case | `id_timesheet`, `flag_approval_required` |
| REST Endpoints | `/api/{resource}` | `/api/timesheets`, `/api/deadlines` |
| Svelte Components | PascalCase.svelte | `DeadlineTable.svelte`, `NewTimesheetModal.svelte` |
| Svelte Stores | camelCase.js | `permissions.js`, `chatbot.js` |
| Routes | kebab-case | `hr-requests/`, `scadenziario/` |

## Pattern Architetturali

### Backend (MVC)
**ActiveJDBC**: ORM senza annotazioni, i model mappano automaticamente le tabelle DB.
```java
// Model: Deadline.java → tabella: flt_deadlines
Deadline deadline = Deadline.findById(id);
deadline.set("field_name", value);
deadline.saveIt();
```

## Comandi Utili

```bash
# Avvio completo
docker-compose up -d

# Solo backend
docker-compose up -d backend

# Rebuild frontend
docker-compose build frontend && docker-compose up -d frontend

# Logs
docker-compose logs -f backend
docker-compose logs -f frontend

# Task runner (Taskfile.yml)
task build-be    # Build backend
task build-fe    # Build frontend
task stop-be     # Stop backend
```

## Porte Servizi

| Servizio | Porta |
|----------|-------|
| Backend | 8080 |
| Valkey | 6379 |

## Documentazione

Documentazione dettagliata in `prompt/`:
- `README.md` - Quick start e setup
- `API_DOCUMENTATION.md` - Endpoints API

## Note Importanti

3. **Eventi**: Sistema event stream con Valkey per notifiche real-time


---

## For Claude Code

### When Creating New Features
1. Seguire i pattern esistenti in controller/service simili
2. Usare DTO per separare API layer da database entities
3. Aggiungere `@Valid` per validazione request body
4. Usare `@PreAuthorize` per controllo accessi
5. Wrappare response in `ApiResponse<T>`
6. Frontend: usare Svelte stores per stato condiviso
7. Frontend: seguire pattern API client in `/lib/api/modules/`
8. Cerca di mantenere le funzioni piccole: <= 100 righe di codice. Se una funzione fa troppe cose, spezzala in funzioni helper piu' piccole
9. Applica principio DRY e NON DUPLICARE CODICE. Se una logica esiste in due posti, rifattorizzala in una funzione comune (o chiarisci perche' servono due implementazioni differenti se esiste un motivo valido)
10. Implementa un mini-agile cycle: proponi -> ottieni feedback -> implementa -> review
11. Verifica sempre il file prompt/API.md e le API esposte per capire se si puo' riusare qualche metodo o se e' necessario implementare nuove API

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
- Dopo milestone di progetto

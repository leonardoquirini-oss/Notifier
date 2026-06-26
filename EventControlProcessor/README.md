# Event Control Processor

Scheduled **rule engine** that detects anomalous situations on unit events and notifies them **once**.

It does **not** consume streams: it queries `evt_unit_events` (populated by TFPEventIngester) on a
cron schedule, raises a *situation* when a rule matches, persists it in `evt_event_checks` with a unique
key, and dispatches a notification only the first time the situation is seen.

## Architecture

```
ControlEngine (@Scheduled cron)
  └─ for each ControlRule (extension point)
       └─ rule.evaluate() -> List<Situation>
            └─ SituationRepository.insertIfAbsent()  (UNIQUE rule_code+dedup_key => notify once)
                 └─ NotificationDispatcher.dispatch() -> BERLink / WhatsApp / Email
```

### Adding a new check

Implement `ControlRule` as a Spring `@Component`. Rules are auto-discovered (`List<ControlRule>`).
Checks are **not** limited to event pairs — any condition a rule can evaluate is valid.

## First rule: `MISSING_END_LOAD`

Every `BEGIN_LOAD` must have an `END_LOAD` on the **same calendar day**, same unit, within a GPS
**tolerance (meters)**. After a **grace period (hours)** with no match → situation → notification.

Config (`control.rules.missing-end-load`): `enabled`, `grace-hours`, `lookback-hours`,
`tolerance-meters`, `channels` (`berlink`/`whatsapp`/`email`), `group-code`, `notification-type`,
`email-recipients`.

## State

`evt_event_checks` (auto-created at startup via `SchemaInitializer`): `rule_code`, `dedup_key`
(UNIQUE together), `unit_number`, `status`, `detected_at`, `notified_at`, `details` JSONB.

## Run

`task ecp && task up` (see `Taskfile.yml`). Health: `GET /api/health/live`.

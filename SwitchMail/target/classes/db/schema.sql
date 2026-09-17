-- SwitchMail - schema SQLite. Eseguito a ogni avvio da SchemaMigrations (idempotente).
--
-- Convenzioni:
--   * Timestamp: TEXT ISO-8601 UTC con millisecondi ("2026-09-17T14:03:11.482Z"), ordinabili
--     lessicograficamente, prodotti in Java da TimestampUtil sopra il bean Clock.
--     NESSUN default di colonna con strftime()/datetime(now): vedi IMPLEMENTATION_PLAN.md 2 e 8.
--   * Booleani: INTEGER 0/1, letti sempre con rs.getBoolean() (portabile su Postgres boolean).
--   * Ogni costrutto non portabile porta sulla riga sopra un commento "-- PG:" con lequivalente.

-- ---------------------------------------------------------------------------
-- mail_account - una casella IMAP sorvegliata
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_account (
  -- PG: id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
  id                      INTEGER PRIMARY KEY AUTOINCREMENT,
  name                    TEXT    NOT NULL UNIQUE,
  host                    TEXT    NOT NULL,
  port                    INTEGER NOT NULL DEFAULT 993,
  use_ssl                 INTEGER NOT NULL DEFAULT 1,
  start_tls               INTEGER NOT NULL DEFAULT 0,
  trust_all_certs         INTEGER NOT NULL DEFAULT 0,
  username                TEXT    NOT NULL,
  -- PG: password_encrypted BYTEA
  -- [0x01 versione][IV 12 byte][ciphertext || tag GCM 16 byte], AES-GCM, chiave da SWITCHMAIL_CREDS_KEY.
  password_encrypted      BLOB,
  folder                  TEXT    NOT NULL DEFAULT 'INBOX',
  access_mode             TEXT    NOT NULL DEFAULT 'READ_ONLY',
  post_action             TEXT    NOT NULL DEFAULT 'NONE',
  post_action_folder      TEXT,
  poll_cron               TEXT    NOT NULL DEFAULT '0 */2 * * * *',
  max_messages_per_poll   INTEGER NOT NULL DEFAULT 50,
  initial_lookback_days   INTEGER NOT NULL DEFAULT 1,
  connect_timeout_ms      INTEGER NOT NULL DEFAULT 10000,
  read_timeout_ms         INTEGER NOT NULL DEFAULT 30000,
  enabled                 INTEGER NOT NULL DEFAULT 1,
  last_poll_at            TEXT,
  last_poll_status        TEXT,
  last_poll_error         TEXT,
  last_poll_fetched       INTEGER,
  consecutive_failures    INTEGER NOT NULL DEFAULT 0,
  created_at              TEXT    NOT NULL,
  updated_at              TEXT    NOT NULL,
  CHECK (access_mode IN ('READ_ONLY','OWNED')),
  CHECK (post_action IN ('NONE','MARK_SEEN','MOVE','DELETE')),
  -- La riga portante dello schema: un account READ_ONLY non puo MAI mutare la casella del
  -- dipendente. Invariante di dato, non solo di codice: nessun refactor futuro, nessun chiamante
  -- API e nessuna riga editata a mano puo romperla.
  CHECK (access_mode = 'OWNED' OR post_action = 'NONE'),
  CHECK (post_action <> 'MOVE' OR (post_action_folder IS NOT NULL AND post_action_folder <> ''))
);

-- ---------------------------------------------------------------------------
-- mail_folder_state - high-water mark per (account, folder)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_folder_state (
  account_id    INTEGER NOT NULL REFERENCES mail_account(id) ON DELETE CASCADE,
  folder        TEXT    NOT NULL,
  uid_validity  INTEGER NOT NULL,
  last_uid      INTEGER NOT NULL DEFAULT 0,
  updated_at    TEXT    NOT NULL,
  PRIMARY KEY (account_id, folder)
);

-- ---------------------------------------------------------------------------
-- mail_rule - classificazione mail -> sub-processor
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_rule (
  -- PG: id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
  id                          INTEGER PRIMARY KEY AUTOINCREMENT,
  name                        TEXT    NOT NULL UNIQUE,
  description                 TEXT,
  -- NULL = la regola vale per tutti gli account
  account_id                  INTEGER REFERENCES mail_account(id) ON DELETE CASCADE,
  enabled                     INTEGER NOT NULL DEFAULT 1,
  -- ASC: numero piu basso = valutata prima
  priority                    INTEGER NOT NULL DEFAULT 100,
  stop_on_match               INTEGER NOT NULL DEFAULT 1,
  sender_pattern              TEXT,
  sender_match                TEXT    NOT NULL DEFAULT 'CONTAINS',
  sender_case_sensitive       INTEGER NOT NULL DEFAULT 0,
  subject_pattern             TEXT,
  subject_match               TEXT    NOT NULL DEFAULT 'CONTAINS',
  subject_case_sensitive      INTEGER NOT NULL DEFAULT 0,
  attachment_pattern          TEXT,
  attachment_match            TEXT    NOT NULL DEFAULT 'CONTAINS',
  attachment_case_sensitive   INTEGER NOT NULL DEFAULT 0,
  require_attachment          INTEGER NOT NULL DEFAULT 0,
  processor_id                TEXT    NOT NULL,
  params_json                 TEXT    NOT NULL DEFAULT '{}',
  max_attempts                INTEGER NOT NULL DEFAULT 3,
  created_at                  TEXT    NOT NULL,
  updated_at                  TEXT    NOT NULL,
  CHECK (sender_match     IN ('EQUALS','CONTAINS','REGEX')),
  CHECK (subject_match    IN ('EQUALS','CONTAINS','REGEX')),
  CHECK (attachment_match IN ('EQUALS','CONTAINS','REGEX')),
  CHECK (max_attempts >= 1),
  -- Nessun catch-all accidentale: almeno un campo deve vincolare qualcosa.
  CHECK ( (sender_pattern     IS NOT NULL AND sender_pattern     <> '')
       OR (subject_pattern    IS NOT NULL AND subject_pattern    <> '')
       OR (attachment_pattern IS NOT NULL AND attachment_pattern <> '') )
);

CREATE INDEX IF NOT EXISTS ix_rule_eval      ON mail_rule(enabled, priority, id);
CREATE INDEX IF NOT EXISTS ix_rule_account   ON mail_rule(account_id);
CREATE INDEX IF NOT EXISTS ix_rule_processor ON mail_rule(processor_id);

-- ---------------------------------------------------------------------------
-- mail_processing_log - UNA riga per mail. E il registro di dedup.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_processing_log (
  -- PG: id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
  id                  INTEGER PRIMARY KEY AUTOINCREMENT,
  -- identita di dedup
  account_id          INTEGER NOT NULL REFERENCES mail_account(id) ON DELETE CASCADE,
  folder              TEXT    NOT NULL,
  uid_validity        INTEGER NOT NULL,
  uid                 INTEGER NOT NULL,
  internet_message_id TEXT,
  -- snapshot della mail: sopravvive alla cancellazione dalla casella
  mail_from           TEXT,
  mail_from_name      TEXT,
  mail_subject        TEXT,
  mail_sent_at        TEXT,
  mail_received_at    TEXT,
  attachment_names    TEXT,
  mail_size_bytes     INTEGER,
  -- snapshot del binding regola/processore
  rule_id             INTEGER REFERENCES mail_rule(id) ON DELETE SET NULL,
  rule_name           TEXT,
  matched_rule_ids    TEXT,
  processor_id        TEXT,
  -- esito
  status              TEXT    NOT NULL,
  attempt             INTEGER NOT NULL DEFAULT 0,
  max_attempts        INTEGER NOT NULL DEFAULT 3,
  message             TEXT,
  extracted_json      TEXT,
  action_ref          TEXT,
  warnings            TEXT,
  error_type          TEXT,
  error_message       TEXT,
  error_stack         TEXT,
  duration_ms         INTEGER,
  -- tempi
  claimed_at          TEXT    NOT NULL,
  processed_at        TEXT,
  next_retry_at       TEXT,
  resolved_at         TEXT,
  resolved_note       TEXT,
  created_at          TEXT    NOT NULL,
  CHECK (status IN ('IN_PROGRESS','SUCCESS','SKIPPED','NO_RULE','RETRY_SCHEDULED','DEAD_LETTER','RESOLVED'))
);

-- *** Il vincolo di dedup. ***
-- Lo UID e unico solo dentro (casella, folder, UIDVALIDITY): servono tutte e quattro le colonne.
-- Un INSERT ... ON CONFLICT DO NOTHING RETURNING id contro questo indice E il claim atomico:
-- 1 riga = la mail e nostra (e lid arriva con la stessa statement), 0 righe = qualcuno ce lha gia.
-- Niente race read-then-write, niente lock applicativo. Sintassi identica su Postgres.
CREATE UNIQUE INDEX IF NOT EXISTS ux_mpl_dedup
  ON mail_processing_log(account_id, folder, uid_validity, uid);

CREATE INDEX IF NOT EXISTS ix_mpl_status_created  ON mail_processing_log(status, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_mpl_account_created ON mail_processing_log(account_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_mpl_rule            ON mail_processing_log(rule_id);
CREATE INDEX IF NOT EXISTS ix_mpl_msgid           ON mail_processing_log(internet_message_id);
CREATE INDEX IF NOT EXISTS ix_mpl_retry_due       ON mail_processing_log(next_retry_at)
  WHERE status = 'RETRY_SCHEDULED';
CREATE INDEX IF NOT EXISTS ix_mpl_in_progress     ON mail_processing_log(claimed_at)
  WHERE status = 'IN_PROGRESS';

-- ---------------------------------------------------------------------------
-- mail_processing_attempt - audit append-only, un record per tentativo/regola
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_processing_attempt (
  -- PG: id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  log_id        INTEGER NOT NULL REFERENCES mail_processing_log(id) ON DELETE CASCADE,
  attempt       INTEGER NOT NULL,
  rule_id       INTEGER,
  processor_id  TEXT,
  status        TEXT    NOT NULL,
  message       TEXT,
  error_type    TEXT,
  error_message TEXT,
  duration_ms   INTEGER,
  triggered_by  TEXT    NOT NULL,
  started_at    TEXT    NOT NULL,
  finished_at   TEXT,
  CHECK (status IN ('SUCCESS','SKIPPED','FAILED_RETRYABLE','FAILED_TERMINAL')),
  CHECK (triggered_by IN ('POLL','RETRY_AUTO','RETRY_MANUAL'))
);

CREATE INDEX IF NOT EXISTS ix_mpa_log ON mail_processing_attempt(log_id, attempt, id);

-- ---------------------------------------------------------------------------
-- mail_raw - MIME grezzo gzippato (scaricabile come .eml dalla UI)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mail_raw (
  log_id        INTEGER PRIMARY KEY REFERENCES mail_processing_log(id) ON DELETE CASCADE,
  -- PG: content_gzip BYTEA
  content_gzip  BLOB    NOT NULL,
  original_size INTEGER NOT NULL,
  stored_at     TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_raw_stored_at ON mail_raw(stored_at);

-- ---------------------------------------------------------------------------
-- schema_meta - versione dello schema per le migration additive
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS schema_meta (
  key        TEXT PRIMARY KEY,
  value      TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

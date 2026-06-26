-- State table for the Event Control Processor.
-- One row per detected situation. The UNIQUE (rule_code, dedup_key) constraint is what
-- guarantees a situation is notified exactly once.

CREATE SEQUENCE IF NOT EXISTS s_evt_event_checks START 1000;

CREATE TABLE evt_event_checks
(
  id_event_check integer NOT NULL,
  rule_code varchar(64) NOT NULL,
  dedup_key varchar(255) NOT NULL,
  unit_number varchar(100),
  status varchar(20) DEFAULT 'OPEN' NOT NULL,
  detected_at timestamp DEFAULT NOW() NOT NULL,
  notified_at timestamp,
  details jsonb
);

ALTER TABLE evt_event_checks ADD CONSTRAINT pk_event_check PRIMARY KEY (id_event_check);

/* CREATE INDEX */

CREATE INDEX idx_evt_event_checks_rule_status
  ON evt_event_checks (rule_code, status);


/* CREATE INDEX */

CREATE UNIQUE INDEX uq_evt_event_checks
  ON evt_event_checks (rule_code, dedup_key);

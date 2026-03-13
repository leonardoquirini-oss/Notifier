CREATE TABLE evt_event_attachments (
  id_event_attachment integer NOT NULL,
  id_unit_event integer NOT NULL,
  event_time timestamp NOT NULL,
  path varchar(250),
  filename varchar(250),
  id_document integer
);
ALTER TABLE evt_event_attachments ADD CONSTRAINT pk_event_attachment PRIMARY KEY (id_event_attachment);
ALTER TABLE evt_event_attachments ADD CONSTRAINT fk_attach_unit_event FOREIGN KEY (id_unit_event,event_time) REFERENCES evt_unit_events (id_unit_event,event_time);
CREATE INDEX idx_unit_evt_attachment ON evt_event_attachments (id_unit_event,event_time);
CREATE SEQUENCE IF NOT EXISTS s_evt_event_attachments START 1000;

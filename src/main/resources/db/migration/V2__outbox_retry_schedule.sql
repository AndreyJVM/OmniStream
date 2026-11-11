ALTER TABLE ingestion_outbox
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN claim_token UUID;
CREATE INDEX idx_outbox_available ON ingestion_outbox(available_at, event_id);

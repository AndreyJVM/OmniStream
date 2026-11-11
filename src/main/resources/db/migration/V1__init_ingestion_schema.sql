CREATE TABLE ingestion_events (
    id UUID PRIMARY KEY,
    source_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    raw_payload TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}',
    status VARCHAR(50) NOT NULL CHECK (status IN ('RECEIVED','PROCESSING','COMPLETED','FAILED')),
    category VARCHAR(50),
    risk_level VARCHAR(20),
    sentiment_score NUMERIC(3,2) CHECK (sentiment_score BETWEEN -1 AND 1),
    extracted_data JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ingestion_events_status ON ingestion_events(status);
CREATE INDEX idx_ingestion_events_source ON ingestion_events(source_id);
CREATE INDEX idx_ingestion_events_recent ON ingestion_events(created_at DESC, id);
CREATE TABLE ingestion_outbox (
    event_id UUID PRIMARY KEY REFERENCES ingestion_events(id),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_outbox_created ON ingestion_outbox(created_at, event_id);

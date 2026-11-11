ALTER TABLE ingestion_events ADD COLUMN demo_owner VARCHAR(64);
CREATE INDEX idx_events_demo_owner ON ingestion_events(demo_owner, created_at DESC);
CREATE TABLE demo_usage (
    day DATE NOT NULL,
    scope VARCHAR(100) NOT NULL,
    requests INTEGER NOT NULL DEFAULT 0,
    reserved_usd NUMERIC(14,6) NOT NULL DEFAULT 0,
    PRIMARY KEY(day, scope)
);

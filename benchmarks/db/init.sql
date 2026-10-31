-- Schema and data for ArchSim's accuracy benchmarks: 100,000 rows read and updated by key, and
-- an events table the Kafka consumer writes to. pg_stat_statements (loaded by compose.yaml) gives
-- calibration each statement's time inside PostgreSQL.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE TABLE items (
  id integer PRIMARY KEY,
  value bigint NOT NULL DEFAULT 0,
  payload text NOT NULL
);
INSERT INTO items (id, value, payload)
SELECT g, 0, repeat('x', 100) FROM generate_series(1, 100000) AS g;

CREATE TABLE events (
  id bigserial PRIMARY KEY,
  item_id integer NOT NULL,
  produced_at timestamptz NOT NULL,
  stored_at timestamptz NOT NULL DEFAULT now()
);
ANALYZE items;

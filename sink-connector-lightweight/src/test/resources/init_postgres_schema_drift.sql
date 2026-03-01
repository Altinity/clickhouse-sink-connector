-- PostgreSQL initialisation for PostgresSchemaDriftIT
-- Creates the initial table used by the schema-drift integration test.
-- The test will ALTER TABLE ADD COLUMN after CDC is running.

CREATE TABLE IF NOT EXISTS public.schema_drift_test
(
    id   SERIAL PRIMARY KEY,
    name TEXT
);

-- Seed one row so the initial snapshot has data to replicate
INSERT INTO public.schema_drift_test (id, name) VALUES (1, 'initial_row');

-- PostgreSQL initialisation for PostgresSchemaDriftIT
-- Creates the initial tables used by the schema-drift integration tests.
-- Each test method uses its own table to avoid column pollution and
-- replication-slot conflicts between test runs.

-- Test 1: single new column
CREATE TABLE IF NOT EXISTS public.schema_drift_single
(
    id   SERIAL PRIMARY KEY,
    name TEXT
);
INSERT INTO public.schema_drift_single (id, name) VALUES (1, 'initial_row');

-- Test 2: multiple new columns
CREATE TABLE IF NOT EXISTS public.schema_drift_multi
(
    id   SERIAL PRIMARY KEY,
    name TEXT
);
INSERT INTO public.schema_drift_multi (id, name) VALUES (1, 'initial_row');

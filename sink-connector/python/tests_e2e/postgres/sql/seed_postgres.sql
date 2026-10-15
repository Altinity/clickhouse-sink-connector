-- Deterministic source data for the PostgreSQL end-to-end suite of the Python
-- toolset (ch-pg-dump, ch-checksum, ch-pg-checksum, ch-pg-count, auto_diff).
--
-- Everything lives in its own schema so the suite never touches the tables the
-- connector stack seeds into "public". The script is idempotent: it drops and
-- recreates the schema.
--
-- Values are chosen so that the PG and ClickHouse renderings compared by
-- ch-checksum are equal for correctly loaded data:
--   * numeric values have no trailing fractional zero (D-13.07-8: ClickHouse
--     prints Decimal without trailing zeros, PostgreSQL keeps the scale);
--   * the server TimeZone is the image default (GMT), so timestamps without
--     time zone are dumped into DateTime64(6, 'GMT') and read back unshifted.
-- Shapes that ch-pg-dump loads correctly but ch-checksum cannot compare yet
-- live in x_* tables, outside the verification include regex '^t_':
--   * x_special_dates: 'infinity' / '-infinity' saturated to the type bounds;
--   * x_numeric_scale: numeric values with a trailing fractional zero.
-- Each has a strict-xfail checksum test naming its defect.
-- Further x_* tables, the second schema pye2e_b and the role pye2e_reader
-- exist only to show specific pre-fix failures (see JUSTIFICATION.md).

DROP SCHEMA IF EXISTS pye2e CASCADE;
DROP SCHEMA IF EXISTS pye2e_b CASCADE;
DROP ROLE IF EXISTS pye2e_reader;
CREATE SCHEMA pye2e;
CREATE SCHEMA pye2e_b;

-- Keyed table covering the S1 value shapes.
CREATE TABLE pye2e.t_orders (
    id          integer PRIMARY KEY,
    amount      numeric(12,4) NOT NULL,
    price       numeric(10,2),
    payload     bytea,
    ext_id      uuid,
    doc         jsonb,
    created_at  timestamp(6) without time zone NOT NULL,
    updated_at  timestamp(6) with time zone,
    ship_date   date,
    note        text,
    active      boolean
);

INSERT INTO pye2e.t_orders
SELECT i,
       (i + 0.1235)::numeric(12,4),
       CASE WHEN i % 7 = 0 THEN NULL ELSE (i + 0.37)::numeric(10,2) END,
       CASE WHEN i % 5 = 0 THEN NULL ELSE decode(lpad(to_hex(i), 8, '0') || '00ff', 'hex') END,
       CASE WHEN i % 6 = 0 THEN NULL
            ELSE ('00000000-0000-4000-8000-' || lpad(to_hex(i), 12, '0'))::uuid END,
       CASE WHEN i % 4 = 0 THEN NULL
            ELSE jsonb_build_object('n', i, 'tag', 'row-' || i, 'nested', jsonb_build_object('ok', i % 2 = 0)) END,
       timestamp '2024-01-01 00:00:00' + make_interval(days => i, secs => i * 1.234567),
       CASE WHEN i % 3 = 0 THEN NULL
            ELSE timestamptz '2024-03-10 01:30:00.123456+00' + make_interval(hours => i) END,
       CASE WHEN i % 8 = 0 THEN NULL ELSE date '2020-02-28' + i END,
       CASE WHEN i % 9 = 0 THEN NULL
            WHEN i % 9 = 1 THEN ''
            ELSE 'note ' || i || ', with "quotes"' || chr(10) || 'and a newline' END,
       CASE WHEN i % 10 = 0 THEN NULL ELSE i % 2 = 0 END
FROM generate_series(1, 60) AS i;

-- Hand-picked edge rows.
INSERT INTO pye2e.t_orders VALUES
    (1000, 0.0001, 99999999.99, '\x00'::bytea, 'ffffffff-ffff-4fff-bfff-ffffffffffff',
     '{"empty": "", "unicode": "café"}', '1999-12-31 23:59:59.999999',
     '2038-01-19 03:14:07.5+00', '1900-01-01', 'tab	inside', true),
    (1001, -12345678.9999, -0.01, NULL, NULL, NULL, '2000-02-29 12:00:00',
     NULL, NULL, NULL, NULL);

-- Keyless table: the dumper sorts it by every column (allow_nullable_key).
CREATE TABLE pye2e.t_events_nopk (
    event_time  timestamp(6) with time zone NOT NULL,
    kind        text,
    qty         integer
);

INSERT INTO pye2e.t_events_nopk
SELECT timestamptz '2024-05-01 00:00:00+00' + make_interval(mins => i),
       CASE WHEN i % 4 = 0 THEN NULL ELSE 'kind-' || (i % 3) END,
       CASE WHEN i % 5 = 0 THEN NULL ELSE i * 10 END
FROM generate_series(1, 25) AS i;

-- Special temporal values that ch-pg-dump saturates to the ClickHouse type
-- bounds, as the streaming connector does.
CREATE TABLE pye2e.x_special_dates (
    id    integer PRIMARY KEY,
    d     date,
    ts    timestamp(6) without time zone,
    tstz  timestamp(6) with time zone
);

INSERT INTO pye2e.x_special_dates VALUES
    (1, 'infinity',   'infinity',   'infinity'),
    (2, '-infinity',  '-infinity',  '-infinity'),
    (3, '2024-06-30', '2024-06-30 10:11:12.131415', '2024-06-30 10:11:12.131415+00'),
    (4, NULL,         NULL,         NULL);

-- numeric with a trailing fractional zero (1.50): PostgreSQL text keeps the
-- declared scale, ClickHouse Decimal text drops the zero (D-13.07-8).
CREATE TABLE pye2e.x_numeric_scale (
    id     integer PRIMARY KEY,
    price  numeric(10,2) NOT NULL
);

INSERT INTO pye2e.x_numeric_scale VALUES (1, 1.50), (2, 2.25), (3, 10.00);

-- A name that contains "t_orders" without being t_orders: a connector
-- table.include.list of pye2e.t_orders must not select it (D-13.05-6).
CREATE TABLE pye2e.x_t_orders_old (id integer PRIMARY KEY, v text);
INSERT INTO pye2e.x_t_orders_old VALUES (1, 'old');

-- The same table name in two schemas (D-13.05-7), and a table only in the
-- second schema, which the schema list "pye2e" must not select (D-13.05-6).
CREATE TABLE pye2e.dup_t (id integer PRIMARY KEY, src text NOT NULL);
INSERT INTO pye2e.dup_t VALUES (1, 'pye2e'), (2, 'pye2e');
CREATE TABLE pye2e_b.dup_t (id integer PRIMARY KEY, src text NOT NULL);
INSERT INTO pye2e_b.dup_t VALUES (1, 'pye2e_b'), (3, 'pye2e_b');
CREATE TABLE pye2e_b.b_only (id integer PRIMARY KEY);
INSERT INTO pye2e_b.b_only VALUES (1);

-- time with time zone: PostgreSQL has no to_char(timetz, text), so the
-- ch-pg-checksum query on this table fails (a failing table, D-13.07-23).
CREATE TABLE pye2e.x_timetz (id integer PRIMARY KEY, t time with time zone NOT NULL);
INSERT INTO pye2e.x_timetz VALUES (1, '12:34:56.789+02');

-- A checksum user that may read every column of t_orders except "note"
-- (column-level privileges hide it from information_schema, D-13.07-4).
CREATE ROLE pye2e_reader LOGIN PASSWORD 'pye2e';
GRANT USAGE ON SCHEMA pye2e TO pye2e_reader;
GRANT SELECT ON pye2e.t_events_nopk TO pye2e_reader;
GRANT SELECT (id, amount, price, payload, ext_id, doc, created_at, updated_at, ship_date, active)
    ON pye2e.t_orders TO pye2e_reader;

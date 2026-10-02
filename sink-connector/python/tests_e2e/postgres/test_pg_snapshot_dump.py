"""Scenario 1: ch-pg-dump snapshots a seeded PostgreSQL schema into ClickHouse.

Positive: with the connector's logical replication slot present, the dump
exits 0, loads every table (keyless, numeric with scale, bytea, uuid, jsonb,
timestamp, timestamptz, date incl. infinity, NULL vs empty string) and writes
the Debezium offset row.
Negative: without the slot, the dump exits non-zero before any ClickHouse
statement.
"""

import datetime as dt
import json
from decimal import Decimal

from conftest import (
    CH_DATABASE,
    CONNECTOR_NAME,
    MISSING_SLOT_NAME,
    OFFSET_TABLE,
    PG_DATABASE,
    PG_SCHEMA,
    SLOT_NAME,
    ch_database_exists,
    ch_query,
    lsn_to_int,
    pg_query,
    run_pg_dump,
    write_connector_config,
)

DUMPED_TABLES = ["t_events_nopk", "t_orders", "x_numeric_scale", "x_special_dates"]
UTC = dt.timezone.utc


def _naive_utc(value):
    """A ClickHouse DateTime64 value (tz-aware) as a naive UTC datetime."""
    if value is None:
        return None
    return value.astimezone(UTC).replace(tzinfo=None) if value.tzinfo else value


def test_dump_exits_zero_and_loads_every_table(dumped):
    output = dumped["output"]
    assert dumped["rc"] == 0
    assert "postgres_dumper finished successfully" in output
    assert f"Replication slot '{SLOT_NAME}' OK" in output
    assert "All tables loaded successfully" in output
    for table in DUMPED_TABLES:
        pg_count = pg_query(f'SELECT count(*) AS n FROM {PG_SCHEMA}."{table}"')[0]["n"]
        ch_count = ch_query(
            f"SELECT count() FROM `{CH_DATABASE}`.`{table}` FINAL WHERE is_deleted = 0")[0][0]
        assert ch_count == pg_count, f"{table}: ClickHouse {ch_count} rows, PostgreSQL {pg_count}"
        assert pg_count > 0


def test_keyless_table_is_sorted_by_every_column(dumped):
    sorting_key, create = ch_query(
        "SELECT sorting_key, create_table_query FROM system.tables "
        "WHERE database = %(d)s AND name = 't_events_nopk'", {"d": CH_DATABASE})[0]
    assert sorting_key == "event_time, kind, qty"
    assert "allow_nullable_key = 1" in create
    assert "KEYLESS TABLE" in dumped["output"]
    # Distinct source rows must not collapse under the all-columns key.
    pg_rows = pg_query(
        f"SELECT event_time, kind, qty FROM {PG_SCHEMA}.t_events_nopk ORDER BY event_time")
    ch_rows = ch_query(
        f"SELECT event_time, kind, qty FROM `{CH_DATABASE}`.t_events_nopk FINAL "
        f"ORDER BY event_time")
    assert [(r["event_time"], r["kind"], r["qty"]) for r in pg_rows] == [
        (e, k, q) for (e, k, q) in ch_rows]


def test_values_round_trip_exactly(dumped):
    pg_rows = pg_query(
        f"SELECT id, amount, price, encode(payload, 'hex') AS payload_hex, ext_id::text AS ext_id, "
        f"doc::text AS doc, created_at, updated_at, ship_date, note, active "
        f"FROM {PG_SCHEMA}.t_orders ORDER BY id")
    ch_rows = ch_query(
        f"SELECT id, amount, price, payload, ext_id, doc, created_at, updated_at, ship_date, "
        f"note, active FROM `{CH_DATABASE}`.t_orders FINAL ORDER BY id")
    assert len(pg_rows) == len(ch_rows) == 62
    for pg, ch in zip(pg_rows, ch_rows):
        (cid, amount, price, payload, ext_id, doc, created, updated, ship, note, active) = ch
        ctx = f"id={pg['id']}"
        assert cid == pg["id"], ctx
        assert isinstance(amount, Decimal) and amount == pg["amount"], ctx
        assert price == pg["price"], ctx
        # bytea travels as PostgreSQL's hex text ("\x..."), NULL stays NULL.
        assert payload == (None if pg["payload_hex"] is None else "\\x" + pg["payload_hex"]), ctx
        assert ext_id == pg["ext_id"], ctx
        assert doc == pg["doc"], ctx
        if doc is not None:
            assert json.loads(doc) == json.loads(pg["doc"]), ctx
        # timestamp without time zone: the wall clock in the column's zone.
        assert created.replace(tzinfo=None) == pg["created_at"], ctx
        # timestamptz: the same instant.
        assert _naive_utc(updated) == _naive_utc(pg["updated_at"]), ctx
        assert ship == pg["ship_date"], ctx
        # NULL and '' stay distinct.
        assert note == pg["note"], ctx
        assert active == (None if pg["active"] is None else int(pg["active"])), ctx
    by_id = {r[0]: r for r in ch_rows}
    assert by_id[1][9] == "" and by_id[9][9] is None
    assert by_id[1000][3] == "\\x00"
    assert by_id[1001][1] == Decimal("-12345678.9999")


def test_special_dates_saturate_to_the_type_bounds(dumped):
    rows = ch_query(
        f"SELECT id, toString(d), toString(toTimeZone(ts, 'UTC')), "
        f"toString(toTimeZone(tstz, 'UTC')) FROM `{CH_DATABASE}`.x_special_dates FINAL ORDER BY id")
    assert rows == [
        (1, "2299-12-31", "2299-12-31 23:59:59.000000", "2299-12-31 23:59:59.000000"),
        (2, "1900-01-01", "1900-01-01 00:00:00.000000", "1900-01-01 00:00:00.000000"),
        (3, "2024-06-30", "2024-06-30 10:11:12.131415", "2024-06-30 10:11:12.131415"),
        (4, None, None, None),
    ]


def test_numeric_scale_values_are_loaded_exactly(dumped):
    rows = ch_query(f"SELECT id, price FROM `{CH_DATABASE}`.x_numeric_scale FINAL ORDER BY id")
    assert rows == [(1, Decimal("1.50")), (2, Decimal("2.25")), (3, Decimal("10.00"))]


def test_offset_row_records_the_snapshot_lsn(dumped):
    rows = ch_query(f"SELECT offset_key, offset_val FROM {OFFSET_TABLE} FINAL")
    assert len(rows) == 1
    key, val = rows[0]
    assert key == json.dumps([CONNECTOR_NAME, {"server": "embeddedconnector"}],
                             separators=(",", ":"))
    payload = json.loads(val)
    assert payload["lsn"] == payload["lsn_proc"]
    slot_lsn = lsn_to_int(dumped["slot"]["lsn"])
    # The recorded LSN is read after the slot existed and before the dump ended.
    assert slot_lsn <= payload["lsn"] <= dumped["lsn_after"]
    assert payload["lsn"] >= dumped["lsn_before"]


def test_slot_is_left_in_place_for_the_connector(dumped):
    # The dumper verifies the slot; it neither creates, advances nor drops it.
    rows = pg_query(
        "SELECT slot_type, plugin, database, active FROM pg_replication_slots "
        "WHERE slot_name = %s", (SLOT_NAME,))
    assert rows == [{"slot_type": "logical", "plugin": "pgoutput",
                     "database": PG_DATABASE, "active": False}]


def test_missing_slot_fails_before_touching_clickhouse(seeded, tmp_path):
    target_db = "pye2e_pg_noslot"
    offset_db = "pye2e_offsets_noslot"
    assert not pg_query("SELECT 1 FROM pg_replication_slots WHERE slot_name = %s",
                        (MISSING_SLOT_NAME,))
    config = write_connector_config(tmp_path / "connector.yml", MISSING_SLOT_NAME,
                                    offset_table=f"{offset_db}.replica_source_info")
    rc, output = run_pg_dump(config, target_db)
    assert rc == 1
    assert f"Replication slot '{MISSING_SLOT_NAME}' does not exist on the source" in output
    assert "postgres_dumper finished successfully" not in output
    assert "=== Step 3" not in output
    assert not ch_database_exists(target_db)
    assert not ch_database_exists(offset_db)

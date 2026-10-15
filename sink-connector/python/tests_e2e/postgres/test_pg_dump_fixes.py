"""ch-pg-dump fixes, each shown end to end (see JUSTIFICATION.md).

Every test here asserts the fixed behaviour of one ch-pg-dump defect
(spec 13.05 D-13.05-n). Run against the pre-fix tools
(PYTOOLS_E2E_TOOLS_ROOT pointing at an older tree), each one fails: the old
dumper reports success where it should fail, or loads wrong data. Each test
writes into its own ClickHouse database, so the tests are order independent.
"""

from conftest import (
    CH_DATABASE,
    PG_SCHEMA,
    SLOT_NAME,
    ch_database_exists,
    ch_query,
    pg_query,
    run_pg_dump,
    write_connector_config,
)


def _ch_tables(db):
    return sorted(r[0] for r in ch_query(
        "SELECT name FROM system.tables WHERE database = %(d)s", {"d": db}))


def _config(tmp_path, tag, keys=None):
    """A connector config for one test, with its own offset database."""
    offset_db = f"pye2e_offsets_{tag}"
    path = write_connector_config(tmp_path / "connector.yml", SLOT_NAME,
                                  offset_table=f"{offset_db}.replica_source_info",
                                  keys=keys)
    return path, offset_db


def _offset_rows(offset_db):
    if not ch_database_exists(offset_db):
        return 0
    return ch_query(f"SELECT count() FROM `{offset_db}`.replica_source_info")[0][0]


def test_failed_clickhouse_insert_fails_the_run_and_writes_no_offset(connector_slot, tmp_path):
    """D-13.05-1, D-13.05-2: a load whose INSERT is rejected must not succeed."""
    db = "pye2e_pg_badload"
    ch_query(f"DROP DATABASE IF EXISTS `{db}` SYNC")  # DESTRUCTIVE: suite-owned test database only
    ch_query(f"CREATE DATABASE `{db}`")
    # An existing target without the `note` column (CREATE TABLE IF NOT EXISTS
    # keeps it as is): the INSERT names `note`, so ClickHouse rejects it.
    ch_query(
        f"CREATE TABLE `{db}`.t_orders (id Int32, amount Decimal(12, 4), "
        f"price Nullable(Decimal(10, 2)), "
        f"payload Nullable(String), ext_id Nullable(String), doc Nullable(String), "
        f"created_at DateTime64(6, 'UTC'), updated_at Nullable(DateTime64(6, 'UTC')), "
        f"ship_date Nullable(Date32), active Nullable(UInt8), "
        f"_version UInt64 DEFAULT 0, is_deleted UInt8 DEFAULT 0) "
        f"ENGINE = ReplacingMergeTree(_version, is_deleted) ORDER BY id")
    config, offset_db = _config(tmp_path, "badload")
    rc, output = run_pg_dump(config, db, "--tables", "^t_orders$")
    assert rc == 1, "a rejected INSERT must fail the run"
    assert "FAILED tables" in output
    assert "postgres_dumper finished successfully" not in output
    assert _offset_rows(offset_db) == 0, "no offset may be written over a failed load"
    assert ch_query(f"SELECT count() FROM `{db}`.t_orders")[0][0] == 0


def test_skip_existing_refuses_a_partly_loaded_table(dumped, tmp_path):
    """D-13.05-4: --skip_existing must not treat a partial table as done."""
    db = "pye2e_pg_partial"
    ch_query(f"DROP DATABASE IF EXISTS `{db}` SYNC")  # DESTRUCTIVE: suite-owned test database only
    ch_query(f"CREATE DATABASE `{db}`")
    ch_query(f"CREATE TABLE `{db}`.t_orders AS `{CH_DATABASE}`.t_orders")
    ch_query(f"INSERT INTO `{db}`.t_orders SELECT * FROM `{CH_DATABASE}`.t_orders "
             f"FINAL WHERE id <= 30")
    config, offset_db = _config(tmp_path, "partial")
    rc, output = run_pg_dump(config, db, "--tables", "^t_orders$",
                             "--skip_existing", "--data_only")
    assert rc == 1
    assert "--skip_existing: ClickHouse has 30 rows (FINAL) but the source has 62" in output
    assert _offset_rows(offset_db) == 0
    assert ch_query(f"SELECT count() FROM `{db}`.t_orders FINAL")[0][0] == 30


def test_two_source_tables_never_share_a_clickhouse_table(connector_slot, tmp_path):
    """D-13.05-7: pye2e.dup_t and pye2e_b.dup_t into one database must stop the run."""
    db = "pye2e_pg_collide"
    config, offset_db = _config(tmp_path, "collide",
                                keys={"schema.include.list": "pye2e,pye2e_b"})
    rc, output = run_pg_dump(config, db, "--tables", "^dup_t$")
    assert rc == 1
    assert f"ClickHouse target {db}.dup_t would receive 2 source tables" in output
    assert not ch_database_exists(db), "nothing may be written before the refusal"
    assert _offset_rows(offset_db) == 0


def test_schema_include_list_is_anchored(dumped):
    """D-13.05-6: schema.include.list 'pye2e' must not also select 'pye2e_b'."""
    tables = _ch_tables(CH_DATABASE)
    assert "b_only" not in tables, tables
    rows = ch_query(f"SELECT id, src FROM `{CH_DATABASE}`.dup_t FINAL ORDER BY id")
    assert rows == [(1, "pye2e"), (2, "pye2e")]


def test_table_include_list_keeps_its_schema_and_is_anchored(connector_slot, tmp_path):
    """D-13.05-6: table.include.list 'pye2e.t_orders' selects exactly that table."""
    db = "pye2e_pg_tlist"
    config, _ = _config(tmp_path, "tlist", keys={"table.include.list": "pye2e.t_orders"})
    rc, output = run_pg_dump(config, db)
    assert rc == 0, output[-2000:]
    assert _ch_tables(db) == ["t_orders"]


def _wall_clock_rows(db):
    pg = pg_query(
        f"SELECT id, to_char(created_at, 'YYYY-MM-DD HH24:MI:SS.US') AS created, "
        f"ship_date::text AS ship FROM {PG_SCHEMA}.t_orders ORDER BY id")
    ch = ch_query(f"SELECT id, toString(created_at), toString(ship_date) "
                  f"FROM `{db}`.t_orders FINAL ORDER BY id")
    return [(r["id"], r["created"], r["ship"]) for r in pg], ch


def test_zone_less_timestamp_keeps_its_wall_clock_in_a_non_utc_zone(connector_slot, tmp_path):
    """D-13.05-8: with server TimeZone America/Chicago a timestamp must not shift."""
    db = "pye2e_pg_tz"
    config, _ = _config(tmp_path, "tz")
    # PGTZ sets the TimeZone of the dumper's sessions, as a server default would.
    rc, output = run_pg_dump(config, db, "--tables", "^t_orders$",
                             extra_env={"PGTZ": "America/Chicago"})
    assert rc == 0, output[-2000:]
    col_type = ch_query(f"SELECT type FROM system.columns WHERE database = %(d)s "
                        f"AND table = 't_orders' AND name = 'created_at'", {"d": db})[0][0]
    assert "America/Chicago" in col_type
    pg, ch = _wall_clock_rows(db)
    # Rendered in the column's zone, every value reads the source wall clock.
    assert [(i, c) for (i, c, _) in ch] == [(i, c) for (i, c, _) in pg]


def test_session_datestyle_cannot_change_loaded_values(connector_slot, tmp_path):
    """D-13.05-10: a non-ISO DateStyle default must not corrupt dates."""
    db = "pye2e_pg_datestyle"
    config, _ = _config(tmp_path, "datestyle")
    rc, output = run_pg_dump(config, db, "--tables", "^t_orders$",
                             extra_env={"PGDATESTYLE": "SQL, DMY"})
    assert rc == 0, output[-2000:]
    pg, ch = _wall_clock_rows(db)
    assert [(i, c, s) for (i, c, s) in ch] == [(i, c, s) for (i, c, s) in pg]


def test_overridden_column_is_loaded_as_overridden(connector_slot, tmp_path):
    """D-13.05-11: a direct override to String loads the source text verbatim."""
    db = "pye2e_pg_override"
    config, _ = _config(tmp_path, "override")
    rc, output = run_pg_dump(config, db, "--tables", "^x_special_dates$",
                             "--column_type_overrides",
                             f"direct:{PG_SCHEMA}.x_special_dates.ts=String")
    assert rc == 0, output[-2000:]
    col_type = ch_query(f"SELECT type FROM system.columns WHERE database = %(d)s "
                        f"AND table = 'x_special_dates' AND name = 'ts'", {"d": db})[0][0]
    assert col_type == "String"   # a direct override replaces the type as given
    rows = ch_query(f"SELECT id, ts FROM `{db}`.x_special_dates FINAL ORDER BY id")
    # The PostgreSQL text verbatim; NULL becomes '' in the non-Nullable String.
    assert rows == [(1, "infinity"), (2, "-infinity"),
                    (3, "2024-06-30 10:11:12.131415"), (4, "")]


def test_pgdump_strategy_is_refused_before_touching_anything(connector_slot, tmp_path):
    """D-13.05-12: the corrupting pgdump strategy is refused (usage error, exit 2)."""
    db = "pye2e_pg_pgdump"
    config, offset_db = _config(tmp_path, "pgdump")
    rc, output = run_pg_dump(config, db, "--strategy", "pgdump",
                             "--dump_dir", tmp_path / "dump")
    assert rc == 2
    assert "--strategy pgdump is disabled" in output
    assert not ch_database_exists(db)
    assert _offset_rows(offset_db) == 0

"""Scenario 3: the auto_diff phase of ch-checksum locates planted rows by key.

A clone of the dumped database gets three divergences on t_orders: a modified
value (id 42), a row deleted on the ClickHouse side (id 43, is_deleted = 1) and
a row present only in ClickHouse (id 5000). ch-checksum in snapshot mode with
auto_diff enabled must FAIL the table, exit 1, and write a diff file that names
exactly those keys, with the per-column difference of the modified row. The
negative control (no diff file on matching data) is part of
test_pg_verification.py::test_ch_checksum_passes_on_dumped_data[snapshot].
"""

from conftest import (
    CH_DATABASE,
    VERIFIED_TABLES,
    ch_query,
    clone_ch_database,
    load_json,
    parse_checksum_summary,
    pg_query,
    run_ch_checksum,
    write_checksum_config,
)


def test_auto_diff_locates_planted_rows_by_primary_key(dumped, tmp_path):
    db = "pye2e_pg_autodiff"
    clone_ch_database(CH_DATABASE, db, VERIFIED_TABLES)
    ch_query(f"INSERT INTO `{db}`.t_orders SELECT * REPLACE ('planted' AS note, 1 AS _version) "
             f"FROM `{db}`.t_orders FINAL WHERE id = 42")
    ch_query(f"INSERT INTO `{db}`.t_orders SELECT * REPLACE (1 AS _version, 1 AS is_deleted) "
             f"FROM `{db}`.t_orders FINAL WHERE id = 43")
    ch_query(f"INSERT INTO `{db}`.t_orders SELECT * REPLACE (5000 AS id) "
             f"FROM `{db}`.t_orders FINAL WHERE id = 50")
    diff_dir = tmp_path / "diff"
    config = write_checksum_config(tmp_path / "checksum.yml", db, snapshot_mode=True,
                                   auto_diff_dir=diff_dir)
    rc, output = run_ch_checksum(config)

    rows, result_line, exit_line = parse_checksum_summary(output)
    assert rc == 1
    assert (rows["t_orders"]["checksum"], rows["t_orders"]["status"]) == ("MISMATCH", "FAIL")
    assert (rows["t_events_nopk"]["status"]) == "PASS"
    assert exit_line == "Exit code: 1"
    assert "AUTO_DIFF: [t_orders] complete: 3 divergent rows" in output

    files = sorted(diff_dir.glob("checksum_diff_*.json"))
    assert [f.name.startswith("checksum_diff_t_orders_") for f in files] == [True], files
    diff = load_json(files[0])
    assert diff["metadata"]["pk_column"] == "id"
    found = {r["pk_value"]: r for r in diff["divergent_rows"]}
    assert {k: r["diff_type"] for k, r in found.items()} == {
        42: "modified", 43: "pg_only", 5000: "ch_only"}
    assert diff["summary"]["total_divergent"] == 3
    assert diff["summary"]["truncated"] is False
    assert diff["skipped_chunks"] == []

    # The modified row: only the planted column differs, with both values shown.
    columns = found[42]["columns"]
    assert {c for c, v in columns.items() if not v["match"]} == {"note"}
    pg_note = pg_query("SELECT note FROM pye2e.t_orders WHERE id = 42")[0]["note"]
    assert columns["note"] == {"pg": pg_note, "ch": "planted", "match": False}

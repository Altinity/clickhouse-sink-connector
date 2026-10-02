"""The MySQL side's JSON warning tells a standalone run what to pass to the ClickHouse side (spec 13.06 D-13.06-41).

Run standalone, the MySQL side skips JSON columns by default; the ClickHouse side cannot tell a String column that
replicates a MySQL JSON column from any other String unless it is given --json_columns, so with the same
--exclude_columns on both sides every row of such a table differs. The warning now names the exact flag and list.
The checksum driver already forwards --json_columns to the ClickHouse side, so it relays the side note without
that hint and the job log is unchanged. Both copies. Offline; run from sink-connector/python:
    python3 -m pytest db_compare/tests/test_json_column_hint.py
"""
import logging
import os
import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))

import ch_sink_tools.db_compare.top_level_table_checksum as pt  # noqa: E402
import db_compare.mysql_table_checksum as my  # noqa: E402
import db_compare.top_level_table_checksum as tl  # noqa: E402
import db_compare.tests.test_checksum_fidelity as fidelity  # noqa: E402
import db_compare.tests.test_packaged_checksum_verdicts as packaged  # noqa: E402

HINT = "pass --json_columns j,k to clickhouse_table_checksum.py so both row strings skip them"


def warnings_of(caplog):
    return [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]


def test_legacy_mysql_side_names_the_flag_and_the_columns(caplog):
    my.warned_tables.clear()
    columns = [fidelity.mysql_column("id", "int"), fidelity.mysql_column("j", "json"),
               fidelity.mysql_column("k", "json"), fidelity.mysql_column("f", "double")]
    with caplog.at_level(logging.WARNING):
        fidelity.build_mysql_select(columns)
    json_warnings = [w for w in warnings_of(caplog) if "JSON columns ['j', 'k']" in w]
    assert len(json_warnings) == 1, warnings_of(caplog)
    assert json_warnings[0] == ("Not compared in table db1.t1: JSON columns ['j', 'k'] (pass --include_json_columns "
                                f"for a best-effort text comparison; {HINT})")
    assert not any("--json_columns" in w for w in warnings_of(caplog) if "floating point" in w)


def test_clickhouse_side_warning_has_no_hint(caplog):
    import db_compare.clickhouse_table_checksum as ch
    ch.warned_tables.clear()
    with caplog.at_level(logging.WARNING):
        fidelity.TestClickHouseRowExpression().build(fidelity.CLICKHOUSE_COLUMNS + [("j", "String", 0, None)], json_columns="j")
    assert any("JSON columns ['j']" in w for w in warnings_of(caplog)), warnings_of(caplog)
    assert not any("--json_columns" in w for w in warnings_of(caplog))


def test_packaged_mysql_side_names_the_flag_and_the_columns(caplog):
    with caplog.at_level(logging.WARNING):
        packaged.mysql_select_for([("id", "int", "NO", None), ("j", "json", "YES", None), ("k", "json", "YES", None)])
    json_warnings = [w for w in warnings_of(caplog) if "JSON column" in w]
    assert len(json_warnings) == 2 and all(w.endswith(f"; {HINT})") for w in json_warnings), json_warnings


def test_drivers_relay_the_side_note_without_the_hint():
    """The job forwards --json_columns itself; its log keeps the side note it logged before (no new text, no
    WARNING)."""
    before = ("2026-10-01 10:00:00,000 - WARNING - MainThread - Not compared in table db1.t1: JSON columns "
              "['j', 'k'] (pass --include_json_columns for a best-effort text comparison)")
    after = before[:-1] + f"; {HINT})"
    expected = before.replace(" - WARNING - ", " - ")
    assert tl.side_note_text(after) == expected
    assert tl.side_note_text(before) == expected
    packaged_before = ("2026-10-01 10:00:00,000 - WARNING - MainThread - Not compared in table db1.t1: JSON column "
                       "`j` of type json (pass --include_json_columns for a best-effort comparison)")
    packaged_after = packaged_before[:-1] + f"; {HINT})"
    assert pt.side_note_text(packaged_after) == packaged_before.replace(" - WARNING - ", " - ")
    assert "WARNING" not in tl.side_note_text(after) and "WARNING" not in pt.side_note_text(packaged_after)

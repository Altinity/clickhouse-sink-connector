"""Checksum-based integration test for the sink-connector-lightweight.

After the ``replicated_stack`` fixture confirms the seeded ``test`` database has
snapshot-replicated MySQL -> ClickHouse, this runs the project's existing table
checksum scripts against both databases and asserts that every verified table has
an identical (md5, row-count) checksum.
"""

import logging
import os
import re
import subprocess
from pathlib import Path

logger = logging.getLogger(__name__)

from conftest import (
    CLICKHOUSE_HOST,
    CLICKHOUSE_PASSWORD,
    CLICKHOUSE_USER,
    DATABASE,
    MYSQL_HOST,
    MYSQL_PASSWORD,
    MYSQL_USER,
    REPLICATED_TABLES as TABLES,
)

# Checksum scripts live under sink-connector/python and must run with that as CWD
# so their `db_compare` / `db` packages import correctly.
REPO_ROOT = Path(__file__).resolve().parents[3]
PYTHON_DIR = REPO_ROOT / "sink-connector" / "python"

# Matching datetime clamps for both sides (mirrors top_level_table_checksum.py).
MIN_DATETIME = "1969-12-31 18:00:00"
MAX_DATETIME = "2299-12-31 00:00:00"

# `Checksum for table <db>.<table> = <md5> count <n>` (ignores the log-line prefix).
CHECKSUM_RE = re.compile(
    r"Checksum for table\s+(?P<db>\S+?)\.(?P<table>\S+?)\s+=\s+"
    r"(?P<md5>[0-9a-fA-F]+)\s+count\s+(?P<count>\d+)"
)

TABLES_REGEX = "^(" + "|".join(TABLES) + ")$"


def _run_checksum(cmd):
    """Run a checksum script and parse its stdout into {table: (md5, count)}."""
    # The scripts import `from db.mysql import *`; running them by path puts the
    # db_compare/ dir on sys.path (not PYTHON_DIR), so add PYTHON_DIR to PYTHONPATH
    # to make the sibling `db` package importable.
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join(
        [str(PYTHON_DIR), env.get("PYTHONPATH", "")]
    ).rstrip(os.pathsep)
    proc = subprocess.run(
        cmd,
        cwd=str(PYTHON_DIR),
        capture_output=True,
        text=True,
        env=env,
    )
    assert proc.returncode == 0, (
        f"Checksum command failed ({proc.returncode}):\n"
        f"CMD: {' '.join(cmd)}\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )
    results = {}
    for line in proc.stdout.splitlines():
        m = CHECKSUM_RE.search(line)
        if m:
            results[m.group("table")] = (m.group("md5").lower(), int(m.group("count")))
    return results


def _mysql_checksums():
    cmd = [
        "python3",
        "db_compare/mysql_table_checksum.py",
        "--mysql_host", MYSQL_HOST,
        "--mysql_user", MYSQL_USER,
        "--mysql_password", MYSQL_PASSWORD,
        "--mysql_database", DATABASE,
        "--tables_regex", TABLES_REGEX,
        "--min_date_value", "1900-01-01",
        "--min_datetime_value", MIN_DATETIME,
        "--max_datetime_value", MAX_DATETIME,
        "--binary_encoding", "base64",
    ]
    return _run_checksum(cmd)


def _clickhouse_checksums():
    cmd = [
        "python3",
        "db_compare/clickhouse_table_checksum.py",
        "--clickhouse_host", CLICKHOUSE_HOST,
        "--clickhouse_user", CLICKHOUSE_USER,
        "--clickhouse_password", CLICKHOUSE_PASSWORD,
        "--clickhouse_database", DATABASE,
        "--tables_regex", TABLES_REGEX,
        "--min_datetime_value", MIN_DATETIME,
        "--max_datetime_value", MAX_DATETIME,
        "--sign_column", "",
        "--exclude_columns", "_version,is_deleted,_is_deleted,__is_deleted,_sign",
    ]
    return _run_checksum(cmd)


def _print_checksums(mysql_results, clickhouse_results):
    """Print (and log) the MySQL vs ClickHouse checksum + row count per table.

    Uses print() so the values are visible in pytest output (captured stdout is
    shown on failure and always with `-s`); logger.info is swallowed by pytest's
    default log capture.
    """
    header = f"{'table':<16} {'mysql_count':>12} {'ch_count':>12}  {'mysql_md5':<34} {'clickhouse_md5':<34}"
    lines = ["", "=== MySQL -> ClickHouse checksums ===", header, "-" * len(header)]
    for table in TABLES:
        m = mysql_results.get(table)
        c = clickhouse_results.get(table)
        m_md5, m_cnt = (m if m else ("<missing>", "-"))
        c_md5, c_cnt = (c if c else ("<missing>", "-"))
        lines.append(
            f"{table:<16} {str(m_cnt):>12} {str(c_cnt):>12}  {str(m_md5):<34} {str(c_md5):<34}"
        )
    report = "\n".join(lines)
    print(report)
    logger.info(report)


def test_snapshot_checksum_matches(replicated_stack):
    mysql_results = _mysql_checksums()
    clickhouse_results = _clickhouse_checksums()

    _print_checksums(mysql_results, clickhouse_results)

    mismatches = []
    for table in TABLES:
        mysql_val = mysql_results.get(table)
        ch_val = clickhouse_results.get(table)
        if mysql_val is None:
            mismatches.append(f"{table}: missing MySQL checksum")
        elif ch_val is None:
            mismatches.append(f"{table}: missing ClickHouse checksum")
        elif mysql_val != ch_val:
            mismatches.append(
                f"{table}: mysql(md5={mysql_val[0]}, count={mysql_val[1]}) != "
                f"clickhouse(md5={ch_val[0]}, count={ch_val[1]})"
            )

    assert not mismatches, "MySQL -> ClickHouse checksum mismatch:\n" + "\n".join(mismatches)

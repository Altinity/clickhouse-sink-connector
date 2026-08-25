"""Checksum-based end-to-end test for KEYLESS source tables.

A source table with no PRIMARY KEY and no UNIQUE key has no row identity of its
own, so the connector generates a ``_row_key`` column in ClickHouse to give
ReplacingMergeTree something to deduplicate on. This runs the project's real
``db_compare`` checksum scripts -- the same ones the Jenkins
``*_top_level_table_checksum_*`` jobs run in production -- against both
databases and asserts the two sides agree on md5 AND row count.

Two distinct failures are covered, and they pull in opposite directions:

1. The generated ``_row_key`` exists ONLY in ClickHouse. MySQL has no column to
   hash against it, so it must be excluded from the ClickHouse side. Left in,
   the two sides hash different column sets and EVERY keyless table is reported
   as a MISMATCH -- indistinguishable from the genuine divergence these jobs
   exist to detect.

2. A source table may itself declare a column called ``_row_key``. That column
   is real data; the generated one is renamed to ``__row_key`` to make room.
   Excluding the source column too would hide a genuine divergence in it -- a
   false PASS, which is the worse failure of the two. ``keyless_name_clash``
   pins that case.

Row counts are compared through the checksum tooling rather than separately: a
row-identity defect shows up as a count difference (rows collapsing into one),
and a value defect shows up as an md5 difference. Both are caught here.
"""

import logging
import os
import re
import subprocess
from pathlib import Path

import pytest

logger = logging.getLogger(__name__)

from conftest import (
    CLICKHOUSE_HOST,
    CLICKHOUSE_PASSWORD,
    CLICKHOUSE_USER,
    DATABASE,
    MYSQL_HOST,
    MYSQL_PASSWORD,
    MYSQL_USER,
    clickhouse_client,
    mysql_connection,
    wait_for_replication,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
PYTHON_DIR = REPO_ROOT / "sink-connector" / "python"

MIN_DATETIME = "1969-12-31 18:00:00"
MAX_DATETIME = "2299-12-31 00:00:00"

CHECKSUM_RE = re.compile(
    r"Checksum for table\s+(?P<db>\S+?)\.(?P<table>\S+?)\s+=\s+"
    r"(?P<md5>[0-9a-fA-F]+)\s+count\s+(?P<count>\d+)"
)

# Keyless tables created by this test. Each isolates one property of the
# generated row identity.
KEYLESS_TABLES = [
    "keyless_basic",       # distinct rows must survive
    "keyless_nulls",       # NULLs, including an all-NULL row
    "keyless_binary",      # BLOBs: the value must drive the key, not JVM identity
    "keyless_name_clash",  # source column already called _row_key
]
# Control: has its own primary key, so the connector generates nothing.
KEYED_TABLE = "keyed_control"

ALL_TABLES = KEYLESS_TABLES + [KEYED_TABLE]
TABLES_REGEX = "^(" + "|".join(ALL_TABLES) + ")$"

# The exclusion list the checksum scripts default to. Spelled out here rather
# than imported so this test fails if the scripts' defaults regress: it is the
# production behaviour being pinned, not a shared constant.
EXCLUDE_COLUMNS = (
    "_version,is_deleted,_is_deleted,__is_deleted,_sign,"
    "_row_key,__row_key,___row_key"
)

DDL = [
    # DESTRUCTIVE: five DROP TABLE IF EXISTS. Blast radius is the five tables
    # this file creates, in the throwaway `test` database of the
    # docker-compose MySQL the checksum suite brings up and tears down -- never
    # a shared or production database. They exist so a rerun against a reused
    # container starts from a known state instead of appending to stale rows.
    "DROP TABLE IF EXISTS keyless_basic",
    "DROP TABLE IF EXISTS keyless_nulls",
    "DROP TABLE IF EXISTS keyless_binary",
    "DROP TABLE IF EXISTS keyless_name_clash",
    "DROP TABLE IF EXISTS keyed_control",
    "CREATE TABLE keyless_basic(a int, b varchar(64))",
    "CREATE TABLE keyless_nulls(a int, b varchar(64))",
    "CREATE TABLE keyless_binary(a int, payload blob)",
    "CREATE TABLE keyless_name_clash(_row_key int, v varchar(64))",
    "CREATE TABLE keyed_control(id int not null primary key, v varchar(64))",
]

SEED = [
    "INSERT INTO keyless_basic VALUES (1,'one'),(2,'two'),(3,'three')",
    # Two rows sharing a value plus an all-NULL row: the identity must depend on
    # the whole row, and a NULL must not collide with the empty string.
    "INSERT INTO keyless_nulls VALUES (NULL,NULL),(1,'same'),(2,'same'),(3,'')",
    "INSERT INTO keyless_binary VALUES (1, x'0102030405'),(2, x'FF00FF00')",
    "INSERT INTO keyless_name_clash VALUES (1,'one'),(2,'two')",
    "INSERT INTO keyed_control VALUES (1,'one'),(2,'two')",
]

# Applied after the initial seed has replicated: an UPDATE must replace rather
# than duplicate, and a DELETE must remove exactly one row. Both resolve through
# the generated identity, so a broken key shows up as a count mismatch.
MUTATIONS = [
    "UPDATE keyless_basic SET b='two_updated' WHERE a=2",
    # DESTRUCTIVE: DELETE against the throwaway docker-compose MySQL created by
    # this test only -- never a production database. It is the point of the
    # test: a DELETE on a keyless table must resolve to exactly one row through
    # the generated identity, which is what the checksum then verifies.
    "DELETE FROM keyless_basic WHERE a=3",
    "UPDATE keyless_nulls SET b='changed' WHERE a=1",
]


def _run_checksum(cmd):
    """Run a checksum script and parse its stdout into {table: (md5, count)}."""
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
    # A checksum run that produced nothing is a harness failure, not a pass:
    # every downstream comparison would trivially agree on "missing".
    assert results, (
        f"Checksum command produced no checksum lines -- the comparison would be "
        f"vacuous.\nCMD: {' '.join(cmd)}\nSTDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
    )
    return results


def _mysql_checksums():
    return _run_checksum([
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
    ])


def _clickhouse_checksums():
    return _run_checksum([
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
        "--exclude_columns", EXCLUDE_COLUMNS,
    ])


def _execute(statements):
    conn = mysql_connection()
    try:
        with conn.cursor() as cur:
            for statement in statements:
                cur.execute(statement)
        conn.commit()
    finally:
        conn.close()


def _report(mysql_results, clickhouse_results):
    header = (f"{'table':<20} {'mysql_count':>12} {'ch_count':>12}  "
              f"{'mysql_md5':<34} {'clickhouse_md5':<34}")
    lines = ["", "=== keyless MySQL -> ClickHouse checksums ===", header, "-" * len(header)]
    for table in ALL_TABLES:
        m_md5, m_cnt = mysql_results.get(table, ("<missing>", "-"))
        c_md5, c_cnt = clickhouse_results.get(table, ("<missing>", "-"))
        lines.append(f"{table:<20} {str(m_cnt):>12} {str(c_cnt):>12}  "
                     f"{str(m_md5):<34} {str(c_md5):<34}")
    report = "\n".join(lines)
    print(report)
    logger.info(report)


def _compare(stage):
    mysql_results = _mysql_checksums()
    clickhouse_results = _clickhouse_checksums()
    _report(mysql_results, clickhouse_results)

    mismatches = []
    for table in ALL_TABLES:
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
    assert not mismatches, (
        f"MySQL -> ClickHouse checksum mismatch after {stage}:\n" + "\n".join(mismatches)
    )


@pytest.fixture(scope="module")
def keyless_replicated(replicated_stack):
    """Create the keyless tables, seed them, and wait for replication.

    Depends on ``replicated_stack`` so the compose stack and the connector are
    already up and the connector is streaming the binlog; these tables are
    created afterwards and arrive through CDC rather than the snapshot.
    """
    _execute(DDL + SEED)
    wait_for_replication(ALL_TABLES)
    yield


def test_keyless_tables_checksum_matches(keyless_replicated):
    """The generated row key must not make a correct table look divergent."""
    _compare("initial seed")


def test_keyless_tables_checksum_matches_after_updates(keyless_replicated):
    """UPDATE and DELETE must resolve through the generated identity.

    A row identity that does not survive an UPDATE strands the pre-update row,
    and the table then has more rows in ClickHouse than in MySQL. Comparing
    through the checksum tooling catches that as a count difference, and any
    value drift as an md5 difference.
    """
    _execute(MUTATIONS)
    wait_for_replication(ALL_TABLES)
    _compare("UPDATE and DELETE")


def test_keyless_tables_checksum_matches_after_schema_change(keyless_replicated):
    """The identity must follow the schema, and must not freeze it.

    Keying on the data columns made ClickHouse refuse to alter any of them, and
    keying on an expression over them left rows that differ only in a
    later-added column sharing one key -- they collapsed silently. Both show up
    here: a rejected ALTER leaves the column missing, and a collapse leaves
    fewer rows in ClickHouse than in MySQL.
    """
    _execute([
        "ALTER TABLE keyless_basic ADD COLUMN c int",
        "ALTER TABLE keyless_basic MODIFY COLUMN b varchar(128)",
        # Rows differing ONLY in the column added after the table was created.
        "INSERT INTO keyless_basic(a,b,c) VALUES (9,'same',1),(9,'same',2)",
    ])
    wait_for_replication(ALL_TABLES)

    client = clickhouse_client()
    try:
        columns = {
            row[0] for row in client.execute(
                "SELECT name FROM system.columns WHERE database=%(db)s AND table='keyless_basic'",
                {"db": DATABASE},
            )
        }
    finally:
        client.disconnect()
    assert "c" in columns, (
        "ADD COLUMN never reached ClickHouse -- the sorting key froze the schema "
        f"and the ALTER was rejected and retried. Columns: {sorted(columns)}"
    )

    _compare("ADD/MODIFY COLUMN")


def test_source_column_named_row_key_is_compared(keyless_replicated):
    """A SOURCE column called _row_key must stay in the comparison.

    The generated column is recognised by the shape of its name, so over-broad
    exclusion would drop this real data column from both sides and a genuine
    divergence in it would be reported as a PASS. Asserting it is part of the
    ClickHouse checksum keeps that hole closed.
    """
    client = clickhouse_client()
    try:
        columns = {
            row[0] for row in client.execute(
                "SELECT name FROM system.columns WHERE database=%(db)s "
                "AND table='keyless_name_clash'",
                {"db": DATABASE},
            )
        }
    finally:
        client.disconnect()

    assert "_row_key" in columns, "the source column must exist in ClickHouse"
    assert "__row_key" in columns, (
        "the generated column must have been renamed out of the source column's "
        f"way. Columns: {sorted(columns)}"
    )

    # Diverge ONLY the source _row_key column. If it were being excluded, the
    # checksums would still agree and this divergence would go unreported.
    _execute(["UPDATE keyless_name_clash SET _row_key = 42 WHERE v='one'"])
    wait_for_replication(ALL_TABLES)
    _compare("source _row_key update")

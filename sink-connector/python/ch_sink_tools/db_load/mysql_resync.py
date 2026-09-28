#!/usr/bin/env python3
"""
ch-mysql-resync -- re-synchronise ClickHouse replica tables with their MySQL source after a change that never reached
the binlog (``SET sql_log_bin = 0`` data patches, ``DROP``/``CREATE``+reload refreshes, restores from a dump, physical
restores). The connector cannot see such changes, so the ClickHouse copy silently keeps the old rows: the value-level
checksum (spec 11.02) fails, and the only repair is to conform ClickHouse to MySQL again -- MySQL is the source of
truth (AGENTS.md).

The procedure is the operator's authoritative-side reinsert, made mechanical and count-reconciled:

  dump        capture ``SHOW MASTER STATUS`` (the position to rewind the connector to), then ``util.dumpTables`` every
              BASE TABLE of each schema with MySQL Shell (zstd, chunked) -- the layout ``clickhouse_loader.py
              --mysqlshell`` reads.
  patch       phase 1, every schema: schema-drift check -> ``<schema><suffix>.<table>`` created ``AS`` the live table
              and filled from the dump by the loader -> loaded rows == dump rows (exact) -> optional canary hash join
              on tables known to be unchanged. Phase 2, only if every canary passed: one ``ALTER TABLE ... REPLACE
              PARTITION`` per dump-side partition (the single ``all`` partition for unpartitioned tables) ->
              partitions that exist only in ClickHouse are LISTED, and dropped only with ``--drop-ch-only`` -> live
              row count verified against the dump.
  rewind-sql  emit the offset-table INSERT that rewinds ONE connector (one ``offset_key``) to the position captured by
              ``dump``, so that every binlogged change made while the dump and the patch ran is replayed on top of the
              patched tables.

Nothing is written to ClickHouse unless ``patch --apply`` is given: the default is a dry run that prints every write
and executes every read. The tool never issues ``DELETE``, ``TRUNCATE`` or a mutation against a live table, and it
never drops anything unless explicitly told to (``--drop-ch-only``); ``REPLACE PARTITION`` is atomic per partition.

Requirements: ``clickhouse-client``, ``zstd`` and ``mysqlsh`` on PATH (``dump`` only needs ``mysqlsh``); the loader
(``python -m ch_sink_tools.db_load.clickhouse_loader``) and its dependencies for ``patch``. Credentials: MySQL
password in ``MYSQL_PWD``; ClickHouse user/password in a clickhouse-client XML config (``--ch-config``), the same
file the loader takes.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import glob
import json
import os
import re
import shlex
import subprocess
import sys

VIRTUAL_COLUMNS = {"_version", "is_deleted", "_sign", "_is_deleted", "__is_deleted"}
DEFAULT_RESTORE_SUFFIX = "_restore"
FAILED_STATUSES = {"LOAD_FAILED", "COUNT_MISMATCH", "CANARY_FAILED", "REPLACED_VERIFY_FAIL", "DUMP_INCOMPLETE"}
LOG_FILE = None


def log(msg: str) -> None:
    line = f"{dt.datetime.now().isoformat(timespec='seconds')} {msg}"
    print(line, flush=True)
    if LOG_FILE:
        LOG_FILE.write(line + "\n")
        LOG_FILE.flush()


def q(ident: str) -> str:
    """Backtick-quote a ClickHouse identifier."""
    return "`" + ident.replace("`", "``") + "`"


def sql_str(value: str) -> str:
    """Single-quoted ClickHouse string literal."""
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


# --------------------------------------------------------------------------------------------------------------------
# Pure helpers (unit-tested in sink-connector/python/db_load/tests/test_mysql_resync.py)
# --------------------------------------------------------------------------------------------------------------------
MYSQL_TYPE_MAP = [
    (r"^tinyint\(1\)", "Int8"), (r"^tinyint.*unsigned", "UInt8"), (r"^tinyint", "Int8"),
    (r"^smallint.*unsigned", "UInt16"), (r"^smallint", "Int16"),
    (r"^mediumint.*unsigned", "UInt32"), (r"^mediumint", "Int32"),
    (r"^int.*unsigned", "UInt32"), (r"^int", "Int32"),
    (r"^bigint.*unsigned", "UInt64"), (r"^bigint", "Int64"),
    (r"^decimal\((\d+),(\d+)\)", r"Decimal(\1,\2)"), (r"^decimal\((\d+)\)", r"Decimal(\1,0)"),
    (r"^float", "Float32"), (r"^double", "Float64"),
    (r"^datetime\((\d)\)", r"DateTime64(\1)"), (r"^datetime", "DateTime64(3)"),
    (r"^timestamp\((\d)\)", r"DateTime64(\1)"), (r"^timestamp", "DateTime64(3)"),
    (r"^date$", "Date32"), (r"^time", "String"), (r"^year", "Int32"),
    (r"^(var)?char", "String"), (r"^(tiny|medium|long)?text", "String"), (r"^enum", "String"), (r"^set\(", "String"),
    (r"^(var)?binary", "String"), (r"^(tiny|medium|long)?blob", "String"), (r"^json", "String"), (r"^bit", "String"),
]


def mysql_to_ch(mysql_type: str, nullable: bool) -> str:
    """Best-effort MySQL -> ClickHouse type suggestion for a drift report (a human reviews it before applying)."""
    t = mysql_type.lower().strip()
    ch = "String"
    for pattern, replacement in MYSQL_TYPE_MAP:
        if re.match(pattern, t):
            ch = re.sub(pattern, replacement, t) if "\\" in replacement else replacement
            break
    return f"Nullable({ch})" if nullable else ch


def parse_mysql_ddl(ddl_text: str) -> list[tuple[str, str, bool, bool]]:
    """Columns of a ``CREATE TABLE`` as (name, type, nullable, generated), in order. Index/constraint lines are ignored."""
    cols = []
    in_create = False
    for raw in ddl_text.splitlines():
        line = raw.strip()
        if line.upper().startswith("CREATE TABLE"):
            in_create = True
            continue
        if not in_create:
            continue
        if line.startswith(")"):
            break
        if not line.startswith("`"):
            continue
        m = re.match(r"`((?:[^`]|``)+)`\s+(\S+(?:\([^)]*\))?(?:\s+unsigned)?(?:\s+zerofill)?)\s*(.*)$", line, re.I)
        if not m:
            continue
        name, ctype = m.group(1).replace("``", "`"), m.group(2)
        rest = re.sub(r"'(?:[^'\\]|\\.)*'", "''", m.group(3)).upper()  # a DEFAULT 'NOT NULL' literal is not a constraint
        cols.append((name, ctype, "NOT NULL" not in rest, "GENERATED ALWAYS" in rest))
    return cols


def column_drift(mysql_cols, ch_cols: dict) -> tuple[list, list]:
    """(mysql_only, ch_only): ``ch_cols`` maps name -> default_kind for the live table. MATERIALIZED/ALIAS columns are
    ClickHouse-computed and never receive source values; generated MySQL columns are not dumped by MySQL Shell."""
    writable = {n for n, kind in ch_cols.items() if kind not in ("MATERIALIZED", "ALIAS")}
    mysql_only = [c for c in mysql_cols if not c[3] and c[0] not in writable]
    ch_only = sorted(writable - VIRTUAL_COLUMNS - {c[0] for c in mysql_cols})
    return mysql_only, ch_only


def drift_ddl(database: str, table: str, mysql_cols, mysql_only) -> list[str]:
    stmts, prev = [], None
    for c in mysql_cols:
        if c in mysql_only:
            pos = f" AFTER {q(prev)}" if prev else " FIRST"
            stmts.append(f"ALTER TABLE {q(database)}.{q(table)} ADD COLUMN IF NOT EXISTS {q(c[0])} {mysql_to_ch(c[1], c[2])}{pos};"
                         f"  -- MySQL: {c[1]}{'' if c[2] else ' NOT NULL'}")
        prev = c[0]
    return stmts


def plan_replace(database: str, table: str, restore_db: str, partitioned: bool,
                 restore_partitions: dict, live_partitions: dict) -> tuple[list[str], list[str]]:
    """Return (replace_statements, ch_only_partition_ids).

    ``restore_partitions`` / ``live_partitions`` map partition_id -> row count (active parts). Unpartitioned tables
    have the single partition id ``all``; replacing it from an EMPTY restore table yields an empty live table, which
    is exactly the source state when the MySQL table is empty."""
    if not partitioned:
        if restore_partitions or live_partitions:
            return [f"ALTER TABLE {q(database)}.{q(table)} REPLACE PARTITION ID 'all' FROM {q(restore_db)}.{q(table)}"], []
        return [], []
    stmts = [f"ALTER TABLE {q(database)}.{q(table)} REPLACE PARTITION ID '{pid}' FROM {q(restore_db)}.{q(table)}"
             for pid in sorted(restore_partitions)]
    ch_only = sorted(set(live_partitions) - set(restore_partitions))
    return stmts, ch_only


def plain_identifiers(sorting_key: str):
    """The sorting key as a column list, or None when it contains expressions (then no hash join is attempted)."""
    if not sorting_key:
        return None
    parts = [p.strip() for p in sorting_key.split(",")]
    return parts if all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", p) for p in parts) else None


def rewind_offset_json(current_offset_val: str, binlog_file: str, binlog_pos: int, ts_sec: int) -> str:
    """New Debezium MySQL offset: same server_id as the current offset, positioned at the start of ``binlog_pos``."""
    cur = json.loads(current_offset_val) if current_offset_val else {}
    new = {"ts_sec": int(ts_sec), "file": binlog_file, "pos": int(binlog_pos), "row": 0, "server_id": cur.get("server_id", 0), "event": 0}
    return json.dumps(new, separators=(",", ":"))


def rewind_offset_sql(offset_table: str, offset_key: str, new_offset_val: str) -> str:
    """One connector only: the offset store is shared by every connector writing to the cluster (one row per
    ``offset_key``), so the INSERT is scoped to the given key. ReplacingMergeTree keyed by offset_key with a wall-clock
    ``_version``: the newer row wins."""
    if not offset_key:
        raise ValueError("offset_key is required: an unscoped rewind would move every connector in the offset table")
    return (f"INSERT INTO {offset_table} (id, offset_key, offset_val, record_insert_ts, record_insert_seq)\n"
            f"SELECT id, offset_key, {sql_str(new_offset_val)}, now(), record_insert_seq + 1\n"
            f"FROM {offset_table} FINAL\n"
            f"WHERE offset_key = {sql_str(offset_key)};")


def select_offset_row(rows: list[list[str]], offset_key: str | None) -> tuple[str, str]:
    """Pick exactly one (offset_key, offset_val) from the offset table: the given key, or the only row when the table
    holds a single connector. Zero or several candidates are an error, never a guess."""
    if offset_key:
        matches = [r for r in rows if r[0] == offset_key]
        if len(matches) != 1:
            raise ValueError(f"offset_key {offset_key!r} matched {len(matches)} rows; keys present: {[r[0] for r in rows]}")
        return matches[0][0], matches[0][1]
    if len(rows) == 1:
        return rows[0][0], rows[0][1]
    raise ValueError(f"offset table holds {len(rows)} connector rows; pass --offset-key, keys present: {[r[0] for r in rows]}")


def isolate_table_dir(dump_dir: str, schema: str, table: str) -> str:
    """Per-table dump directory of HARD links so the loader handles exactly one table per invocation.
    (``zstd -d --stdout <symlink>`` refuses symbolic links: "is a symbolic link, ignoring".)"""
    d = os.path.join(dump_dir, "_bytable", table)
    os.makedirs(d, exist_ok=True)
    names = [f"{schema}@{table}.sql", f"{schema}@{table}.json"]
    names += [os.path.basename(x) for x in data_files(dump_dir, schema, table)]
    for name in names:
        src, dst = os.path.join(dump_dir, name), os.path.join(d, name)
        if os.path.islink(dst):
            os.unlink(dst)
        if not os.path.exists(dst) and os.path.exists(src):
            os.link(src, dst)
    return d


def data_files(dump_dir: str, schema: str, table: str) -> list[str]:
    return sorted(glob.glob(os.path.join(dump_dir, f"{schema}@{table}@*.tsv.zst")) +
                  glob.glob(os.path.join(dump_dir, f"{schema}@{table}.tsv.zst")))


def dump_tables(dump_dir: str, schema: str) -> list[str]:
    return sorted(re.sub(r"\.sql$", "", os.path.basename(f)).split("@", 1)[1]
                  for f in glob.glob(os.path.join(dump_dir, f"{schema}@*.sql")))


def exact_dump_rows(files: list[str]) -> int:
    """Exact row count of a MySQL Shell TSV dump: one newline-terminated line per row (embedded newlines are escaped)."""
    total = 0
    for f in files:
        p = subprocess.run(f"zstd -dc {shlex.quote(f)} | wc -l", shell=True, capture_output=True, text=True)
        if p.returncode != 0:
            raise RuntimeError(f"zstd/wc failed on {f}: {p.stderr.strip()[:300]}")
        total += int(p.stdout.strip() or 0)
    return total


# --------------------------------------------------------------------------------------------------------------------
# ClickHouse / MySQL Shell wrappers
# --------------------------------------------------------------------------------------------------------------------
class ClickHouse:
    def __init__(self, host: str, config: str, apply: bool, port: int = 9000):
        self.host, self.config, self.apply, self.port = host, config, apply, port

    def _run(self, sql: str, timeout: int) -> str:
        cmd = ["clickhouse-client", "--config-file", self.config, "--host", self.host, "--port", str(self.port),
               "--format", "TSV", "--max_execution_time", str(timeout), "--query", sql]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout + 60)
        if p.returncode != 0:
            raise RuntimeError(f"clickhouse-client rc={p.returncode}: {p.stderr.strip()[:1500]}\nSQL: {sql[:400]}")
        return p.stdout

    def rows(self, sql: str, timeout: int = 3600) -> list[list[str]]:
        return [l.split("\t") for l in self._run(sql, timeout).split("\n") if l != ""]

    def one(self, sql: str, timeout: int = 3600):
        r = self.rows(sql, timeout)
        return r[0][0] if r and r[0] else None

    def write(self, sql: str, timeout: int = 3600):
        if not self.apply:
            log(f"[DRY-RUN] would execute: {sql}")
            return None
        log(f"[APPLY] {sql}")
        return self._run(sql, timeout)


def mysqlsh_run(mysqlsh: str, uri: str, password: str, mode_args: list[str], stdin_extra: str = "", timeout: int = None):
    """Run mysqlsh with the password on stdin (``--passwords-from-stdin``), never on the command line."""
    cmd = [mysqlsh, "--passwords-from-stdin", "--uri", uri] + mode_args
    p = subprocess.run(cmd, input=password + "\n" + stdin_extra, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def capture_binlog_position(mysqlsh: str, uri: str, password: str) -> dict:
    rc, out, err = mysqlsh_run(mysqlsh, uri, password, ["--sql", "--result-format=json/raw", "-e",
                               "SELECT NOW() AS taken_at, @@hostname AS source_host, UNIX_TIMESTAMP() AS ts_sec; SHOW MASTER STATUS;"])
    if rc != 0:
        raise RuntimeError(f"mysqlsh SHOW MASTER STATUS failed rc={rc}: {err.strip()[:500]}")
    docs = [json.loads(l) for l in out.splitlines() if l.startswith("{")]
    meta = next((d for d in docs if "taken_at" in d), {})
    status = next((d for d in docs if "File" in d), None)
    if not status:
        raise RuntimeError("SHOW MASTER STATUS returned no row (binary logging disabled or missing privilege)")
    return {"taken_at": meta.get("taken_at"), "source_host": meta.get("source_host"), "ts_sec": int(meta.get("ts_sec", 0)),
            "file": status["File"], "pos": int(status["Position"]), "gtid_executed": status.get("Executed_Gtid_Set", "")}


DUMP_JS = r"""
var schema = os.getenv("RESYNC_SCHEMA"), dir = os.getenv("RESYNC_DIR");
var threads = parseInt(os.getenv("RESYNC_THREADS") || "8");
var consistent = (os.getenv("RESYNC_CONSISTENT") || "false") === "true";
var tableRe = new RegExp(os.getenv("RESYNC_TABLES") || ".*");
var res = session.runSql("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' ORDER BY TABLE_NAME", [schema]);
var tables = [], row;
while ((row = res.fetchOne())) { if (tableRe.test(row[0])) tables.push(row[0]); }
println("schema=" + schema + " tables=" + tables.length + " dir=" + dir + " threads=" + threads + " consistent=" + consistent);
util.dumpTables(schema, tables, dir, {threads: threads, consistent: consistent, compression: "zstd", chunking: true,
                                      bytesPerChunk: "256M", triggers: false, showProgress: false, defaultCharacterSet: "utf8mb4"});
println("DONE schema=" + schema + " tables=" + tables.length);
"""


# --------------------------------------------------------------------------------------------------------------------
# Sub-commands
# --------------------------------------------------------------------------------------------------------------------
def cmd_dump(args) -> int:
    password = os.environ.get("MYSQL_PWD")
    if not password:
        sys.exit("MYSQL_PWD must be set (the password is passed to mysqlsh on stdin, never on the command line)")
    os.makedirs(args.dump_base, exist_ok=True)
    pos_path = os.path.join(args.dump_base, f"binlog_position_{args.stamp}.json")
    if os.path.isfile(pos_path):
        log(f"binlog position already captured: {pos_path} (kept: it must predate the FIRST table read)")
    else:
        pos = capture_binlog_position(args.mysqlsh, args.mysql_uri, password)
        with open(pos_path, "w") as f:
            json.dump(pos, f, indent=2)
        log(f"binlog position at dump start: {pos['file']}:{pos['pos']} ({pos['taken_at']} on {pos['source_host']}) -> {pos_path}")
    rc_all = 0
    for schema in args.schemas:
        d = os.path.join(args.dump_base, f"{schema}_{args.stamp}")
        if os.path.isfile(os.path.join(d, "@.done.json")):
            log(f"SKIP {schema}: already complete ({d})")
            continue
        if os.path.isdir(d):
            log(f"ERROR {schema}: {d} exists but is incomplete -- move it away and rerun")
            rc_all = 1
            continue
        env = dict(os.environ, RESYNC_SCHEMA=schema, RESYNC_DIR=d, RESYNC_THREADS=str(args.threads),
                   RESYNC_CONSISTENT=str(args.consistent).lower(), RESYNC_TABLES=args.tables)
        log(f"DUMP {schema} -> {d}")
        cmd = [args.mysqlsh, "--passwords-from-stdin", "--uri", args.mysql_uri, "--js", "-e", DUMP_JS]
        p = subprocess.run(cmd, input=password + "\n", capture_output=True, text=True, env=env)
        with open(os.path.join(args.dump_base, f"dump_{schema}_{args.stamp}.log"), "a") as lf:
            lf.write(p.stdout + "\n" + p.stderr)
        if p.returncode != 0 or not os.path.isfile(os.path.join(d, "@.done.json")):
            log(f"FAILED {schema} rc={p.returncode}: {p.stderr.strip()[-400:]}")
            rc_all = 1
        else:
            log(f"OK {schema}")
    return rc_all


def loader_command(args) -> list[str]:
    if args.loader_cmd:
        return shlex.split(args.loader_cmd)
    return [sys.executable, "-m", "ch_sink_tools.db_load.clickhouse_loader"]


def run_loader(args, restore_db: str, schema: str, table: str, table_dir: str, logdir: str) -> tuple[int, str]:
    # NEVER pass --truncate_tables to the loader: it truncates <mysql_source_database>.<table>, i.e. the LIVE table.
    cmd = loader_command(args) + ["--mysqlshell", "--data_only", "--rmt_delete_support",
                                  "--clickhouse_host", args.ch_host, "--clickhouse_port", str(args.ch_port),
                                  "--clickhouse_database", restore_db, "--mysql_source_database", schema,
                                  "--dump_dir", table_dir, "--threads", str(args.load_threads),
                                  "--clickhouse_config_file", args.ch_config]
    if not args.apply:
        cmd.append("--dry_run")
    pkg_root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    env = dict(os.environ, PYTHONPATH=pkg_root + os.pathsep + os.environ.get("PYTHONPATH", ""))
    logpath = os.path.join(logdir, f"load_{schema}.{table}.log")
    with open(logpath, "w") as lf:
        p = subprocess.run(cmd, cwd=args.loader_cwd or pkg_root, env=env, stdout=lf, stderr=subprocess.STDOUT, text=True)
    return p.returncode, logpath


def column_names(ch: ClickHouse, schema: str, table: str) -> set:
    return {r[0] for r in ch.rows(f"SELECT name FROM system.columns WHERE database='{schema}' AND table='{table}'")}


def canary_ratio(ch: ClickHouse, schema: str, restore_db: str, table: str, sorting_key: str):
    """(identical, joined) rows between the scratch and live copies, or None when the sorting key is not plain columns."""
    pk = plain_identifiers(sorting_key)
    if not pk:
        return None
    pkl = ", ".join(q(c) for c in pk)
    has_del = "is_deleted" in column_names(ch, schema, table)
    exc = "(_version, is_deleted)" if has_del else "(_version)"
    where = "WHERE is_deleted = 0" if has_del else ""
    r = ch.rows(f"SELECT countIf(r.h = l.h), count() FROM (SELECT {pkl}, cityHash64(* EXCEPT {exc}) AS h FROM {q(restore_db)}.{q(table)}) AS r "
                f"INNER JOIN (SELECT {pkl}, cityHash64(* EXCEPT {exc}) AS h FROM {q(schema)}.{q(table)} FINAL {where}) AS l USING ({pkl}) "
                f"SETTINGS join_algorithm = 'parallel_hash'")
    return int(r[0][0]), int(r[0][1])


def cmd_patch(args) -> int:
    global LOG_FILE
    if not os.path.isfile(args.ch_config):
        sys.exit(f"ClickHouse client config not found: {args.ch_config}")
    ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    outdir = os.path.join(args.dump_base, f"patch_{args.stamp}")
    os.makedirs(outdir, exist_ok=True)
    LOG_FILE = open(os.path.join(outdir, f"patch_{ts}.log"), "a")
    report_path = os.path.join(outdir, f"report_{ts}.tsv")
    drift_path = os.path.join(outdir, f"schema_drift_{ts}.sql")
    drop_path = os.path.join(outdir, f"drop_ch_only_{ts}.sql")
    ch = ClickHouse(args.ch_host, args.ch_config, args.apply, args.ch_port)
    mode = "APPLY" if args.apply else "DRY-RUN"
    log(f"== ch-mysql-resync patch mode={mode} host={args.ch_host} dump_base={args.dump_base} stamp={args.stamp} out={outdir}")
    log(f"ClickHouse {ch.one('SELECT version()')} on {ch.one('SELECT hostName()')}")
    canary = set()
    if args.canary_list:
        canary = {l.split("\t")[0].strip() for l in open(args.canary_list) if l.strip()}
        log(f"canary list: {args.canary_list} ({len(canary)} tables)")
    table_re = re.compile(args.tables)
    report: list[list[str]] = []
    drift_sql = [f"-- schema drift found by ch-mysql-resync {ts}: MySQL-only columns. REVIEW the suggested types, apply, re-run for these tables."]
    drop_sql = [f"-- ClickHouse-only partitions/tables found by ch-mysql-resync {ts}.",
                "-- DESTRUCTIVE: every statement below removes data that exists in ClickHouse but NOT in the MySQL dump",
                "-- (MySQL is the source of truth). Nothing here is executed unless the tool runs with --drop-ch-only.",
                "-- Blast radius: exactly the enumerated partition ids / tables, one statement each."]
    canary_hits = canary_total = 0
    ready: list[tuple[str, str, str, list, int]] = []   # (schema, restore_db, table, live_row, dump_rows) cleared for REPLACE

    def set_status(schema, table, status, extra=()):
        for row in report:
            if row[0] == schema and row[1] == table:
                row[2] = status
                row.extend(extra)
                return

    # ---------------------------------------------------------------- phase 1: every schema loaded and reconciled
    for schema in args.schemas:
        restore_db = f"{schema}{args.restore_suffix}"
        dump_dir = os.path.join(args.dump_base, f"{schema}_{args.stamp}")
        if not os.path.isfile(os.path.join(dump_dir, "@.done.json")):
            log(f"!! {schema}: dump not complete ({dump_dir}/@.done.json missing) -> schema skipped")
            report.append([schema, "*", "DUMP_INCOMPLETE"])
            continue
        all_tables = dump_tables(dump_dir, schema)
        tables = [t for t in all_tables if table_re.search(t)]
        log(f"== {schema}: {len(tables)}/{len(all_tables)} tables selected from {dump_dir}")
        live = {r[0]: r for r in ch.rows(f"SELECT name, engine, partition_key, sorting_key, total_rows FROM system.tables WHERE database = '{schema}'")}
        for t in sorted(set(live) - set(all_tables)):
            if live[t][1] != "View":
                # DESTRUCTIVE: emitted as a COMMENTED-OUT statement in the drop file only; this tool never executes a
                # DROP TABLE -- a live table absent from the source is the operator's decision, one table per line.
                drop_sql += [f"-- DESTRUCTIVE (never executed by this tool, human decision): table exists only in ClickHouse, rows={live[t][4]}",
                             f"-- DROP TABLE {q(schema)}.{q(t)};"]
        ch.write(f"CREATE DATABASE IF NOT EXISTS {q(restore_db)}")

        todo = []
        for t in tables:
            if t not in live:
                report.append([schema, t, "NOT_IN_CH"])
                log(f"   {schema}.{t}: not in ClickHouse -> skipped")
                continue
            if live[t][1] != "ReplacingMergeTree":
                report.append([schema, t, f"ENGINE_{live[t][1]}"])
                log(f"   {schema}.{t}: engine {live[t][1]} -> skipped")
                continue
            mcols = parse_mysql_ddl(open(os.path.join(dump_dir, f"{schema}@{t}.sql"), encoding="utf-8", errors="replace").read())
            ccols = {r[0]: r[1] for r in ch.rows(f"SELECT name, default_kind FROM system.columns WHERE database='{schema}' AND table='{t}' ORDER BY position")}
            mysql_only, ch_only_cols = column_drift(mcols, ccols)
            if mysql_only:
                drift_sql += drift_ddl(schema, t, mcols, mysql_only)
                report.append([schema, t, "SCHEMA_DRIFT", ",".join(c[0] for c in mysql_only)])
                log(f"   {schema}.{t}: SCHEMA DRIFT, MySQL-only columns {[c[0] for c in mysql_only]} -> skipped (see {drift_path})")
                continue
            if ch_only_cols:
                log(f"   {schema}.{t}: ClickHouse-only columns {ch_only_cols} (kept, filled with defaults)")
            todo.append(t)
            if not args.skip_load:
                # DESTRUCTIVE: drops ONLY the scratch copy <schema><suffix>.<table> (never the live table) so it is
                # recreated with the live table's exact current structure before being refilled from the dump.
                ch.write(f"DROP TABLE IF EXISTS {q(restore_db)}.{q(t)}")
                ch.write(f"CREATE TABLE {q(restore_db)}.{q(t)} AS {q(schema)}.{q(t)}")

        dump_rows, load_rc = {}, {}

        def one_table(t):
            d = isolate_table_dir(dump_dir, schema, t)
            n = exact_dump_rows(data_files(d, schema, t))
            if args.skip_load:
                return t, n, 0, None
            rc, lp = run_loader(args, restore_db, schema, t, d, outdir)
            return t, n, rc, lp

        if todo:
            log(f"   loading {len(todo)} tables into {restore_db} ({args.load_parallel} tables x {args.load_threads} streams)")
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.load_parallel) as ex:
            for t, n, rc, lp in ex.map(one_table, todo):
                dump_rows[t], load_rc[t] = n, rc
                log(f"   {schema}.{t}: dump_rows={n} loader_rc={rc} log={lp}")

        for t in todo:
            if load_rc[t] != 0:
                report.append([schema, t, "LOAD_FAILED", str(dump_rows[t])])
                continue
            if not args.apply and not args.skip_load:
                report.append([schema, t, "DRY_RUN", str(dump_rows[t])])
                ready.append((schema, restore_db, t, live[t], dump_rows[t]))
                continue
            rcount = int(ch.one(f"SELECT count() FROM {q(restore_db)}.{q(t)}") or 0)
            if rcount != dump_rows[t]:
                report.append([schema, t, "COUNT_MISMATCH", str(dump_rows[t]), str(rcount)])
                log(f"   !! {schema}.{t}: restore rows {rcount} != dump rows {dump_rows[t]} -> FAILED, no REPLACE")
                continue
            ratio = ""
            if f"{schema}.{t}" in canary:
                res = canary_ratio(ch, schema, restore_db, t, live[t][3])
                if res:
                    same, n = res
                    canary_hits, canary_total = canary_hits + same, canary_total + n
                    ratio = f"{same}/{n}"
                    log(f"   canary {schema}.{t}: identical rows {same}/{n}")
            ready.append((schema, restore_db, t, live[t], dump_rows[t]))
            report.append([schema, t, "LOADED", str(dump_rows[t]), str(rcount), ratio])

    # ---------------------------------------------------------------- canary gate: evaluated once, before ANY replace
    canary_failed = bool(canary_total) and canary_hits / canary_total < args.canary_threshold
    if canary_total:
        log(f"== canary overall: {canary_hits}/{canary_total} = {canary_hits / canary_total:.5f} (threshold {args.canary_threshold})")
    if canary_failed and not args.force:
        log("!! CANARY FAILED: the loader renders values differently from the connector. No REPLACE was issued for any table; "
            "the scratch tables are kept for inspection. Do NOT rewind the connector. Re-run with --force only once the difference is understood.")
        for schema, _, t, _, _ in ready:
            set_status(schema, t, "CANARY_FAILED")
        ready = []

    # ---------------------------------------------------------------- phase 2: replace, list replica-only partitions, verify
    for schema, restore_db, t, live_row, drows in ready:
        partitioned = live_row[2] != ""
        lparts = {r[0]: int(r[1]) for r in ch.rows(f"SELECT partition_id, sum(rows) FROM system.parts WHERE database='{schema}' AND table='{t}' AND active GROUP BY partition_id")}
        before = sum(lparts.values())
        if not args.apply and not args.skip_load:
            # The REPLACE plan is derived from the scratch table's partitions, which only exist after a real load:
            # a dry run without --skip-load cannot enumerate them, so it reports the live side and stops here.
            log(f"   {schema}.{t}: DRY_RUN live_partitions={len(lparts)} live_rows={before} dump_rows={drows} "
                f"(REPLACE plan is computed from the scratch table after the load; use --skip-load on a loaded scratch table for the exact plan)")
            set_status(schema, t, "DRY_RUN", ["n/a", "n/a", str(before), ""])
            continue
        rparts = {r[0]: int(r[1]) for r in ch.rows(f"SELECT partition_id, sum(rows) FROM system.parts WHERE database='{restore_db}' AND table='{t}' AND active GROUP BY partition_id")}
        stmts, ch_only = plan_replace(schema, t, restore_db, partitioned, rparts, lparts)
        for s in stmts:
            ch.write(s)
        for pid in ch_only:
            # DESTRUCTIVE: drops exactly ONE enumerated partition id that exists in ClickHouse but not in the MySQL
            # dump; written to the drop file always, executed ONLY with --drop-ch-only (default: never executed).
            drop_sql.append(f"-- DESTRUCTIVE: partition id {pid} (rows={lparts[pid]}) exists only in ClickHouse; the MySQL dump has no rows for it")
            stmt = f"ALTER TABLE {q(schema)}.{q(t)} DROP PARTITION ID '{pid}'"
            drop_sql.append(stmt + ";")
            if args.drop_ch_only:
                ch.write(stmt)
        status, after = ("DRY_RUN_PLANNED", None)
        if args.apply:
            after = int(ch.one(f"SELECT count() FROM {q(schema)}.{q(t)}") or 0)
            expect = drows + (0 if args.drop_ch_only else sum(lparts[p] for p in ch_only))
            status = "REPLACED_OK" if after == expect else "REPLACED_VERIFY_FAIL"
        log(f"   {schema}.{t}: {status} partitions_replaced={len(stmts)} ch_only_partitions={len(ch_only)} before={before} after={after} dump_rows={drows}")
        set_status(schema, t, status, [str(len(stmts)), str(len(ch_only)), str(before), str(after)])

    columns = ["schema", "table", "status", "dump_rows", "restore_rows", "canary", "partitions_replaced", "ch_only_partitions", "live_before", "live_after"]
    with open(report_path, "w") as f:
        f.write("\t".join(columns) + "\n")
        f.write("".join("\t".join((r + [""] * len(columns))[:len(columns)]) + "\n" for r in report))
    open(drift_path, "w").write("\n".join(drift_sql) + "\n")
    open(drop_path, "w").write("\n".join(drop_sql) + "\n")
    counts = {}
    for r in report:
        counts[r[2]] = counts.get(r[2], 0) + 1
    log(f"== done mode={mode}: {counts}")
    log(f"   report: {report_path}\n   drift (human applies): {drift_path}\n   ch-only (DESTRUCTIVE, human decision): {drop_path}")
    # In apply mode every SELECTED table must have been repaired: a table skipped for drift, missing in ClickHouse or
    # on another engine is NOT repaired, so it is a failure too -- an operator must never be told to rewind on it.
    unrepaired = {"SCHEMA_DRIFT", "NOT_IN_CH"} if args.apply else set()
    failed = any(r[2] in FAILED_STATUSES or r[2] in unrepaired or (args.apply and r[2].startswith("ENGINE_")) for r in report)
    if failed:
        log("== FAILED: at least one selected table did not reach REPLACED_OK -- do not rewind the connector until every table is repaired "
            "(apply the drift DDL / exclude the table with --tables and re-run).")
    else:
        log("   next: `ch-mysql-resync rewind-sql` -> stop the connector, run the INSERT, start it, then re-run the checksum job.")
    LOG_FILE.close()
    LOG_FILE = None
    return 1 if failed else 0


def cmd_rewind_sql(args) -> int:
    pos = json.load(open(args.position_file or os.path.join(args.dump_base, f"binlog_position_{args.stamp}.json")))
    rows = []
    if args.ch_config and os.path.isfile(args.ch_config):
        ch = ClickHouse(args.ch_host, args.ch_config, apply=False, port=args.ch_port)
        rows = ch.rows(f"SELECT offset_key, offset_val FROM {args.offset_table} FINAL ORDER BY offset_key")
    elif not args.offset_key:
        sys.exit("--offset-key is required when the current offset table cannot be read (--ch-host/--ch-config not given)")
    if rows:
        offset_key, current = select_offset_row(rows, args.offset_key)
    else:
        offset_key, current = args.offset_key, ""
    new_val = rewind_offset_json(current, pos["file"], pos["pos"], pos.get("ts_sec") or 0)
    sql = rewind_offset_sql(args.offset_table, offset_key, new_val)
    print(f"-- Rewind the connector whose offset_key is {offset_key} in {args.offset_table} to the binlog position captured at dump start:")
    print(f"--   {pos['file']}:{pos['pos']} taken {pos.get('taken_at')} on {pos.get('source_host')} (gtid_executed: {pos.get('gtid_executed') or 'n/a'})")
    print("-- Procedure: (1) stop THAT connector service; (2) run the INSERT below; (3) start the connector; (4) watch it")
    print("-- replay the binlog from that position (every change binlogged since the dump is re-applied on top of the")
    print("-- patched tables -- idempotent on ReplacingMergeTree); (5) re-run the value-level checksum (spec 11.02).")
    print("-- The source must still hold that binlog file: check `SHOW BINARY LOGS` before starting the connector.")
    if rows:
        print(f"-- offset rows in {args.offset_table}: {len(rows)}; current offset_val of the selected key: {current}")
    print(sql)
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="ch-mysql-resync", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--dump-base", required=True, help="directory holding <schema>_<stamp>/ dumps and binlog_position_<stamp>.json")
        p.add_argument("--stamp", default=dt.date.today().strftime("%Y%m%d"), help="dump set identifier (default: today YYYYMMDD)")

    d = sub.add_parser("dump", help="capture the binlog position, then mysqlsh util.dumpTables of every BASE TABLE per schema")
    common(d)
    d.add_argument("--mysql-uri", required=True, help="user@host:port (password from MYSQL_PWD)")
    d.add_argument("--schemas", nargs="+", required=True)
    d.add_argument("--tables", default=".*", help="regex on table names")
    d.add_argument("--threads", type=int, default=8)
    d.add_argument("--consistent", action="store_true", help="mysqlsh consistent snapshot (long REPEATABLE READ transactions on the source); "
                                                           "default off -- the recorded binlog position + connector rewind gives the same guarantee")
    d.add_argument("--mysqlsh", default="mysqlsh")
    d.set_defaults(func=cmd_dump)

    p = sub.add_parser("patch", help="conform the ClickHouse tables to the dump (dry run unless --apply)")
    common(p)
    p.add_argument("--schemas", nargs="+", required=True, help="ClickHouse databases == MySQL schemas dumped")
    p.add_argument("--tables", default=".*", help="regex on table names")
    p.add_argument("--ch-host", required=True)
    p.add_argument("--ch-port", type=int, default=9000)
    p.add_argument("--ch-config", required=True, help="clickhouse-client XML config with <user>/<password> (also given to the loader)")
    p.add_argument("--apply", action="store_true", help="execute writes (default: dry run)")
    p.add_argument("--drop-ch-only", action="store_true", help="also DROP partitions that exist only in ClickHouse (DESTRUCTIVE)")
    p.add_argument("--restore-suffix", default=DEFAULT_RESTORE_SUFFIX, help="scratch database = <schema><suffix>")
    p.add_argument("--load-parallel", type=int, default=2, help="tables loaded concurrently")
    p.add_argument("--load-threads", type=int, default=4, help="insert streams per table")
    p.add_argument("--skip-load", action="store_true", help="scratch tables already loaded; reconcile/replace only")
    p.add_argument("--canary-list", default=None, help="file of <schema.table> known to be unchanged: they must hash-match after the load")
    p.add_argument("--canary-threshold", type=float, default=0.99)
    p.add_argument("--force", action="store_true", help="continue to REPLACE even if the canary check fails")
    p.add_argument("--loader-cmd", default=None, help="override the loader command (default: python -m ch_sink_tools.db_load.clickhouse_loader)")
    p.add_argument("--loader-cwd", default=None)
    p.set_defaults(func=cmd_patch)

    r = sub.add_parser("rewind-sql", help="print the offset-table INSERT that rewinds ONE connector to the captured binlog position")
    common(r)
    r.add_argument("--offset-table", required=True, help="e.g. altinity_sink_connector.replica_source_info")
    r.add_argument("--offset-key", default=None, help="the connector's offset_key row; may be omitted only when the table holds a single row")
    r.add_argument("--position-file", default=None, help="override <dump-base>/binlog_position_<stamp>.json (one dump set = one stamp = one position; "
                                                        "use distinct stamps for distinct connectors)")
    r.add_argument("--ch-host", default=None)
    r.add_argument("--ch-port", type=int, default=9000)
    r.add_argument("--ch-config", default=None, help="if given, the current offset rows are read (server_id kept, key checked)")
    r.set_defaults(func=cmd_rewind_sql)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

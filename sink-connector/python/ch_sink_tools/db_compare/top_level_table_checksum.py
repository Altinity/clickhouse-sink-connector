#!/usr/bin/env python3
import yaml
import sys
import os
import argparse
import logging
from ch_sink_tools.db.mysql import *
from ch_sink_tools.db.checksum_common import (validate_timezone, JSON_COLUMNS_HINT_RE, DATETIME_MIN, DATETIME_MAX,
                                 DATETIME64_MIN_UTC, DATETIME64_MAX_UTC, canonical_datetime_bound)
import concurrent.futures
import functools
import json
import math
import threading
from ch_sink_tools.db.clickhouse import (clickhouse_connection, clickhouse_execute_conn,
                           resolve_credentials_from_config as clickhouse_credentials)
from datetime import datetime
from subprocess import Popen, PIPE
import subprocess
import time
import re
import shlex
import traceback

# The directory holding the ch_sink_tools package this driver was imported
# from. The side scripts are run as `<this interpreter> -m
# ch_sink_tools.db_compare.<side>` with it first on PYTHONPATH, so they are the
# packaged modules of the same installation whatever the cwd (spec 13.06
# FM-13.06-1).
PACKAGE_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MYSQL_SIDE_MODULE = "ch_sink_tools.db_compare.mysql_table_checksum"
CLICKHOUSE_SIDE_MODULE = "ch_sink_tools.db_compare.clickhouse_table_checksum"


class LockAcquisitionError(Exception):
    """Raised when the source READ lock for a single table cannot be acquired
    within lock_wait_timeout. Treated as a skippable, non-fatal condition so a
    continuously-written table (e.g. a hot table on a busy replication target)
    does not abort the checksum run for every other table."""
    pass


def _mysql_error_code(exc):
    """Extract the numeric MySQL error code from a driver exception.

    pymysql.err.OperationalError carries it as args[0]; SQLAlchemy wraps the
    original driver exception under .orig. Returns None if no integer code is
    present."""
    for candidate in (exc, getattr(exc, "orig", None)):
        if candidate is None:
            continue
        args = getattr(candidate, "args", None)
        if args and isinstance(args[0], int):
            return args[0]
    return None


def _is_lock_wait_timeout(exc):
    """True only for MySQL 'Lock wait timeout exceeded' (errno 1205).

    Classified from the structured driver error code first (whether raw
    pymysql.OperationalError or SQLAlchemy-wrapped), falling back to the exact
    MySQL timeout message. Deliberately does NOT do a loose "1205" substring
    match: an unrelated error on a table like `trades_1205`, or any message that
    merely contains those digits, must not be misclassified as a skippable lock
    timeout and silently swallowed."""
    if _mysql_error_code(exc) == 1205:
        return True
    return "Lock wait timeout exceeded" in str(exc)


def parse_config(config_file):
    """Parse the YAML configuration file."""
    try:
        with open(config_file, 'r') as file:
            config = yaml.safe_load(file)
        return config
    except FileNotFoundError:
        logging.error(f"Error: Configuration file '{config_file}' not found.")
        sys.exit(1)
    except yaml.YAMLError as e:
        logging.error(f"Error parsing YAML file: {e}")
        sys.exit(1)
    except Exception as e:
        logging.error(f"Unexpected error: {e}")
        sys.exit(1)


def validate_config(config):
    """Validate the configuration structure."""
    try:
        # Check source section
        source = config['source']
        mysql = source['mysql']
        host = mysql['host'] 
        # Check replicas section
        replicas = config['replicas']
        if not isinstance(replicas, list):
            logging.error("Error: 'replicas' must be a list")
            return False
            
        for i, replica in enumerate(replicas):
            if 'clickhouse' not in replica:
                logging.error(f"Error: 'clickhouse' missing in replica {i+1}")
                return False
            if 'host' not in replica['clickhouse']:
                logging.error(f"Error: 'host' missing in replica {i+1}")
                return False
                
        return True
    except KeyError as e:
        logging.error(f"Error: Missing required configuration key: {e}")
        return False


# The one line a side script prints per table. Both sides log it at INFO with
# the format '%(asctime)s - %(levelname)s - %(threadName)s - %(message)s'. It is
# matched per line and anchored on the whole message, so a database, table or
# column name containing "checksum" in any other log line is never taken for a
# result (spec 13.06 FM-13.06-2).
CHECKSUM_LINE = re.compile(r" - INFO - .* - Checksum for table (?P<table>\S.*) = (?P<checksum>[0-9a-f]{32}) count (?P<count>\d+)\s*$")
# A side ERROR or CRITICAL line means the side did not produce a trustworthy
# result: it is relayed at ERROR and the table gets no verdict. A side WARNING
# (columns not compared, clamped values, SQL or client-library warnings)
# qualifies the verdict without failing it: it is relayed at INFO as a "side
# note", without the level word. WARNING in the driver log is reserved for
# "Checksum difference" -- scheduled jobs fail on any line containing WARNING
# (spec 13.06 section 3.17).
SIDE_ERROR_MARKERS = (" - CRITICAL - ", " - ERROR - ")
SIDE_WARNING_MARKER = " - WARNING - "
SIDE_OUTPUT_TAIL_LINES = 20

VERDICT_MATCH = "MATCH"
VERDICT_DIFFERENT = "DIFFERENT"
VERDICT_EMPTY = "EMPTY"
VERDICT_ERROR = "ERROR"


def side_output_text(data):
    if data is None:
        return ""
    if isinstance(data, bytes):
        return data.decode('utf-8', errors='replace')
    return str(data)


def parse_checksum(data, table, expected_name=None):
    """(name, md5, count) from a side script's raw output, else (name, None, None).

    Exactly one ``Checksum for table`` line must be present. Zero or several,
    or a line naming another table than ``expected_name`` (``<db>.<table>``),
    is an invalid output, never a result."""
    lines = side_output_text(data).splitlines()
    matches = [match for match in (CHECKSUM_LINE.search(line) for line in lines) if match]
    if len(matches) != 1:
        logging.error(f"Invalid checksum output for table {table}: expected exactly one "
                      f"'Checksum for table' line, found {len(matches)}")
        return (table, None, None)
    match = matches[0]
    name = match.group('table')
    if expected_name is not None and name != expected_name:
        logging.error(f"Invalid checksum output for table {table}: the side reported {name}, expected {expected_name}")
        return (name, None, None)
    return (name, match.group('checksum'), int(match.group('count')))


def side_note_text(line):
    """A side output line without the word WARNING (its level or any other
    occurrence), as the driver relays or dumps it below WARNING. The MySQL
    side's hint to pass --json_columns to the ClickHouse side is dropped: the
    driver forwards that list itself (spec 13.06 D-13.06-41)."""
    text = JSON_COLUMNS_HINT_RE.sub("", line.strip())
    return text.replace(SIDE_WARNING_MARKER, " - ").replace("WARNING", "warning")


# The replica side's report of columns the destination has and the source
# table does not (spec 11.02 sections 3.3 and 3.9).
REPLICA_ONLY_RE = re.compile(r"Replica-only columns in table (?P<table>\S+): (?P<columns>\[.*?\])")
# (replica host, <db>.<table>) -> the column list as the side printed it.
# Filled while the run's tables are compared, read by report_run_summary().
replica_only_findings = {}
replica_only_lock = threading.Lock()


def allow_replica_only_columns():
    """--allow_replica_only_columns: kept for drop-in compatibility
    (Invariant I11); it is a no-op. Replica-only columns are always tolerated
    (spec 11.02 section 3.9, CONSTITUTION.md "Parity scope")."""
    return bool(getattr(globals().get("args"), "allow_replica_only_columns", False))


def record_replica_only(host, line):
    """True when ``line`` is the replica side's replica-only report. Each
    (host, table) is recorded and logged once per run, at INFO, as tolerated:
    a column that exists only on the ClickHouse destination is out of parity
    scope (Invariant I6 "Parity scope", `specs/CONSTITUTION.md`) and is never
    a WARNING or a reason to fail the run, regardless of
    --allow_replica_only_columns (kept as an accepted no-op, Invariant I11).
    The columns are left out of the comparison either way, so the table's
    verdict is about the replicated columns only."""
    match = REPLICA_ONLY_RE.search(line)
    if not match:
        return False
    key = (host, match.group('table'))
    columns = match.group('columns')
    with replica_only_lock:
        first = key not in replica_only_findings
        replica_only_findings[key] = columns
    if not first:
        return True
    logging.info(f"{host} {key[1]} side note: replica-only columns {columns} tolerated "
                 "(present on the ClickHouse destination, absent from the source table; not compared)")
    return True


def relay_side_messages(data, host, table):
    """Re-log the side script's ERROR/CRITICAL lines at ERROR and its WARNING
    lines (columns not compared, clamped values, SQL warnings) at INFO as side
    notes: they qualify the verdict (spec 13.06 FM-13.06-8). The replica-only
    report is the exception, checked first regardless of its own level: it is
    always tolerated (spec 11.02 section 3.9), so record_replica_only() logs
    it at INFO whether the side emitted it at INFO or WARNING. Returns True
    when the side logged an ERROR or CRITICAL line."""
    side_failed = False
    for line in side_output_text(data).splitlines():
        if any(marker in line for marker in SIDE_ERROR_MARKERS):
            logging.error(f"{host} {table} side: {line.strip()}")
            side_failed = True
        elif record_replica_only(host, line):
            continue
        elif SIDE_WARNING_MARKER in line:
            logging.info(f"{host} {table} side note: {side_note_text(line)}")
    return side_failed


def log_side_output_tail(data, host, table):
    for line in side_output_text(data).splitlines()[-SIDE_OUTPUT_TAIL_LINES:]:
        logging.error(f"{host} {table} side output: {line}")


def command_text(cmd):
    return shlex.join(cmd) if isinstance(cmd, (list, tuple)) else str(cmd)


def expected_side_name(cmd, table):
    """``<database>.<table>`` a side command asks for, or None when ``cmd`` is
    not an argv list naming a database."""
    if not isinstance(cmd, (list, tuple)):
        return None
    for flag in ("--mysql_database", "--clickhouse_database"):
        if flag in cmd and cmd.index(flag) + 1 < len(cmd):
            return f"{cmd[cmd.index(flag) + 1]}.{table}"
    return None


def run_quick_safe_checksum(cmd, host, table, with_output=False):
    """One side's (host, table, md5, count), or None when it failed. With
    ``with_output`` returns (result, raw output)."""
    start = time.perf_counter()
    (rc, stdout) = run_quick_safe_command(cmd)
    result = side_result(cmd, host, table, rc, stdout, time.perf_counter() - start)
    return (result, stdout) if with_output else result


def run_snapshot_side(cmd, host, table, on_position):
    """Run the MySQL side under --consistent_snapshot --exclude_keys_from_stdin.

    The side opens its snapshot, prints the snapshot's binlog position and
    waits on stdin. ``on_position(position)`` runs while the snapshot is held
    (it waits for the connectors and works out the keys to exclude) and
    returns the line to send: a dict {"column": <pk>, "keys": [...]}. The side
    then computes the checksum in the same snapshot. Returns (result, output,
    position, payload); result is None when the side failed. If
    ``on_position`` raises, the side's stdin is closed (it exits with an
    error) and the exception propagates."""
    start = time.perf_counter()
    expected = expected_side_name(cmd, table)
    logging.debug("cmd " + command_text(cmd))
    try:
        process = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   env=side_environment())
    except OSError as e:
        logging.error(f"command failed to start : {e}")
        return (side_result(cmd, host, table, "127", str(e).encode('utf-8'), 0), b"", None, None)
    head = []
    (position, payload) = (None, None)
    try:
        for line in iter(process.stdout.readline, b""):
            head.append(line)
            match = SNAPSHOT_LINE.search(side_output_text(line).rstrip("\n"))
            if match and match.group('table') == expected:
                position = (match.group('file'), int(match.group('position')), match.group('kind'))
                break
        if position is not None:
            payload = on_position(position)
            process.stdin.write((json.dumps(payload) + "\n").encode("utf-8"))
            process.stdin.flush()
    finally:
        try:
            process.stdin.close()
        except OSError:
            pass
        try:
            rest = process.stdout.read()
        finally:
            # Close the pipe explicitly: left to the garbage collector it raises a
            # ResourceWarning, which execute_mysql's catch_warnings records and
            # logs as "SQL warnings" at WARNING -- and a scheduled job fails on
            # any WARNING line. Guarded like stdin above, so wait() always runs.
            try:
                process.stdout.close()
            except OSError:
                pass
            process.wait()
    stdout = b"".join(head) + rest
    for line in side_output_text(stdout).splitlines():
        logging.debug(f"side output: {side_note_text(line)}")
    rc = str(process.returncode)
    if rc != "0":
        logging.error("command failed : terminating")
    return (side_result(cmd, host, table, rc, stdout, time.perf_counter() - start), stdout, position, payload)


def side_result(cmd, host, table, rc, stdout, duration):
    """(host, table, md5, count) of a finished side run, or None."""
    side_failed = relay_side_messages(stdout, host, table)
    result = None
    if rc == '0' and side_failed:
        logging.error(f"{command_text(cmd)}. logged an ERROR although it exited 0: no result from {host} for {table}")
        log_side_output_tail(stdout, host, table)
    elif rc == '0':
        (table, checksum, count) = parse_checksum(stdout, table, expected_side_name(cmd, table))
        if checksum is None:
            log_side_output_tail(stdout, host, table)
        logging.info(f"{( host, table, checksum, count)} in {duration:0.3f} seconds" )
        result = ( host, table, checksum, count)
    else:
        logging.error(f"{command_text(cmd)}. failed with return code {rc}")
        log_side_output_tail(stdout, host, table)
    return result


def table_where(table_name, where, table_overrides_map):
    """``where`` with the table's config override ANDed on."""
    if table_name in table_overrides_map and 'where' in table_overrides_map[table_name]:
        if where is None:
            where =''
        if where != '':
            where+= " and "
        logging.info(f"Where override found for {table_name}")
        where+= table_overrides_map[table_name]['where']
    return where


def side_commands(mysql_database, database_override_map, table, mysql_host, replica_hosts, pk, max_pk, where,
                  ignored_columns=[], debug_output=False, defaults_file=None, partition_key=None, timestamp_columns=(),
                  binary_columns=(), json_columns=(), mysql_threads_per_table=None, mysql_extra_flags=(),
                  ch_extra_where=None, source_columns=()):
    """(MySQL side argv, [(replica host, ClickHouse side argv)]) for one table and ``where``.
    ``ch_extra_where`` is ANDed onto the ClickHouse sides' filter only; it is a
    filter, or a function of the replica host that returns one."""
    # Build MySQL checksum command
    mysql_cmd = get_mysql_checksum_command(mysql_host, mysql_database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output, defaults_file=defaults_file, threads_per_table=mysql_threads_per_table)
    if mysql_extra_flags:
        mysql_cmd = list(mysql_cmd) + list(mysql_extra_flags)

    # Build ClickHouse checksum commands
    ch_commands = []
    for replica_host in replica_hosts:
        replica_database = replica_database_for(replica_host, mysql_database, database_override_map)
        replica_where = where
        extra_where = ch_extra_where(replica_host) if callable(ch_extra_where) else ch_extra_where
        if extra_where:
            replica_where = f"({where}) and {extra_where}" if where else extra_where
        cmd = get_clickhouse_checksum_command(replica_host, replica_database, table, pk, max_pk, where=replica_where, ignored_columns=ignored_columns, debug_output=debug_output, partition_key = partition_key, timestamp_columns=timestamp_columns, binary_columns=binary_columns, json_columns=json_columns, source_columns=source_columns)
        ch_commands.append((replica_host, cmd))
    return (mysql_cmd, ch_commands)


def replica_database_for(replica_host, mysql_database, database_override_map, log=True):
    """The ClickHouse database a replica holds ``mysql_database`` in
    (``database_override_map``, §3.4 of spec 13.06)."""
    replica_database = mysql_database
    if replica_host in database_override_map and mysql_database in database_override_map[replica_host]:
        override_map = database_override_map[replica_host]
        database_maps = override_map.split(',')
        for database_map in database_maps:
            (source_db, target_db) = database_map.split(':')
            if source_db == mysql_database:
                replica_database = target_db
                break
        if log:
            logging.info(f"Overriding database for host {replica_host} from {mysql_database} to {replica_database}")
    return replica_database


def compute_checksum (mysql_database, database_override_map, table_overrides_map,  table, mysql_user, mysql_password, mysql_host, replica_hosts, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None, partition_key = None, lock_enabled=False, sleep_after_lock=3, mysql_port=3306, timestamp_columns=(), binary_columns=(), json_columns=(), lock_wait_timeout=None, fences=None, source_columns=()):
    table_name = f"{mysql_database}.{table}"
    logging.info(f"Checksumming {table_name}")
    where = table_where(table_name, where, table_overrides_map)
    (mysql_cmd, ch_commands) = side_commands(
        mysql_database, database_override_map, table, mysql_host, replica_hosts, pk, max_pk, where,
        ignored_columns=ignored_columns, debug_output=debug_output, defaults_file=defaults_file,
        partition_key=partition_key, timestamp_columns=timestamp_columns, binary_columns=binary_columns,
        json_columns=json_columns, source_columns=source_columns)

    # Lock held during all checksums (MySQL source + ClickHouse replicas)
    # to ensure a consistent comparison. Without --wait_for_connector the
    # sleep_after_lock is the only allowance for replication lag; with it, the
    # end of the source binary log is read under the lock -- every write to
    # the table is before it -- and each replica's connector is awaited up to
    # that position (spec 13.06 section 3.7.1). Unlocking early would let new
    # writes reach ClickHouse and invalidate the comparison.
    lock_conn = None
    head_conn = None
    try:
        if lock_enabled:
            lock_conn = get_mysql_connection(mysql_host, mysql_user,
                                             mysql_password, mysql_port, mysql_database)
            logging.info(f"Locking table {table} on source {mysql_host}")
            lock_tables(lock_conn, table, lock_wait_timeout=lock_wait_timeout)
            if not fences:
                time.sleep(sleep_after_lock)
        if fences:
            head_conn = lock_conn or get_mysql_connection(mysql_host, mysql_user, mysql_password, mysql_port, mysql_database)
            target = source_binary_log_head(head_conn)
            wait_for_connectors(fences, replica_hosts, target, lambda: source_binary_log_head(head_conn), table_name)

        # Run the MySQL source checksum and all ClickHouse replica checksums
        # concurrently under the lock. The lock keeps the source frozen so
        # every checksum sees the same snapshot; running them in parallel
        # (instead of MySQL-first-then-ClickHouse) minimizes how long the
        # lock must be held — the lock duration becomes the slowest single
        # checksum rather than the sum of MySQL + ClickHouse.
        all_commands = [(mysql_host, mysql_cmd)] + ch_commands
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(all_commands)) as executor:
            futures = [
                executor.submit(run_quick_safe_checksum, cmd, host, table)
                for (host, cmd) in all_commands
            ]
            # Surface the first exception, if any, so the caller can react.
            for future in concurrent.futures.as_completed(futures):
                if future.exception() is not None:
                    raise future.exception()
            # Results in submission order: MySQL source first, then replicas
            # (analyze_differences relies on the source being identifiable).

            results = [future.result() for future in futures]
        return results
    finally:
        if head_conn is not None and head_conn is not lock_conn:
            close_connection(head_conn, table_name)
        if lock_conn:
            try:
                unlock_tables(lock_conn, table)
            finally:
                close_connection(lock_conn, table_name)


def get_tables_from_regexp(conn, database, tables_regexp):
    return get_tables_from_regex(conn, args.no_wc, database, tables_regexp, include_partitions_regex=args.include_partitions_regex, exclude_tables_regex=args.exclude_tables_regex, non_partitioned_tables_only=args.non_partitioned_tables_only)


def include_flags():
    """The coverage opt-ins, passed identically to both sides (spec 11.02 section 3.9).

    getattr: a caller (test or script) that builds its own argparse.Namespace
    without these options keeps the behaviour it had before they existed."""
    flags = []
    if getattr(args, "include_floating_point_columns", False):
        flags.append("--include_floating_point_columns")
    if getattr(args, "include_json_columns", False):
        flags.append("--include_json_columns")
    return flags


DATETIME_BOUND_OPTIONS = ("min_datetime_value", "max_datetime_value")


def datetime_bound_flags():
    """``--min_datetime_value`` / ``--max_datetime_value``, passed identically
    to both sides, only when given to the driver (spec 13.06 section 3.6.1).

    Without them each side clamps to its own default, the full ClickHouse
    DateTime64 range, and the argv is unchanged. ``args`` namespaces built
    outside main() may lack the attributes; that means "not given"."""
    flags = []
    for option in DATETIME_BOUND_OPTIONS:
        value = getattr(args, option, None)
        if value is not None:
            flags += [f"--{option}", str(value)]
    return flags


def resolve_datetime_bounds(options, parser):
    """Canonicalise the datetime bounds given to the driver before any side
    runs, with the sides' own rule (``canonical_datetime_bound``: accepted
    forms, confined to the ClickHouse range with a WARNING), so both sides
    receive the same canonical text. A value that is not a datetime, or bounds
    that leave no range (min >= max, the missing one at its default), stop the
    run through ``parser.error``. Returns the effective (min, max) when at
    least one bound was given, else None."""
    if all(getattr(options, option, None) is None for option in DATETIME_BOUND_OPTIONS):
        return None
    for option in DATETIME_BOUND_OPTIONS:
        value = getattr(options, option, None)
        if value is not None:
            try:
                value = canonical_datetime_bound(value, f"--{option}")
            except ValueError as error:
                parser.error(str(error))
        setattr(options, option, value)
    effective_min = options.min_datetime_value if options.min_datetime_value is not None else DATETIME_MIN
    effective_max = options.max_datetime_value if options.max_datetime_value is not None else DATETIME_MAX
    if effective_min >= effective_max:
        parser.error(f"--min_datetime_value {effective_min} must be earlier than --max_datetime_value {effective_max}")
    return (effective_min, effective_max)


# Characters that are regex syntax in MySQL (ICU), ClickHouse (re2) and Python.
# Each is matched literally as a one-character class: a backslash escape would
# be consumed by the SQL string literal the sides paste the regex into.
TABLE_REGEX_SPECIALS = set(".$|?*+(){}")


def exact_table_regex(table):
    """``^<table>$`` matching exactly that table name (``orders$archive`` must
    not become ``^orders$``)."""
    return "^" + "".join(f"[{ch}]" if ch in TABLE_REGEX_SPECIALS else ch for ch in table) + "$"


LEGACY_ESCAPED_QUOTE = "\\'"


def normalize_where_override(table, where):
    """Return a per-table ``where`` override as plain SQL.

    Until fstr() became a literal substitution, the override was pushed through
    eval() as an f-string, so config authors had to write ``\\' 16:30:00\\'`` to
    get ``' 16:30:00'`` into the query; eval() consumed the backslashes. The
    literal fstr() forwards the text unchanged, so the same backslashes now
    reach MySQL and ClickHouse and both reject the statement (ClickHouse:
    Code 62 ``Unrecognized token: '\\'``). Configs written for the eval era
    keep working: the escape is folded here, once, with a note, and plain
    quotes are the documented form.
    """
    if where and LEGACY_ESCAPED_QUOTE in where:
        logging.info(f"where override for {table} uses the legacy escaped quote \\' -- "
                     "write plain single quotes; folding the escape for this run")
        return where.replace(LEGACY_ESCAPED_QUOTE, "'")
    return where


def side_command(module):
    """argv prefix running a packaged side module with this interpreter."""
    return [sys.executable, "-m", module]


def mysql_json_columns(conn, mysql_database, mysql_table):
    """Names of the table's MySQL JSON columns, in ordinal order."""
    return mysql_columns_by_data_type(conn, mysql_database, mysql_table, ('json',))


def get_mysql_checksum_command(mysql_host, database, table, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None, threads_per_table=None):
    partition_date = args.partition_date
    where_value = " 1=1 "
    if where:
        where_value += f" and {where} "
    if partition_date:
      where_value += f""" and {{partition_expression}}={partition_date:%Y%m%d}"""

    ignored_columns_clause = []
    if len(ignored_columns) > 0:
        logging.info(f"Ignoring columns {ignored_columns} for table {table}")
        ignored_columns_clause = ["--exclude_columns", ",".join(ignored_columns)]

    debug_output_clause = []
    if debug_output:
        debug_output_clause = ["--debug_output"]

    defaults_file_clause = []
    if defaults_file:
        defaults_file_clause = [f"--defaults_file={defaults_file}"]
    # An argv list, run without a shell: every value (table name, where clause,
    # partition expression) reaches the side byte for byte -- no `$` or
    # backtick expansion, no word splitting -- and the return code is the side
    # script's own. Its output is parsed in Python (parse_checksum), not by
    # grep|awk, so its WARNING lines are kept (spec 13.06 FM-13.06-3, -8).
    cmd = side_command(MYSQL_SIDE_MODULE) + [
           "--threads_per_table", str(args.threads_per_table if threads_per_table is None else threads_per_table), f"--threads={args.threads}",
           "--min_date_value", "1900-01-01", "--mysql_host", str(mysql_host), "--mysql_database", str(database),
           "--tables_regex", exact_table_regex(table), "--where", where_value,
           "--source_timezone", str(getattr(args, "source_timezone", None)), "--binary_encoding", str(getattr(args, "binary_encoding", "hex"))]
    cmd += include_flags() + datetime_bound_flags() + ignored_columns_clause + debug_output_clause + defaults_file_clause
    logging.debug(f"MySQL command: {command_text(cmd)}")
    return cmd



def get_clickhouse_checksum_command(replica_host, database, table, pk, max_pk, where=None, ignored_columns=[], debug_output=False, partition_key = None, timestamp_columns=(), binary_columns=(), json_columns=(), source_columns=()):
    partition_date = args.partition_date
    where_value = " 1=1 "
    if where:
        where_value += f" and {where} "
    if partition_date:
      # Plain single quotes: fstr() on the ClickHouse side is a literal
      # substitution. The former backslash escapes (toDate(\\'...\\')) only
      # existed for the eval()-based fstr, which interpreted them; with the
      # literal fstr they reached the server and every partitioned table failed
      # with Code 62 "Unrecognized token: '\\'".
      where_value += f""" and {{partition_expression}}=toDate('{partition_date:%Y-%m-%d}') """

    ignored_columns_value = "_version,is_deleted,_is_deleted,__is_deleted"
    if len(ignored_columns) > 0:
        logging.info(f"Ignoring columns {ignored_columns} for table {table}")
        ignored_columns_value += ","+",".join(ignored_columns)

    debug_output_clause = []
    if debug_output:
        debug_output_clause = ["--debug_output"]

    # One argv word, so a function partition (to_days(dt)) or one with spaces
    # reaches the side intact.
    partition_key_clause = []
    if partition_key:
        partition_key_clause = ["--partition_key", partition_key.replace('`','')]

    # MySQL TIMESTAMP columns are compared as UTC instants, the other datetime
    # columns as wall clocks of the source zone (spec 11.02 section 3.4).
    timestamp_columns_clause = []
    if timestamp_columns:
        timestamp_columns_clause = ["--timestamp_columns", ",".join(timestamp_columns)]

    # Only raw bytes need hexing on the replica; hex and base64 are compared
    # as the text the connector stored (spec 11.02 section 3.6).
    binary_encoding_clause = ["--binary_encoding", str(getattr(args, "binary_encoding", "hex"))]
    if getattr(args, "binary_encoding", "hex") == 'raw' and binary_columns:
        binary_encoding_clause += ["--hex_columns", ",".join(binary_columns)]

    # String columns that replicate MySQL JSON (spec 11.02 section 3.9).
    json_columns_clause = []
    if json_columns:
        json_columns_clause = ["--json_columns", ",".join(json_columns)]
    # The source table's column set: replica columns outside it hold no
    # replicated value and are not compared (spec 11.02 section 3.3). A JSON
    # list, so a column name holding a comma or a space survives the trip.
    source_columns_clause = []
    if source_columns:
        source_columns_clause = ["--source_columns", json.dumps(list(source_columns))]
    # An argv list, run without a shell (see get_mysql_checksum_command).
    cmd = side_command(CLICKHOUSE_SIDE_MODULE) + [
           "--max_memory_usage", "80000000000", f"--threads={args.threads}",
           "--clickhouse_host", str(replica_host), "--clickhouse_database", str(database),
           "--tables_regex", exact_table_regex(table), "--where", where_value,
           "--source_timezone", str(getattr(args, "source_timezone", None))]
    cmd += timestamp_columns_clause + json_columns_clause + source_columns_clause + binary_encoding_clause + include_flags()
    cmd += datetime_bound_flags()
    cmd += ["--exclude_columns", ignored_columns_value, "--sign_column", ""]
    cmd += debug_output_clause + partition_key_clause
    return cmd


def resolve_source_timezone(conn, explicit):
    """The IANA zone MySQL DATETIME values are interpreted in (spec 11.02
    section 3.2 step 1): ``--source_timezone`` when given, else the server's
    session zone, falling back to the system zone when that is SYSTEM. It must
    be the zone the connector's database.connectionTimeZone names."""
    if explicit:
        validate_timezone(explicit, "--source_timezone")
        return explicit
    (rowset, rowcount) = execute_mysql(conn, "select @@session.time_zone as session_time_zone, @@system_time_zone as system_time_zone")
    row = list(rowset.mappings())[0]
    zone = row['session_time_zone']
    if str(zone).upper() == 'SYSTEM':
        zone = row['system_time_zone']
    validate_timezone(zone, "the MySQL server time zone (pass --source_timezone with the IANA name the connector's database.connectionTimeZone uses)")
    logging.info(f"Source time zone resolved from MySQL: {zone} (override with --source_timezone; it must match the connector's database.connectionTimeZone)")
    return zone


def analyze_differences(results, mysql_host, replica_hosts, table_name=None, recheck_note=None):
    """Log and return the table's verdict: MATCH, DIFFERENT, EMPTY or ERROR.

    ``results`` is in submission order: the MySQL source first, then one entry
    per replica in ``replica_hosts`` order. The source is identified by that
    position, never by its host string, so a replica configured with the same
    host string as MySQL still gets a verdict (spec 13.06 FM-13.06-4). A side
    that failed (None) or printed no parseable checksum (None md5 or count)
    makes the table an ERROR, never a match (FM-13.06-1, FM-13.06-2). Equal
    results with zero rows are EMPTY, not a match (FM-13.06-5).

    ``recheck_note`` is set when a re-check of this table follows (spec 13.06
    section 3.7.3): a difference is then logged at INFO with the note, and
    only the last pass logs the WARNING "Checksum difference" line."""
    results = list(results or [])
    label = table_name
    if label is None:
        label = next((r[1] for r in results if r is not None), "<unknown table>")
    expected = 1 + len(replica_hosts)
    if len(results) != expected:
        logging.error(f"Checksum ERROR for {label}: {len(results)} side result(s) for {expected} side(s) "
                      f"(source {mysql_host}, replicas {list(replica_hosts)}); no verdict")
        return VERDICT_ERROR
    failed = [i for i, r in enumerate(results) if r is None or r[2] is None or r[3] is None]
    if failed:
        hosts = [mysql_host if i == 0 else replica_hosts[i - 1] for i in failed]
        logging.error(f"Checksum ERROR for {label}: no valid checksum from {hosts}; no verdict")
        return VERDICT_ERROR
    source_result = results[0]
    (mysql_checksum, mysql_count) = (source_result[2], source_result[3])
    is_difference = False
    for replica_result in results[1:]:
        (checksum, count) = (replica_result[2], replica_result[3])
        if  (checksum, count) !=  (mysql_checksum, mysql_count):
            if recheck_note is None:
                logging.warning(f"Checksum difference : {replica_result} to {source_result}")
            else:
                # INFO, not WARNING: a re-check follows, and WARNING is
                # reserved for the difference the last pass still sees.
                logging.info(f"Checksum mismatch {recheck_note}: {replica_result} to {source_result}")
            is_difference = True
    if is_difference:
        return VERDICT_DIFFERENT
    if mysql_count == 0:
        # INFO, not WARNING: empty partitions are normal in date-partitioned
        # runs, and WARNING is reserved for "Checksum difference".
        logging.info(f"EMPTY on both sides for {source_result[1]}: 0 rows compared "
                     "(empty table, or a filter or --partition_date that matched nothing)")
        return VERDICT_EMPTY
    logging.info(f"No difference for {source_result[1]}")
    return VERDICT_MATCH


def recheck_settings(options):
    """(recheck_differences, recheck_delay_seconds) of the parsed options.

    The command line defaults to one re-check after 60 s. A caller that builds
    its own argparse.Namespace without these attributes keeps the single pass
    it had before they existed."""
    return (getattr(options, "recheck_differences", 0), getattr(options, "recheck_delay_seconds", 60))


def verify_table(checksum, mysql_host, replica_hosts, table_name, recheck_differences, recheck_delay_seconds):
    """Return the table's verdict, checksumming it again when it differs.

    ``checksum`` is a callable without arguments that runs every side once and
    returns their results (``compute_checksum`` bound to the table). The sides
    of a table that is written during the run read it at different moments,
    and the MySQL side reads its PK chunks over separate connections, so one
    pass can differ although no row diverges (spec 13.06 section 3.7.3). A
    DIFFERENT verdict is therefore checksummed again, up to
    ``recheck_differences`` more times, ``recheck_delay_seconds`` apart; only
    the last pass may log the WARNING. A pass that is not DIFFERENT ends the
    table with that verdict, so a recheck that errors is still an ERROR."""
    passes = 1 + recheck_differences
    for pass_number in range(1, passes + 1):
        last = pass_number == passes
        note = None if last else (f"on pass {pass_number} of {passes}, checksumming again "
                                  f"in {recheck_delay_seconds} s")
        verdict = analyze_differences(checksum(), mysql_host, replica_hosts, table_name, recheck_note=note)
        if verdict != VERDICT_DIFFERENT or last:
            if pass_number > 1 and verdict != VERDICT_DIFFERENT:
                logging.info(f"Re-check of {table_name}: the mismatch of the earlier pass(es) is gone on pass "
                             f"{pass_number} of {passes}; verdict {verdict}")
            return verdict
        time.sleep(recheck_delay_seconds)


# ---------------------------------------------------------------------------
# Waiting for the connector (spec 13.06 sections 3.7.1 and 3.7.4)
# ---------------------------------------------------------------------------
# The MySQL side prints this INFO line under --consistent_snapshot.
SNAPSHOT_LINE = re.compile(r" - INFO - .* - Snapshot position for table (?P<table>\S.*) = "
                           r"(?P<file>\S+) (?P<position>\d+) (?P<kind>exact|upper_bound)\s*$")


OFFSET_TABLE_NAME = re.compile(r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)?$")


class ConnectorFenceError(Exception):
    """The connector offset of a replica cannot be read (missing table, no
    row, several rows): the table gets no verdict."""


class BinlogPositionError(Exception):
    """The source binary log position cannot be read (binary log disabled or
    REPLICATION CLIENT missing): the table gets no verdict."""


def binlog_position_key(file_name, position):
    """Sortable (sequence, position) of a binlog coordinate (binlog.000042, 157)."""
    return (int(str(file_name).rsplit(".", 1)[1]), int(position))


def parse_snapshot_position(data, expected_name):
    """(file, position, kind) from the MySQL side's output, else None.
    Exactly one ``Snapshot position for table`` line naming ``expected_name``
    must be present."""
    matches = [m for m in (SNAPSHOT_LINE.search(line) for line in side_output_text(data).splitlines()) if m]
    if len(matches) != 1 or matches[0].group('table') != expected_name:
        logging.error(f"Invalid snapshot output for {expected_name}: expected one 'Snapshot position for table "
                      f"{expected_name}' line, found {[m.group('table') for m in matches]}")
        return None
    match = matches[0]
    return (match.group('file'), int(match.group('position')), match.group('kind'))


def source_binary_log_head(conn):
    """(file, position) of the end of the source's binary log: SHOW BINARY LOG
    STATUS from MySQL 8.2, SHOW MASTER STATUS before (chosen from @@version).
    Needs the REPLICATION CLIENT privilege; raises BinlogPositionError when it
    cannot be read."""
    try:
        (rowset, _) = execute_mysql(conn, "SELECT @@version")
        version = rowset.fetchone()[0]
        match = re.match(r"(\d+)\.(\d+)", str(version))
        modern = bool(match) and (int(match.group(1)), int(match.group(2))) >= (8, 2)
        statement = "SHOW BINARY LOG STATUS" if modern else "SHOW MASTER STATUS"
        (rowset, _) = execute_mysql(conn, statement)
        row = rowset.fetchone()
    except Exception as e:
        raise BinlogPositionError(f"Cannot read the source binary log position: {e}") from e
    if row is None:
        raise BinlogPositionError(f"Cannot read the source binary log position: {statement} returned no row "
                                  "(binary log disabled, or REPLICATION CLIENT missing)")
    return (str(row[0]), int(row[1]))


class ConnectorFence:
    """Waits until the connector that writes one replica has applied the source
    binary log up to a position.

    The connector keeps its durable offset in ``offset_table`` on the replica
    (offset.storage.jdbc.table.name): one row whose ``offset_val`` JSON holds
    the source binlog ``file`` and ``pos``. Debezium records the start of the
    transaction it is in, so the fence is reached when that position is at or
    past the target: every transaction before the target is then applied. A
    source that writes nothing after the target never moves the offset past it;
    when the end of the source binary log is still the target and the offset
    has not moved for ``idle_seconds``, the wait ends as well (nothing is in
    flight). The last idle verdict is remembered (``idle_mark``: the source
    head and the connector offset at that moment): a later wait whose target is
    at or below that head, while the head and the offset are both unchanged,
    ends at once, because nothing was written to the binary log since the
    earlier verdict. After ``timeout_seconds`` the wait gives up and says so;
    the comparison still runs, and a difference it finds is reported with the
    connector position."""

    def __init__(self, replica_host, offset_table, offset_key_contains=None, port=9000,
                 config_file="./clickhouse-client.xml", timeout_seconds=300, poll_seconds=1, idle_seconds=10):
        self.replica_host = replica_host
        self.offset_table = offset_table
        self.offset_key_contains = offset_key_contains
        self.port = int(port)
        self.config_file = config_file
        self.timeout_seconds = timeout_seconds
        self.poll_seconds = poll_seconds
        self.idle_seconds = idle_seconds
        self.behind = False
        # (source head, connector offset) of the last idle verdict, or None.
        self.idle_mark = None

    def query(self, sql):
        """Rows of one read-only statement on this replica."""
        (user, password) = clickhouse_credentials(self.config_file)
        conn = clickhouse_connection(self.replica_host, user=user, password=password, port=self.port)
        try:
            return clickhouse_execute_conn(conn, sql)
        finally:
            conn.close()

    def offset(self):
        """(file, position) of the connector's durable offset."""
        try:
            rows = self.query(f"SELECT offset_key, offset_val FROM {self.offset_table} FINAL")
        except Exception as e:
            raise ConnectorFenceError(f"Cannot read the connector offset from {self.offset_table} on "
                                      f"{self.replica_host}: {e}") from e
        offsets = []
        for (key, value) in rows:
            if self.offset_key_contains and self.offset_key_contains not in key:
                continue
            try:
                parsed = json.loads(value)
            except ValueError:
                continue
            if "file" in parsed and "pos" in parsed:
                offsets.append((key, str(parsed["file"]), int(parsed["pos"])))
        if len(offsets) != 1:
            raise ConnectorFenceError(f"Expected one binlog offset in {self.offset_table} on {self.replica_host}"
                                      f"{' with a key containing ' + repr(self.offset_key_contains) if self.offset_key_contains else ''}"
                                      f", found {len(offsets)}: {[key for (key, _, _) in offsets]} "
                                      "(set offset_key_contains in the replica config)")
        return offsets[0][1:]

    def wait(self, target, source_head, label):
        """Return (reached, offset). ``target`` is (file, position);
        ``source_head`` returns the end of the source binary log."""
        target_key = binlog_position_key(*target)
        start = time.monotonic()
        (last_offset, last_change) = (None, start)
        # Once this connector has missed a target, it is behind: every later
        # wait of the run checks the offset once instead of sleeping the full
        # timeout again, so a lagging connector costs one timeout per run, not
        # one per slice and pass. The first target it reaches clears this.
        timeout_seconds = 0 if self.behind else self.timeout_seconds
        while True:
            offset = self.offset()
            if binlog_position_key(*offset) >= target_key:
                if self.behind:
                    logging.info(f"Connector on {self.replica_host} has caught up; waits are back to "
                                 f"{self.timeout_seconds} s")
                self.behind = False
                logging.info(f"Connector on {self.replica_host} reached {target[0]}:{target[1]} for {label} "
                             f"(offset {offset[0]}:{offset[1]}, waited {time.monotonic() - start:0.1f} s)")
                return (True, offset)
            now = time.monotonic()
            if offset != last_offset:
                (last_offset, last_change) = (offset, now)
            head = source_head()
            # An earlier idle verdict still holds while the source head and the
            # offset are both unchanged: nothing was written to the binary log
            # since then, so every target up to that head is already applied.
            # One idle period per quiet stretch of the source, not one per
            # slice and pass.
            mark = self.idle_mark
            if (mark is not None and head == mark[0] and offset == mark[1]
                    and target_key <= binlog_position_key(*head)):
                logging.info(f"Connector on {self.replica_host} is idle at {offset[0]}:{offset[1]} and the source "
                             f"wrote nothing after {head[0]}:{head[1]} since an earlier idle verdict, for {label}: "
                             "nothing is in flight")
                return (True, offset)
            if binlog_position_key(*head) <= target_key and now - last_change >= self.idle_seconds:
                self.idle_mark = (head, offset)
                logging.info(f"Connector on {self.replica_host} is idle at {offset[0]}:{offset[1]} and the source "
                             f"wrote nothing after {target[0]}:{target[1]} for {label}: nothing is in flight")
                return (True, offset)
            if now - start >= timeout_seconds:
                waited_for = ("in one check (it is behind)" if self.behind
                              else f"within {self.timeout_seconds} s")
                logging.info(f"Connector on {self.replica_host} did NOT reach {target[0]}:{target[1]} for {label} "
                             f"{waited_for} (offset {offset[0]}:{offset[1]}); comparing anyway"
                             f"{'' if self.behind else '; later waits of this run check the offset once until it catches up'}")
                self.behind = True
                return (False, offset)
            time.sleep(self.poll_seconds)


def build_connector_fences(config):
    """{replica host: ConnectorFence} from each replica's ``offset_table``
    (and optional ``offset_key_contains``). Every replica must name one."""
    fences = {}
    for replica in config['replicas']:
        clickhouse = replica['clickhouse']
        if not clickhouse.get('offset_table'):
            logging.error(f"--wait_for_connector and --consistent_snapshot need replicas[].clickhouse.offset_table "
                          f"(the connector's offset.storage.jdbc.table.name) for {clickhouse['host']}")
            sys.exit(1)
        if not OFFSET_TABLE_NAME.match(str(clickhouse['offset_table'])):
            logging.error(f"replicas[].clickhouse.offset_table for {clickhouse['host']} must be <database>.<table> "
                          f"(letters, digits, underscores): {clickhouse['offset_table']!r}")
            sys.exit(1)
        fences[clickhouse['host']] = ConnectorFence(
            clickhouse['host'], clickhouse['offset_table'], clickhouse.get('offset_key_contains'),
            port=args.clickhouse_port, config_file=args.clickhouse_config_file,
            timeout_seconds=args.fence_timeout_seconds, poll_seconds=args.fence_poll_seconds,
            idle_seconds=args.fence_idle_seconds)
    return fences


def wait_for_connectors(fences, replica_hosts, target, source_head, label):
    """Wait on every replica's connector; return {replica host: (reached, offset)}."""
    return {host: fences[host].wait(target, source_head, label) for host in replica_hosts}


# ---------------------------------------------------------------------------
# --consistent_snapshot: per-slice snapshots (spec 13.06 section 3.7.4)
# ---------------------------------------------------------------------------
def mysql_where_for_slicing(where, partition_key):
    """The MySQL row filter of the sides (``where`` plus the --partition_date
    predicate) with ``{partition_expression}`` resolved, or None when it
    cannot be resolved (no partition key)."""
    where_value = " 1=1 "
    if where:
        where_value += f" and {where} "
    if args.partition_date:
        where_value += f" and {{partition_expression}}={args.partition_date:%Y%m%d}"
    if "{partition_expression}" in where_value:
        if not partition_key:
            return None
        where_value = where_value.replace("{partition_expression}", str(partition_key))
    return where_value


# Keys sampled per slice of --snapshot_slice_rows rows, and the most keys one
# sample may return (a larger table is sampled more sparsely).
SLICE_SAMPLE_KEYS_PER_SLICE = 1000
SLICE_SAMPLE_MAX_KEYS = 1000000
# Below this rate even 10^15 filtered rows give a sample under the limit.
SLICE_SAMPLE_MIN_RATE = 1e-12


def quote_mysql_string(value):
    """A MySQL string literal (backslashes and quotes escaped)."""
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


def filtered_row_estimate(conn, table, pk, mysql_where, min_pk, max_pk):
    """(EXPLAIN estimate, partition statistics) of the filtered rows; neither
    reads table data. The first is the key-range EXPLAIN of
    ``estimate_table_count``. The second is the sum of information_schema
    PARTITIONS.TABLE_ROWS over the partitions EXPLAIN says the filtered query
    reads (an unpartitioned table's single row): on a date-partitioned table
    the EXPLAIN estimate can be far below the partition's rows."""
    explain_rows = estimate_table_count(conn, table, mysql_where, pk, min_pk, max_pk)
    plan = mysql_execute_df(conn, f"explain select * from {quote_mysql_identifier(table)} where {mysql_where}")
    partitions = plan['partitions'].iloc[0] if 'partitions' in plan.columns and len(plan.index) else None
    sql = ("select coalesce(sum(TABLE_ROWS), 0) as table_rows from information_schema.PARTITIONS "
           f"where TABLE_SCHEMA = database() and TABLE_NAME = {quote_mysql_string(table)}")
    if isinstance(partitions, str) and partitions:
        names = ",".join(quote_mysql_string(name.strip()) for name in partitions.split(","))
        sql += f" and (PARTITION_NAME in ({names}) or concat(PARTITION_NAME, '_', SUBPARTITION_NAME) in ({names}))"
    statistics = mysql_execute_df(conn, sql)
    # An aggregate without GROUP BY returns one row; a missing row counts as 0.
    partition_rows = int(statistics['table_rows'].iloc[0] or 0) if len(statistics.index) else 0
    return (int(explain_rows or 0), partition_rows)


def sample_keys(conn, table, pk, mysql_where, rate, limit):
    """Keys of a random sample of the filtered rows (each row with probability
    ``rate``), at most ``limit`` of them: one read of the filtered rows' keys
    (an index-only read when an index holds the filter's columns; InnoDB
    secondary indexes hold the primary key, partition column included)."""
    column = quote_mysql_identifier(pk)
    df = mysql_execute_df(conn, f"select {column} as k from {quote_mysql_identifier(table)} where ({mysql_where}) "
                                f"and rand() < {rate:.12f} limit {int(limit)}")
    return [int(key) for key in df['k'].tolist()]


def slice_starts(conn, table, pk, mysql_where, slice_rows, min_slices, min_pk, max_pk):
    """First key of each slice, smallest first; the first is ``min_pk``.

    A table the larger of the two estimates (filtered_row_estimate) puts at
    ``slice_rows`` rows or fewer is one slice. Otherwise the filtered keys are
    sampled (about SLICE_SAMPLE_KEYS_PER_SLICE per slice); the sample gives the
    row count (sampled keys / rate), hence the number of slices, at least
    ``min_slices`` (--threads_per_table), and the boundaries are the sample's
    quantiles, so every slice holds about the same number of rows however the
    keys are spread. An even split of the key range does not: a day partition
    whose keys sit in a narrow band at the top of the range gets almost all its
    rows in one slice."""
    if slice_rows <= 0:
        logging.info(f"Slices of {table}: --snapshot_slice_rows 0, one slice")
        return [min_pk]
    (explain_rows, partition_rows) = filtered_row_estimate(conn, table, pk, mysql_where, min_pk, max_pk)
    estimate = max(explain_rows, partition_rows)
    if estimate <= slice_rows:
        logging.info(f"Slices of {table}: estimate {estimate} rows (EXPLAIN {explain_rows}, partition statistics "
                     f"{partition_rows}), one slice")
        return [min_pk]
    rate = min(1.0, SLICE_SAMPLE_KEYS_PER_SLICE / slice_rows, SLICE_SAMPLE_MAX_KEYS / estimate)
    keys = sample_keys(conn, table, pk, mysql_where, rate, SLICE_SAMPLE_MAX_KEYS)
    while len(keys) >= SLICE_SAMPLE_MAX_KEYS and rate / 16 >= SLICE_SAMPLE_MIN_RATE:
        # A full sample stopped at its LIMIT in scan order, so it is not
        # representative: the estimates were far too low. Sample sparser until
        # a sample ends below the limit (16 times fewer keys each time).
        rate /= 16
        keys = sample_keys(conn, table, pk, mysql_where, rate, SLICE_SAMPLE_MAX_KEYS)
    sampled_rows = round(len(keys) / rate)
    count = math.ceil(sampled_rows / slice_rows)
    if count > 1:
        count = max(count, min_slices)
    bounds = sorted({key for key in keys if key > min_pk})
    starts = [min_pk]
    for i in range(1, count):
        if bounds and bounds[(i * len(bounds)) // count] > starts[-1]:
            starts.append(bounds[(i * len(bounds)) // count])
    fewer = (f" (fewer than {count}: the sample holds {len(bounds)} distinct key(s) above the smallest)"
             if len(starts) < count else "")
    logging.info(f"Slices of {table}: estimate {estimate} rows (EXPLAIN {explain_rows}, partition statistics "
                 f"{partition_rows}), about {sampled_rows} rows from a sample of {len(keys)} keys, "
                 f"{len(starts)} slice(s){fewer}")
    return starts


def snapshot_slices(conn, table, pk, mysql_where, slice_rows, min_slices=1):
    """Row conditions that split the table into PK-range slices of about
    ``slice_rows`` rows each (slice_starts), at least ``min_slices`` of them
    when the table holds more than one slice's worth. The first slice starts
    at the smallest key of the filtered rows and the last is open above, so
    together they cover every key of the filtered set, including rows inserted
    (with higher keys) while the run goes on. Every slice is bounded below: on
    a replica sorted by the key, an open lower end would scan every older row
    of the table (an unpartitioned replica of a date-partitioned source reads
    its whole history for the first slice). A table without an integer primary
    key, or with no rows in the filter, is one slice (None)."""
    if not pk or mysql_where is None:
        return [None]
    (min_pk, max_pk) = get_min_max_pk_value(conn, table, pk, mysql_where)
    if min_pk is None:
        return [None]
    starts = slice_starts(conn, table, pk, mysql_where, slice_rows, min_slices, min_pk, max_pk)
    column = f"`{pk}`"
    edges = starts + [None]
    return [f"{column} >= {low}" + (f" and {column} < {high}" if high is not None else "")
            for (low, high) in zip(edges, edges[1:])]


def compare_slice(results, mysql_host, replica_hosts, table_name, condition, recheck_note=None, fence_notes=()):
    """MATCH, DIFFERENT, EMPTY or ERROR for one slice. Only a difference on
    the last pass logs the WARNING ``Checksum difference``; the table's own
    verdict line is logged by verify_table_in_slices."""
    label = f"{table_name} slice [{condition or 'all rows'}]"
    expected = 1 + len(replica_hosts)
    failed = [i for i, r in enumerate(results) if r is None or r[2] is None or r[3] is None]
    if len(results) != expected or failed:
        hosts = [mysql_host if i == 0 else replica_hosts[i - 1] for i in failed]
        logging.error(f"Checksum ERROR in {label}: no valid checksum from {hosts}")
        return VERDICT_ERROR
    source_result = results[0]
    differences = [r for r in results[1:] if (r[2], r[3]) != (source_result[2], source_result[3])]
    if not differences:
        logging.info(f"Slice match in {label}: {source_result[3]} rows")
        return VERDICT_EMPTY if source_result[3] == 0 else VERDICT_MATCH
    for replica_result in differences:
        if recheck_note is None:
            notes = "".join(f"; {note}" for note in fence_notes)
            logging.warning(f"Checksum difference : {replica_result} to {source_result} in slice "
                            f"[{condition or 'all rows'}]{notes}")
        else:
            logging.info(f"Checksum mismatch {recheck_note} in {label}: {replica_result} to {source_result}")
    return VERDICT_DIFFERENT


def checksum_one_slice(table_name, table, condition, commands_for, mysql_host, replica_hosts, fences, source_head,
                       recheck_differences, exclusion=None):
    """(verdict, rows compared, keys left out) of one slice.

    The MySQL side opens one consistent snapshot of the slice, prints its
    binlog position and holds the snapshot while every connector is awaited up
    to that position; then both sides compute the slice. A difference is read
    again with a fresh snapshot, up to ``recheck_differences`` more times.

    ``exclusion`` ({"column", "databases": {host: replica database}, "cap"})
    turns on the version fence: keys a replica changed after the slice's
    highest _version read before the snapshot are left out on both sides, so a
    slice written while it is read compares only the keys whose state the
    snapshot and the replica can share (spec 13.06 section 3.7.4). The
    connector keeps writing while the slice is compared, so the replicas are
    read first, while the snapshot is held, each leaving out in the same query
    every key with a row versioned above its floor; the keys are listed only
    after that read, so the list the MySQL side leaves out holds at least
    every key the replica read left out. A key changed in between is left out
    on MySQL only: the counts differ and the slice is read again, never a
    false match."""
    label = f"{table_name} slice [{condition or 'all rows'}]"
    passes = 1 + recheck_differences
    for pass_number in range(1, passes + 1):
        last = pass_number == passes
        # Before the snapshot: the highest _version each replica holds in the
        # slice. Every transaction after the snapshot position is versioned
        # above it (versions grow in binlog order, spec 02.02 section 3.3).
        floors = None
        if exclusion:
            floors = {host: slice_max_version(fences[host], exclusion["databases"][host], table, condition)
                      for host in replica_hosts}
        (mysql_cmd, _) = commands_for(condition)
        state = {"notes": [], "keys": [], "too_many": 0, "replicas": None}

        def read_replicas(extra_where):
            (_, ch_commands) = commands_for(condition, extra_where)
            with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, len(ch_commands))) as executor:
                futures = [executor.submit(run_quick_safe_checksum, cmd, host, table) for (host, cmd) in ch_commands]
                return [future.result() for future in futures]

        def on_position(position):
            # The side's own line reaches this log only at DEBUG; the position
            # the wait is anchored on is part of the evidence for the verdict.
            logging.info(f"Snapshot of {label} at {position[0]}:{position[1]} ({position[2]})")
            waited = wait_for_connectors(fences, replica_hosts, position[:2], source_head, label)
            state["notes"] = [f"connector on {host} had not reached {position[0]}:{position[1]} "
                              f"(offset {offset[0]}:{offset[1]})"
                              for host, (reached, offset) in waited.items() if not reached]
            if floors is not None:
                # Keys the replicas changed after the floor: the snapshot cannot
                # hold their later state, so they are left out on both sides. The
                # replicas are read now, leaving them out as of their own read
                # (the connector keeps writing), and listed afterwards for the
                # MySQL side, which leaves out that list or more.
                state["replicas"] = read_replicas(
                    lambda host: changed_keys_filter(exclusion["databases"][host], table, exclusion["column"],
                                                     condition, floors[host]))
                keys = set()
                for host in replica_hosts:
                    keys |= keys_changed_since(fences[host], exclusion["databases"][host], table,
                                               exclusion["column"], condition, floors[host], exclusion["cap"])
                if len(keys) > exclusion["cap"]:
                    state["too_many"] = len(keys)
                    state["notes"].append(f"more than {exclusion['cap']} keys changed while the slice was read "
                                          "(--snapshot_max_excluded_keys); none were left out")
                    keys = set()
                    state["replicas"] = read_replicas(None)
                state["keys"] = sorted(keys)
                if keys:
                    logging.info(f"Excluded {len(keys)} key(s) of {label} changed after its snapshot began, "
                                 "on both sides")
            return {"column": exclusion["column"] if exclusion else None, "keys": state["keys"]}

        (mysql_result, _, position, _) = run_snapshot_side(mysql_cmd, mysql_host, table, on_position)
        if position is None:
            logging.error(f"Checksum ERROR in {label}: the MySQL side printed no snapshot position")
            return (VERDICT_ERROR, 0, 0)
        if mysql_result is None or mysql_result[2] is None:
            logging.error(f"Checksum ERROR in {label}: no valid checksum from {mysql_host}"
                          f"{'; the replicas were not read' if state['replicas'] is None else ''}")
            return (VERDICT_ERROR, 0, 0)
        ch_results = state["replicas"] if state["replicas"] is not None else read_replicas(None)
        note = None if last else f"on pass {pass_number} of {passes}, reading the slice again"
        verdict = compare_slice([mysql_result] + ch_results, mysql_host, replica_hosts, table_name, condition,
                                recheck_note=note, fence_notes=state["notes"])
        if verdict != VERDICT_DIFFERENT or last:
            rows = mysql_result[3] if verdict in (VERDICT_MATCH, VERDICT_EMPTY) else 0
            return (verdict, rows, len(state["keys"]))


def quote_clickhouse_identifier(name):
    return "`" + str(name).replace("\\", "\\\\").replace("`", "\\`") + "`"


def slice_max_version(fence, database, table, condition):
    """Highest _version of the slice's rows on one replica (all versions, not FINAL); 0 when empty."""
    rows = fence.query(f"SELECT max(_version) FROM {quote_clickhouse_identifier(database)}."
                       f"{quote_clickhouse_identifier(table)} WHERE {condition or '1'}")
    return int(rows[0][0] or 0)


def keys_changed_since(fence, database, table, column, condition, version, cap):
    """Keys of the slice with a row versioned above ``version`` on one replica
    (inserts, updates and delete markers alike), at most cap + 1 of them."""
    rows = fence.query(f"SELECT DISTINCT {quote_clickhouse_identifier(column)} FROM "
                       f"{quote_clickhouse_identifier(database)}.{quote_clickhouse_identifier(table)} "
                       f"WHERE ({condition or '1'}) AND _version > {int(version)} LIMIT {int(cap) + 1}")
    return {int(row[0]) for row in rows}


def changed_keys_filter(database, table, column, condition, version):
    """ClickHouse row filter that leaves out, as of the query that applies it,
    every key of the slice with a row versioned above ``version`` (the same
    keys keys_changed_since lists, read in the replica's own query)."""
    key = quote_clickhouse_identifier(column)
    return (f"{key} not in (select {key} from {quote_clickhouse_identifier(database)}."
            f"{quote_clickhouse_identifier(table)} where ({condition or '1'}) and _version > {int(version)})")


def replicas_have_version_column(fences, databases, table):
    """True when every replica's table has a _version column."""
    for (host, database) in databases.items():
        rows = fences[host].query(
            "SELECT count() FROM system.columns WHERE database = '" + database.replace("\\", "\\\\").replace("'", "\\'")
            + "' AND table = '" + table.replace("\\", "\\\\").replace("'", "\\'") + "' AND name = '_version'")
        if int(rows[0][0]) != 1:
            return False
    return True


def verify_table_in_slices(mysql_database, database_override_map, table_overrides_map, table, mysql_user,
                           mysql_password, mysql_host, replica_hosts, pk, max_pk, where, fences, ignored_columns=[],
                           defaults_file=None, partition_key=None, mysql_port=3306, timestamp_columns=(),
                           binary_columns=(), json_columns=(), source_columns=()):
    """Verdict of a table compared slice by slice under --consistent_snapshot
    (spec 13.06 section 3.7.4). No lock is taken: each slice is one InnoDB
    read view on the source, held only while that slice is read."""
    table_name = f"{mysql_database}.{table}"
    logging.info(f"Checksumming {table_name}")
    where = table_where(table_name, where, table_overrides_map)
    conn = get_mysql_connection(mysql_host, mysql_user, mysql_password, mysql_port, mysql_database)
    try:
        integer_pk = mysql_pk_columns(conn, mysql_database, table, is_integer=True)
        slice_pk = integer_pk[0] if integer_pk else None
        conditions = snapshot_slices(conn, table, slice_pk, mysql_where_for_slicing(where, partition_key),
                                     args.snapshot_slice_rows, min_slices=max(1, args.threads_per_table))
    finally:
        close_connection(conn, table_name)
    logging.info(f"Consistent snapshots for {table_name}: {len(conditions)} slice(s)"
                 f"{' on ' + slice_pk if slice_pk and conditions != [None] else ' (whole table)'}")

    def commands_for(condition, ch_extra_where=None):
        slice_where = where
        if condition:
            slice_where = f"({where}) and {condition}" if where else condition
        return side_commands(mysql_database, database_override_map, table, mysql_host, replica_hosts, pk, max_pk,
                             slice_where, ignored_columns=ignored_columns, defaults_file=defaults_file,
                             partition_key=partition_key, timestamp_columns=timestamp_columns,
                             binary_columns=binary_columns, json_columns=json_columns,
                             mysql_threads_per_table=1,
                             mysql_extra_flags=["--consistent_snapshot", "--exclude_keys_from_stdin"],
                             ch_extra_where=ch_extra_where, source_columns=source_columns)

    # The version fence needs a key to exclude by and a _version column on every
    # replica table (the connector's ReplacingMergeTree version).
    exclusion = None
    if slice_pk:
        databases = {host: replica_database_for(host, mysql_database, database_override_map, log=False)
                     for host in replica_hosts}
        if replicas_have_version_column(fences, databases, table):
            exclusion = {"column": slice_pk, "databases": databases,
                         "cap": getattr(args, "snapshot_max_excluded_keys", 5000)}
        else:
            logging.info(f"{table_name}: a replica table has no _version column; keys written while a slice is read "
                         "are not left out, so such a slice can differ")
    (recheck_differences, _) = recheck_settings(args)
    head_conn = get_mysql_connection(mysql_host, mysql_user, mysql_password, mysql_port, mysql_database)
    head_lock = threading.Lock()

    def source_head():
        with head_lock:
            return source_binary_log_head(head_conn)

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.threads_per_table)) as executor:
            futures = [executor.submit(checksum_one_slice, table_name, table, condition, commands_for, mysql_host,
                                       replica_hosts, fences, source_head, recheck_differences, exclusion)
                       for condition in conditions]
            outcomes = [future.result() for future in futures]
    finally:
        close_connection(head_conn, table_name)
    verdicts = [verdict for (verdict, _, _) in outcomes]
    rows = sum(count for (_, count, _) in outcomes)
    excluded = sum(keys for (_, _, keys) in outcomes)
    if excluded:
        logging.info(f"{table_name}: {excluded} key(s) changed while their slice was read were left out on both sides")
    if VERDICT_ERROR in verdicts:
        logging.error(f"Checksum ERROR for {table_name}: {verdicts.count(VERDICT_ERROR)} of {len(verdicts)} "
                      "slice(s) have no verdict")
        return VERDICT_ERROR
    if VERDICT_DIFFERENT in verdicts:
        logging.info(f"{table_name}: {verdicts.count(VERDICT_DIFFERENT)} of {len(verdicts)} slice(s) differ")
        return VERDICT_DIFFERENT
    if rows == 0:
        logging.info(f"EMPTY on both sides for {table_name}: 0 rows compared "
                     "(empty table, or a filter or --partition_date that matched nothing)")
        return VERDICT_EMPTY
    logging.info(f"{table_name}: {len(verdicts)} slice(s), {rows} rows, each slice in one consistent snapshot")
    logging.info(f"No difference for {table_name}")
    return VERDICT_MATCH


def report_run_summary(verdicts, fail_on_empty):
    """Log the per-verdict totals; return the process exit code.

    ERROR (a table with no verdict) always fails the run. EMPTY fails it only
    with --fail_on_empty. DIFFERENT keeps exit 0 (spec 11.02 FM-11.02-1).
    Replica-only columns are tolerated and never affect the exit code (spec
    11.02 section 3.9, CONSTITUTION.md "Parity scope")."""
    by_verdict = {}
    for table_name, verdict in verdicts.items():
        by_verdict.setdefault(verdict, []).append(table_name)
    counts = ", ".join(f"{len(by_verdict.get(v, []))} {v}"
                       for v in (VERDICT_MATCH, VERDICT_DIFFERENT, VERDICT_EMPTY, VERDICT_ERROR))
    logging.info(f"Run summary: {len(verdicts)} table(s) verified: {counts}")
    exit_code = 0
    empty = by_verdict.get(VERDICT_EMPTY, [])
    if empty:
        message = (f"EMPTY on both sides: {len(empty)} table(s) compared 0 rows"
                   f"{' (failing the run: --fail_on_empty)' if fail_on_empty else ' (pass --fail_on_empty to fail the run)'}: "
                   + ", ".join(empty))
        if fail_on_empty:
            logging.error(message)
            exit_code = 1
        else:
            logging.info(message)
    errors = by_verdict.get(VERDICT_ERROR, [])
    if errors:
        logging.error(f"{len(errors)} table(s) have NO verdict (a side failed or its output could not be parsed): "
                      + ", ".join(errors))
        exit_code = 1
    with replica_only_lock:
        findings = sorted(replica_only_findings.items())
    if findings:
        logging.info(f"{len(findings)} replica table(s) carry columns the source table does not have "
                     "(tolerated, out of parity scope; not compared): "
                     + "; ".join(f"{host} {table} {columns}" for ((host, table), columns) in findings))
    return exit_code


def quote_mysql_identifier(identifier):
    """Backtick-quote a MySQL identifier, escaping embedded backticks.

    MySQL escapes a backtick inside a quoted identifier by doubling it. Without
    this, a table name containing a backtick would terminate the quoted
    identifier early and the remainder would be parsed as SQL grammar.
    """
    return "`" + str(identifier).replace("`", "``") + "`"


def lock_tables(conn, table, lock_wait_timeout=None):
    # Bound how long we wait for the metadata lock. Without this the session
    # inherits the server default (often very large), and a table under
    # continuous writes -- e.g. a hot table applied non-stop by a replica's
    # SQL thread -- makes the lock starve for the whole timeout before failing,
    # taking the entire checksum run down with it.
    if lock_wait_timeout is not None:
        execute_mysql(conn, f"SET SESSION lock_wait_timeout = {int(lock_wait_timeout)}")
    lock_stmt = f"LOCK TABLES {quote_mysql_identifier(table)} READ"
    logging.info(f"Locking table with statement {lock_stmt}")
    try:
        execute_mysql(conn, lock_stmt)
    except Exception as e:
        if _is_lock_wait_timeout(e):
            raise LockAcquisitionError(
                f"Could not acquire READ lock on {table} within "
                f"{lock_wait_timeout}s -- source has continuous writes: {e}"
            ) from e
        raise


def unlock_tables(conn, table):
    unlock_stmt = "UNLOCK TABLES"
    logging.info(f"Unlocking table {table} with statement {unlock_stmt}")
    execute_mysql(conn, unlock_stmt)


def close_connection(conn, table_name):
    """Safely close a MySQL connection."""
    try:
        conn.close()
    except Exception as e:
        logging.warning(f"Failed to close connection for {table_name}: {e}")

def match_table_include_list(database, table, table_include_list):
    """
    Check if a table matches any regex pattern in the include list.
    
    Args:
        database: The database name
        table: The table object or dict with 'table_name' key
        table_include_list: List of regex patterns for table names in format "database.table"
    
    Returns:
        True if the table matches any pattern in the include list, False otherwise
    """
    table_name = table['table_name'] if isinstance(table, dict) else table
    full_table_name = f"{database}.{table_name}"
    
    for pattern in table_include_list:
        try:
            rex = re.compile(pattern.strip())
            if rex.match(full_table_name):
                return True
        except re.error as e:
            logging.error(f"Invalid regex pattern '{pattern}': {e}")
            continue
    
    return False

def run_config(config):
    """Display the parsed configuration."""
    logging.info("\nConfiguration Details:")
    
    mysql_host = config['source']['mysql']['host']
    logging.info(f"Source MySQL Host: {mysql_host}")
    
    replica_hosts = []
    database_override_map = {}
    mysql_table_include_list = None
    
    if "table_include_list" in  config['source']['mysql']:
            mysql_table_include_list = config['source']['mysql']['table_include_list'].split(',')
    
    for i, replica in enumerate(config['replicas']):
        replica_hosts.append(replica['clickhouse']['host'])
        if "database_override_map" in replica['clickhouse']:
            database_override_map[replica['clickhouse']['host']] = replica['clickhouse']['database_override_map']
            
   
    logging.info(f"\nFound {len( config['replicas'])} ClickHouse replicas: {replica_hosts}")
    
    mysql_user = args.mysql_user
    config_file = args.defaults_file
    (mysql_user, mysql_password) = resolve_credentials_from_config(config_file)

    database = args.mysql_database
    databases = []
    if not database:    
        databases = config['source']['mysql']['databases'] if 'databases' in config['source']['mysql'] else []
        if len(databases) == 0:
            logging.error("If not specifying --mysql_database, there must at least one database in the config file under mysql:databases")
            sys.exit(1)
        logging.info(f"Using MySQL databases: {databases}") 
    else:
        databases = [database]  
    
    ignored_columns = config['source']['mysql']['ignored_columns'] if 'ignored_columns' in config['source']['mysql'] else []
    ignored_columns_map = {}
    for ignored_column in ignored_columns:
        logging.info(f"Ignoring column {ignored_column}")
        ignored_columns_split = ignored_column.split('.')  
        db = ignored_columns_split[0]
        table = ignored_columns_split[1]
        col = ignored_columns_split[2]
        if len(ignored_columns_split) == 3:
            if db not in ignored_columns_map:
                ignored_columns_map[db] = {}
            if table not in ignored_columns_map[db]:
                ignored_columns_map[db][table] = {}
            ignored_columns_map[db][table][col] = True
    table_overrides = config['source']['mysql']['tables'] if 'tables' in config['source']['mysql'] else []
    table_overrides_map = {}
    for table_dict in table_overrides:
        table = next(iter(table_dict))
        if 'where' in table_dict[table]:
            if not table in table_overrides_map:
                table_overrides_map[table] = {}
            table_dict[table]['where'] = normalize_where_override(table, table_dict[table]['where'])
            table_overrides_map[table]['where'] = table_dict[table]['where']
    logging.info(f"Table overrides : {table_overrides_map}")
    # getattr: a caller that builds its own argparse.Namespace without these
    # options keeps the behaviour it had before they existed.
    consistent_snapshot = getattr(args, "consistent_snapshot", False)
    fences = None
    if getattr(args, "wait_for_connector", False) or consistent_snapshot:
        fences = build_connector_fences(config)
    verdicts = {}
    with replica_only_lock:
        replica_only_findings.clear()
    for database in databases:
        logging.info(f"Using MySQL database: {database}")
        try:
            conn = get_mysql_connection(mysql_host, mysql_user,
                                    mysql_password, args.mysql_port, database)
            args.source_timezone = resolve_source_timezone(conn, getattr(args, "source_timezone", None))
            tables = get_tables_from_regexp(conn, database, args.tables_regex)
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
                futures = []
                future_to_table = {}
                table_include_list = [t for t in mysql_table_include_list if t.startswith(f"{database}.")] if mysql_table_include_list else []
                # --no_wc: get_tables_from_regex returns [[<tables_regex>]], the table name
                # itself, not a result set (spec 13.06 D-13.06-26).
                # Rows by column name through mappings(): a SQLAlchemy 2.x Row is a
                # tuple, so row['table_name'] raised TypeError (spec 13.06 D-13.06-9).
                table_rows = [{'table_name': row[0]} for row in tables] if args.no_wc else tables.mappings().fetchall()
                for table_row in table_rows:
                    table = table_row['table_name']
                    table_name = f"{database}.{table}"
                    if len(table_include_list)>0 and not match_table_include_list(database, table, table_include_list):
                        logging.info(f"Skipping table {database}.{table} since not in include list")
                        continue

                    # Pre-fetch metadata on shared connection (no lock needed)
                    pk = mysql_pk_columns(conn, database, table)
                    pk_column = pk[0] if len(pk) > 0 else 'NULL'
                    (min_pk, max_pk) = get_min_max_pk_value(conn, table, pk_column, '1=1')
                    partition_key = get_table_partition_key(conn, database, table)
                    timestamp_columns = mysql_columns_by_data_type(conn, database, table, ('timestamp',))
                    binary_columns = []
                    if getattr(args, "binary_encoding", "hex") == 'raw':
                        binary_columns = mysql_columns_by_data_type(conn, database, table, binary_datatypes)
                    json_columns = mysql_json_columns(conn, database, table)
                    source_columns = mysql_column_names(conn, database, table)
                    ignored_columns = []
                    if database in ignored_columns_map and table in ignored_columns_map[database]:
                        ignored_columns = list(ignored_columns_map[database][table].keys())

                    logging.info(f"Ignored columns for table {table_name}: {ignored_columns}")
                    # Lock lifecycle is now managed inside compute_checksum per table.
                    # Each future acquires its own lock, runs both MySQL and
                    # ClickHouse checksums under the lock, then releases it.
                    if consistent_snapshot:
                        future = executor.submit(
                            verify_table_in_slices, database, database_override_map, table_overrides_map, table,
                            mysql_user, mysql_password, mysql_host, replica_hosts, pk_column, max_pk, args.where,
                            fences, ignored_columns=ignored_columns, defaults_file=args.defaults_file,
                            partition_key=partition_key, mysql_port=args.mysql_port,
                            timestamp_columns=timestamp_columns, binary_columns=binary_columns,
                            json_columns=json_columns, source_columns=source_columns)
                    else:
                        checksum = functools.partial(
                            compute_checksum, database, database_override_map, table_overrides_map, table, mysql_user, mysql_password, mysql_host, replica_hosts, pk_column, max_pk, args.where, ignored_columns = ignored_columns, debug_output = args.debug_output, defaults_file=args.defaults_file, partition_key = partition_key, lock_enabled=args.lock_tables_on_source, sleep_after_lock=args.sleep_after_lock, mysql_port=args.mysql_port, timestamp_columns=timestamp_columns, binary_columns=binary_columns, json_columns=json_columns, lock_wait_timeout=getattr(args, "lock_wait_timeout", 30), fences=fences, source_columns=source_columns)
                        future = executor.submit(
                            verify_table, checksum, mysql_host, replica_hosts, table_name,
                            *recheck_settings(args))
                    futures.append(future)
                    future_to_table[future] = table_name
                skipped_tables = []
                for future in concurrent.futures.as_completed(futures):
                    table_name = future_to_table[future]
                    exc = future.exception()
                    if exc is not None:
                        if isinstance(exc, LockAcquisitionError) and not getattr(args, "fail_on_lock_timeout", False):
                            # Default: a single un-lockable (continuously-written)
                            # table must not abort the whole run. Skip it and keep
                            # comparing the rest. The skip is NOT silent -- it is
                            # logged here and summarized loudly below as an explicit
                            # coverage gap, so a green run never hides that a table
                            # went unverified. Pass --fail_on_lock_timeout to make
                            # any such timeout abort the run instead.
                            logging.warning(
                                "COVERAGE GAP -- skipping checksum for " + table_name +
                                ": source lock could not be acquired: " + str(exc))
                            skipped_tables.append(table_name)
                            continue
                        if isinstance(exc, (ConnectorFenceError, BinlogPositionError)):
                            # Waiting for the connector was asked for and is
                            # impossible: this table gets no verdict, the
                            # others still do, and the run exits 1.
                            logging.error(f"Checksum ERROR for {table_name}: {exc}")
                            verdicts[table_name] = VERDICT_ERROR
                            continue
                        logging.error("Exception in table " + table_name)
                        logging.error(exc)
                        raise exc
                    else:
                        verdicts[table_name] = future.result()
                if skipped_tables:
                    logging.warning(
                        "COVERAGE GAP -- checksum finished but " +
                        str(len(skipped_tables)) +
                        " table(s) were NOT compared (source lock timeout; pass "
                        "--fail_on_lock_timeout to fail the run instead): " +
                        ", ".join(skipped_tables))

        except (KeyboardInterrupt, SystemExit):
            logging.info("Received interrupt")
            os._exit(1)
        except Exception as e:
            logging.error("Exception in main thread : " + str(e))
            logging.error(traceback.format_exc())
            sys.exit(1)

    exit_code = report_run_summary(verdicts, args.fail_on_empty)
    logging.debug("Exiting Main Thread")
    sys.exit(exit_code)


def side_environment():
    """The child environment: this package's root first on PYTHONPATH."""
    env = dict(os.environ)
    env["PYTHONPATH"] = os.pathsep.join(p for p in (PACKAGE_ROOT, env.get("PYTHONPATH", "")) if p)
    return env


def run_quick_safe_command(cmd):
    """Run a side command given as an argv list (no shell); return (rc, output)."""
    logging.debug("cmd " + command_text(cmd))
    try:
        process = subprocess.Popen(cmd,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT,
                                   env=side_environment())
    except OSError as e:
        logging.error(f"command failed to start : {e}")
        return "127", str(e).encode('utf-8')
    stdout, stderr = process.communicate()
    rc = str(process.poll())
    # Line by line and without the word WARNING (side_note_text), so --debug
    # adds no WARNING line to a clean run's log.
    for line in side_output_text(stdout).splitlines():
        logging.debug(f"side output: {side_note_text(line)}")
    logging.debug("return code = " + rc)
    if rc != "0":
        logging.error("command failed : terminating")
    return rc, stdout


def valid_date(s, format= "%Y-%m-%d"):
    try:
        try :
            return datetime.strptime(s, format)
        except ValueError:
            return datetime.strptime(s, "%Y/%m/%d")
    except ValueError:
        msg = "Not a valid date: '{0}'.".format(s)
        raise argparse.ArgumentTypeError(msg)


def non_negative_int(s):
    try:
        value = int(s)
    except ValueError:
        raise argparse.ArgumentTypeError(f"Not an integer: '{s}'.")
    if value < 0:
        raise argparse.ArgumentTypeError(f"Must be 0 or more: '{s}'.")
    return value

# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)

def main():
    parser = argparse.ArgumentParser(description='Parse and display a database configuration file')
    parser.add_argument('--config_file', help='Path to the YAML configuration file', required=True)
    # it can be useful to specify a date to checksum a subset of the date based on a partition by date
    parser.add_argument('--partition_date', help='date of partition - format yyyy/mm/dd', type=valid_date, required=False, default=None)
    parser.add_argument('--mysql_user', help='MySQL user', required=False)
    parser.add_argument('--defaults_file',
                        help='MySQL config file default is ~/.my.cnf', required=False, default='~/.my.cnf')
    parser.add_argument('--mysql_database',
                        help='MySQL database', required=False)
    parser.add_argument('--mysql_port', help='MySQL port',
                        default=3306, required=False)
    parser.add_argument('--tables_regex', help='table regexp', required=False, default='.')
    parser.add_argument('--exclude_tables_regex',
                        help='exclude table regexp', required=False)
    parser.add_argument('--include_partitions_regex', help='partitions regex', required=False, default=None)
    parser.add_argument('--non_partitioned_tables_only', dest='non_partitioned_tables_only', action='store_true', default=False)
    parser.add_argument('--clickhouse_user',
                        help='ClickHouse user', required=False)
    parser.add_argument('--clickhouse_config_file',
                        help='CH config file either xml or yaml, default is ./clickhouse-client.xml', required=False, default='./clickhouse-client.xml')
    parser.add_argument('--clickhouse_database',
                        help='ClickHouse database', required=False, default=None)
    parser.add_argument('--clickhouse_port',
                        help='ClickHouse port', default=9000, required=False)
    parser.add_argument('--secure',
                        help='True or False', default=False, required=False)
    parser.add_argument('--threads_per_table', type=int,
                        help='number of parallel threads per table', default=1)
    parser.add_argument('--chunk_size', type=int, help='Chunk size', default=10000)
    parser.add_argument('--threads', type=int,
                        help='number of tables in parallel to compute', default=1)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--debug_output', dest='debug_output',
                        action='store_true', default=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Use --tables_regex as the table', required=False)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--lock_tables_on_source', action='store_true', default=False,
                        help='Lock the table on the source so that source and target are in sync ...', required=False)
    parser.add_argument('--sleep_after_lock', type=int, help='When locking, sleeping n seconds', default=3)
    parser.add_argument('--source_timezone', help='IANA time zone the connector interprets MySQL DATETIME values in (its database.connectionTimeZone); default: resolved from the MySQL server', required=False, default=None)
    parser.add_argument('--binary_encoding', choices=['hex', 'base64', 'raw'], default='hex', required=False,
                        help='how the connector wrote binary values: hex text (default), base64 text (binary.handling.mode=base64) or raw bytes (persist.raw.bytes=true); passed to both sides')
    parser.add_argument('--min_datetime_value', required=False, default=None,
                        help='Lower clamp bound for DATETIME/TIMESTAMP values, a UTC instant YYYY-MM-DD[ HH:MM:SS[.ffffff]]; passed to both sides only when given. Every value earlier than it compares as the bound on both sides, so differences below it are not detected. Default: not passed, each side uses the ClickHouse DateTime64 minimum (1900-01-01 00:00:00)')
    parser.add_argument('--max_datetime_value', required=False, default=None,
                        help='Upper clamp bound for DATETIME/TIMESTAMP values, a UTC instant YYYY-MM-DD[ HH:MM:SS[.ffffff]]; passed to both sides only when given. Every value at or after it compares as the bound on both sides, so differences above it are not detected; for example 2299-12-31 makes every value on 2299-12-31 compare equal whatever its time of day. Default: not passed, each side uses the ClickHouse DateTime64 maximum (2299-12-31 23:59:59)')
    parser.add_argument('--include_floating_point_columns', action='store_true', default=False,
                        help='compare floating point columns (text renderings, not guaranteed identical in exponent notation); passed to both sides')
    parser.add_argument('--include_json_columns', action='store_true', default=False,
                        help='compare JSON columns (best-effort text rendering on the MySQL side); passed to both sides')
    parser.add_argument('--lock_wait_timeout', type=int, default=30,
                        help='Seconds to wait for the source READ lock on each table before giving up on that table (default 30). Bounds the wait so a continuously-written table fails fast instead of stalling the whole run for the server-default timeout.')
    parser.add_argument('--fail_on_lock_timeout', action='store_true', default=False,
                        help='Abort the whole run if any table cannot be locked within --lock_wait_timeout. Default is to log a loud COVERAGE GAP warning, skip that one table, and keep checksumming the rest so a single continuously-written table does not fail the whole job.')
    parser.add_argument('--allow_replica_only_columns', action='store_true', default=False,
                        help='No-op, kept for drop-in compatibility. Columns that exist on a ClickHouse replica table but not in the source table (added on the destination only) are always out of parity scope: they are never compared and are logged once per table at INFO as tolerated, with no effect on the exit code, whether or not this flag is passed.')
    parser.add_argument('--fail_on_empty', action='store_true', default=False,
                        help='Exit non-zero when a table compared 0 rows on both sides (verdict EMPTY). Default: EMPTY is logged at INFO and the run exits 0, since empty partitions are normal in date-partitioned runs.')
    parser.add_argument('--recheck_differences', type=non_negative_int, default=1,
                        help='How many more times a table whose checksums differ is checksummed before it is reported as a "Checksum difference" (default 1, 0 disables). A table written during the run is read by each side at a different moment, so a single pass can differ although no row diverges.')
    parser.add_argument('--recheck_delay_seconds', type=non_negative_int, default=60,
                        help='Seconds to wait before each re-check of a differing table (default 60), so the connector can apply the writes the first pass raced with.')
    parser.add_argument('--wait_for_connector', action='store_true', default=False,
                        help='Before reading a replica, wait until its connector has applied the source binary log up to the position read at the start of the table (under --lock_tables_on_source: read under the lock, so the comparison is exact). Needs replicas[].clickhouse.offset_table in the config and REPLICATION CLIENT on the source.')
    parser.add_argument('--consistent_snapshot', action='store_true', default=False,
                        help='Compare each table in PK-range slices without any lock: each slice is read on MySQL in one START TRANSACTION WITH CONSISTENT SNAPSHOT, the connector is awaited up to that snapshot\'s binlog position, then the replica reads the slice; a differing slice is read again (--recheck_differences). Needs replicas[].clickhouse.offset_table. Not combinable with --lock_tables_on_source.')
    parser.add_argument('--snapshot_slice_rows', type=non_negative_int, default=1000000,
                        help='Approximate rows per slice under --consistent_snapshot (default 1000000; 0 = one slice per table); a slice holds its read view only while it is read. A table with more rows gets at least --threads_per_table slices, cut at the quantiles of a sample of its keys. Slices run --threads_per_table at a time.')
    parser.add_argument('--snapshot_max_excluded_keys', type=non_negative_int, default=5000,
                        help='Under --consistent_snapshot, keys a replica changed while their slice was read are left out on both sides (version fence, needs _version on the replica table); above this many in one slice none are left out and the slice is compared as is (default 5000; the list is sent to the MySQL side, the replica leaves the same keys out by a subquery on _version).')
    parser.add_argument('--fence_timeout_seconds', type=non_negative_int, default=300,
                        help='Longest wait for a connector to reach a position (default 300); after it the comparison runs anyway and a difference names the connector position.')
    parser.add_argument('--fence_poll_seconds', type=non_negative_int, default=1,
                        help='Seconds between reads of the connector offset (default 1).')
    parser.add_argument('--fence_idle_seconds', type=non_negative_int, default=10,
                        help='When the source wrote nothing after the position and the connector offset has not moved for this long, nothing is in flight and the wait ends (default 10). Later waits reuse that verdict at once while the source binary log head and the offset are both unchanged.')

    global args
    args = parser.parse_args()
    if args.consistent_snapshot and args.lock_tables_on_source:
        parser.error("--consistent_snapshot takes no lock: do not combine it with --lock_tables_on_source")
    if args.consistent_snapshot and args.debug_output:
        parser.error("--debug_output writes per-row files and prints no checksum per slice: use it without --consistent_snapshot")

    root = logging.getLogger()
    root.setLevel(logging.INFO)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)

    formatter = logging.Formatter(
        '%(asctime)s - %(levelname)s - %(threadName)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    if args.debug:
        root.setLevel(logging.DEBUG)
        handler.setLevel(logging.DEBUG)

    # After the handler is attached, so a bound confined to the ClickHouse
    # range logs its WARNING into the run log.
    bounds = resolve_datetime_bounds(args, parser)
    if bounds:
        logging.info(f"Datetime clamp bounds passed to both sides: [{bounds[0]}, {bounds[1]}] (UTC instants); "
                     f"values outside them compare as the bound")

    # Parse the configuration file
    config = parse_config(args.config_file)

    # Validate the configuration
    if validate_config(config):
        logging.info("Configuration is valid.")
        run_config(config)
    else:
        print("Invalid configuration. Please check your YAML file.")
        sys.exit(1)

if __name__ == "__main__":
    main()

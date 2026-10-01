#!/usr/bin/env python3
import yaml
import sys
import argparse
import logging
from db.mysql import *
from db.checksum_common import validate_timezone, JSON_COLUMNS_HINT_RE
import concurrent.futures
from datetime import datetime
from subprocess import Popen, PIPE
import subprocess
import time
import re
import shlex
import traceback


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


def relay_side_messages(data, host, table):
    """Re-log the side script's ERROR/CRITICAL lines at ERROR and its WARNING
    lines (columns not compared, clamped values, SQL warnings) at INFO as side
    notes: they qualify the verdict (spec 13.06 FM-13.06-8). Returns True when
    the side logged an ERROR or CRITICAL line."""
    side_failed = False
    for line in side_output_text(data).splitlines():
        if any(marker in line for marker in SIDE_ERROR_MARKERS):
            logging.error(f"{host} {table} side: {line.strip()}")
            side_failed = True
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


def run_quick_safe_checksum(cmd, host, table):
    start = time.perf_counter()
    (rc, stdout) = run_quick_safe_command(cmd)
    duration = time.perf_counter() - start
    side_failed = relay_side_messages(stdout, host, table)
    if rc == '0' and side_failed:
        logging.error(f"{command_text(cmd)}. logged an ERROR although it exited 0: no result from {host} for {table}")
        log_side_output_tail(stdout, host, table)
        return None
    if rc == '0':
        (table, checksum, count) = parse_checksum(stdout, table, expected_side_name(cmd, table))
        if checksum is None:
            log_side_output_tail(stdout, host, table)
        logging.info(f"{( host, table, checksum, count)} in {duration:0.3f} seconds" )
        return ( host, table, checksum, count)
    else:
        logging.error(f"{command_text(cmd)}. failed with return code {rc}")
        log_side_output_tail(stdout, host, table)
        return None



def compute_checksum (mysql_database, database_override_map, table_overrides_map,  table, mysql_user, mysql_password, mysql_host, replica_hosts, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None, partition_key = None, lock_enabled=False, sleep_after_lock=3, mysql_port=3306, timestamp_columns=(), binary_columns=(), json_columns=(), lock_wait_timeout=None):
    table_name = f"{mysql_database}.{table}"
    logging.info(f"Checksumming {table_name}")
    if table_name in table_overrides_map and 'where' in table_overrides_map[table_name]:
        if where is None:
            where =''
        if where != '':
            where+= " and "
        logging.info(f"Where override found for {table_name}")
        where+= table_overrides_map[table_name]['where']

    # Build MySQL checksum command
    mysql_cmd = get_mysql_checksum_command(mysql_host, mysql_database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output, defaults_file=defaults_file)

    # Build ClickHouse checksum commands
    ch_commands = []
    for replica_host in replica_hosts:
        replica_database = mysql_database
        if replica_host in database_override_map and mysql_database in database_override_map[replica_host]:
            override_map = database_override_map[replica_host]
            database_maps = override_map.split(',')
            for database_map in database_maps:
                (source_db, target_db) = database_map.split(':')
                if source_db == mysql_database:
                    replica_database = target_db
                    break
            logging.info(f"Overriding database for host {replica_host} from {mysql_database} to {replica_database}")
        cmd = get_clickhouse_checksum_command(replica_host, replica_database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output, partition_key = partition_key, timestamp_columns=timestamp_columns, binary_columns=binary_columns, json_columns=json_columns)
        ch_commands.append((replica_host, cmd))

    # Lock held during all checksums (MySQL source + ClickHouse replicas)
    # to ensure a consistent comparison. The sleep_after_lock allows
    # replication lag to settle before checksumming — unlocking early would
    # let new writes reach ClickHouse and invalidate the comparison.
    lock_conn = None
    try:
        if lock_enabled:
            lock_conn = get_mysql_connection(mysql_host, mysql_user,
                                             mysql_password, mysql_port, mysql_database)
            logging.info(f"Locking table {table} on source {mysql_host}")
            lock_tables(lock_conn, table, lock_wait_timeout=lock_wait_timeout)
            time.sleep(sleep_after_lock)

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
        if lock_conn:
            try:
                unlock_tables(lock_conn, table)
            finally:
                close_connection(lock_conn, table_name)


def get_tables_from_regexp(conn, database, tables_regexp):
    return get_tables_from_regex(conn, args.no_wc, database, tables_regexp, include_partitions_regex=args.include_partitions_regex, exclude_tables_regex=args.exclude_tables_regex, non_partitioned_tables_only=args.non_partitioned_tables_only)


def include_flags():
    """The coverage opt-ins, passed identically to both sides (spec 11.02 section 3.9)."""
    flags = []
    if args.include_floating_point_columns:
        flags.append("--include_floating_point_columns")
    if args.include_json_columns:
        flags.append("--include_json_columns")
    return flags


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


def get_mysql_checksum_command(mysql_host, database, table, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None):
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
    cmd = ["python", "db_compare/mysql_table_checksum.py",
           "--threads_per_table", str(args.threads_per_table), f"--threads={args.threads}",
           "--min_date_value", "1900-01-01", "--mysql_host", str(mysql_host), "--mysql_database", str(database),
           "--tables_regex", exact_table_regex(table), "--where", where_value,
           "--source_timezone", str(args.source_timezone), "--binary_encoding", str(args.binary_encoding)]
    cmd += include_flags() + ignored_columns_clause + debug_output_clause + defaults_file_clause
    logging.debug(f"MySQL command: {command_text(cmd)}")
    return cmd



def get_clickhouse_checksum_command(replica_host, database, table, pk, max_pk, where=None, ignored_columns=[], debug_output=False, partition_key = None, timestamp_columns=(), binary_columns=(), json_columns=()):
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
    binary_encoding_clause = ["--binary_encoding", str(args.binary_encoding)]
    if args.binary_encoding == 'raw' and binary_columns:
        binary_encoding_clause += ["--hex_columns", ",".join(binary_columns)]

    # String columns that replicate MySQL JSON (spec 11.02 section 3.9).
    json_columns_clause = []
    if json_columns:
        json_columns_clause = ["--json_columns", ",".join(json_columns)]
    # An argv list, run without a shell (see get_mysql_checksum_command).
    cmd = ["python", "db_compare/clickhouse_table_checksum.py",
           "--max_memory_usage", "80000000000", f"--threads={args.threads}",
           "--clickhouse_host", str(replica_host), "--clickhouse_database", str(database),
           "--tables_regex", exact_table_regex(table), "--where", where_value,
           "--source_timezone", str(args.source_timezone)]
    cmd += timestamp_columns_clause + json_columns_clause + binary_encoding_clause + include_flags()
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


def analyze_differences(results, mysql_host, replica_hosts, table_name=None):
    """Log and return the table's verdict: MATCH, DIFFERENT, EMPTY or ERROR.

    ``results`` is in submission order: the MySQL source first, then one entry
    per replica in ``replica_hosts`` order. The source is identified by that
    position, never by its host string, so a replica configured with the same
    host string as MySQL still gets a verdict (spec 13.06 FM-13.06-4). A side
    that failed (None) or printed no parseable checksum (None md5 or count)
    makes the table an ERROR, never a match (FM-13.06-1, FM-13.06-2). Equal
    results with zero rows are EMPTY, not a match (FM-13.06-5)."""
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
            logging.warning(f"Checksum difference : {replica_result} to {source_result}")
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


def report_run_summary(verdicts, fail_on_empty):
    """Log the per-verdict totals; return the process exit code.

    ERROR (a table with no verdict) always fails the run. EMPTY fails it only
    with --fail_on_empty. DIFFERENT keeps exit 0 (spec 11.02 FM-11.02-1)."""
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
    verdicts = {}
    for database in databases:
        logging.info(f"Using MySQL database: {database}")
        try:
            conn = get_mysql_connection(mysql_host, mysql_user,
                                    mysql_password, args.mysql_port, database)
            args.source_timezone = resolve_source_timezone(conn, args.source_timezone)
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
                    if args.binary_encoding == 'raw':
                        binary_columns = mysql_columns_by_data_type(conn, database, table, binary_datatypes)
                    json_columns = mysql_columns_by_data_type(conn, database, table, ('json',))
                    ignored_columns = []
                    if database in ignored_columns_map and table in ignored_columns_map[database]:
                        ignored_columns = list(ignored_columns_map[database][table].keys())

                    logging.info(f"Ignored columns for table {table_name}: {ignored_columns}")
                    # Lock lifecycle is now managed inside compute_checksum per table.
                    # Each future acquires its own lock, runs both MySQL and
                    # ClickHouse checksums under the lock, then releases it.
                    future = executor.submit(
                        compute_checksum, database, database_override_map, table_overrides_map, table, mysql_user, mysql_password, mysql_host, replica_hosts, pk_column, max_pk, args.where, ignored_columns = ignored_columns, debug_output = args.debug_output, defaults_file=args.defaults_file, partition_key = partition_key, lock_enabled=args.lock_tables_on_source, sleep_after_lock=args.sleep_after_lock, mysql_port=args.mysql_port, timestamp_columns=timestamp_columns, binary_columns=binary_columns, json_columns=json_columns, lock_wait_timeout=args.lock_wait_timeout)
                    futures.append(future)
                    future_to_table[future] = table_name
                skipped_tables = []
                for future in concurrent.futures.as_completed(futures):
                    table_name = future_to_table[future]
                    exc = future.exception()
                    if exc is not None:
                        if isinstance(exc, LockAcquisitionError) and not args.fail_on_lock_timeout:
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
                        logging.error("Exception in table " + table_name)
                        logging.error(exc)
                        raise exc
                    else:
                        verdicts[table_name] = analyze_differences(future.result(), mysql_host, replica_hosts, table_name)
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

def run_quick_safe_command(cmd):
    """Run a side command given as an argv list (no shell); return (rc, output)."""
    logging.debug("cmd " + command_text(cmd))
    try:
        process = subprocess.Popen(cmd,
                                   stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT)
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
    parser.add_argument('--include_floating_point_columns', action='store_true', default=False,
                        help='compare floating point columns (text renderings, not guaranteed identical in exponent notation); passed to both sides')
    parser.add_argument('--include_json_columns', action='store_true', default=False,
                        help='compare JSON columns (best-effort text rendering on the MySQL side); passed to both sides')
    parser.add_argument('--lock_wait_timeout', type=int, default=30,
                        help='Seconds to wait for the source READ lock on each table before giving up on that table (default 30). Bounds the wait so a continuously-written table fails fast instead of stalling the whole run for the server-default timeout.')
    parser.add_argument('--fail_on_lock_timeout', action='store_true', default=False,
                        help='Abort the whole run if any table cannot be locked within --lock_wait_timeout. Default is to log a loud COVERAGE GAP warning, skip that one table, and keep checksumming the rest so a single continuously-written table does not fail the whole job.')
    parser.add_argument('--fail_on_empty', action='store_true', default=False,
                        help='Exit non-zero when a table compared 0 rows on both sides (verdict EMPTY). Default: EMPTY is logged at INFO and the run exits 0, since empty partitions are normal in date-partitioned runs.')

    global args
    args = parser.parse_args()

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
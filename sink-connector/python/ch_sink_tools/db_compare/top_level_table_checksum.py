#!/usr/bin/env python3
import yaml
import os
import sys
import argparse
import logging
import shlex
import traceback
from ch_sink_tools.db.mysql import (
    get_tables_from_regex, execute_mysql, resolve_credentials_from_config,
    get_mysql_connection, mysql_pk_columns, get_min_max_pk_value,
    get_table_partition_key,
)
import concurrent.futures
import functools
from datetime import datetime
from subprocess import Popen, PIPE
import subprocess
import time
import re

# The directory holding the ch_sink_tools package this driver was imported
# from. The side scripts are run as `<this interpreter> -m
# ch_sink_tools.db_compare.<side>` with it first on PYTHONPATH, so they are the
# packaged modules of the same installation whatever the cwd (spec 13.06
# FM-13.06-1).
PACKAGE_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MYSQL_SIDE_MODULE = "ch_sink_tools.db_compare.mysql_table_checksum"
CLICKHOUSE_SIDE_MODULE = "ch_sink_tools.db_compare.clickhouse_table_checksum"

# The ClickHouse DateTime64 range the connector clamps to when it writes. Both
# sides receive the same bounds, so only values ClickHouse cannot hold are
# clamped, identically on both sides (spec 13.06 FM-13.06-6).
DATETIME_RANGE_MIN = "1900-01-01 00:00:00"
DATETIME_RANGE_MAX = "2299-12-31 23:59:59"


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


# The MySQL side's hint to pass --json_columns to the ClickHouse side (spec 13.06 D-13.06-41).
JSON_COLUMNS_HINT_RE = re.compile(r"; pass --json_columns .*? to clickhouse_table_checksum\.py so both row strings "
                                  r"skip them")


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


def compute_checksum (mysql_database, database_override_map, table_overrides_map,  table, mysql_user, mysql_password, mysql_host, replica_hosts, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None, partition_key = None, json_columns=()):
    table_name = f"{mysql_database}.{table}"
    logging.info(f"Checksumming {table_name}")
    commands = []
    if table_name in table_overrides_map and 'where' in table_overrides_map[table_name]:
        if where is None:
            where =''
        if where != '':
            where+= " and "
        logging.info(f"Where override found for {table_name}")
        where+= table_overrides_map[table_name]['where']
    cmd = get_mysql_checksum_command(mysql_host, mysql_database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output, defaults_file=defaults_file)
   
    commands.append((mysql_host,cmd))
    
    for ch_host in replica_hosts:
        ch_database = mysql_database
        if ch_host in database_override_map and mysql_database in database_override_map[ch_host]:
            override_map = database_override_map[ch_host]
            database_maps = override_map.split(',')
            for database_map in database_maps:
                (source_db, target_db) = database_map.split(':')
                if source_db == mysql_database:
                    ch_database = target_db
                    break
            logging.info(f"Overriding database for host {ch_host} from {mysql_database} to {ch_database}")
        cmd = get_clickhouse_checksum_command(ch_host, ch_database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output, partition_key = partition_key, json_columns=json_columns)
        commands.append((ch_host, cmd))
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(commands)) as executor:
            futures = []
            for (host, cmd) in commands:
                future = executor.submit(
                    run_quick_safe_checksum, cmd, host, table)
                futures.append(future)
            for future in concurrent.futures.as_completed(futures):
                if future.exception() is not None:
                    raise future.exception()
            # Submission order: the MySQL source first, then the replicas in
            # replica_hosts order. analyze_differences identifies the source by
            # this position, not by its host string.
            results = [future.result() for future in futures]
    return results


def get_tables_from_regexp(conn, database, tables_regexp):
    return get_tables_from_regex(conn, args.no_wc, database, tables_regexp, include_partitions_regex=args.include_partitions_regex, exclude_tables_regex=args.exclude_tables_regex, non_partitioned_tables_only=args.non_partitioned_tables_only)


# Characters that are regex syntax in MySQL (ICU), ClickHouse (re2) and Python.
# Each is matched literally as a one-character class: a backslash escape would
# be consumed by the SQL string literal the sides paste the regex into.
TABLE_REGEX_SPECIALS = set(".$|?*+(){}")


def exact_table_regex(table):
    """``^<table>$`` matching exactly that table name (``orders$archive`` must
    not become ``^orders$``)."""
    return "^" + "".join(f"[{ch}]" if ch in TABLE_REGEX_SPECIALS else ch for ch in table) + "$"


def side_command(module):
    """argv prefix running a packaged side module with this interpreter."""
    return [sys.executable, "-m", module]


def mysql_json_columns(conn, mysql_database, mysql_table):
    """Names of the table's MySQL JSON columns, in ordinal order."""
    sql = ("select column_name from information_schema.columns where table_schema='" + mysql_database +
           "' and table_name = '" + mysql_table + "' and data_type = 'json' order by ordinal_position")
    (rowset, rowcount) = execute_mysql(conn, sql)
    return [row[0] for row in rowset]


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
    # An argv list, run without a shell: every value reaches the side byte for
    # byte (no `$` or backtick expansion, no word splitting), the return code
    # is the side's own, and its output is parsed in Python, keeping its
    # WARNING lines (spec 13.06 FM-13.06-1, -3, -8).
    cmd = side_command(MYSQL_SIDE_MODULE) + [
        "--threads_per_table", str(args.threads_per_table), f"--threads={args.threads}",
        "--min_date_value", "1900-01-01", "--mysql_host", str(mysql_host), "--mysql_database", str(database),
        "--tables_regex", exact_table_regex(table), "--where", where_value,
        "--min_datetime_value", DATETIME_RANGE_MIN, "--max_datetime_value", DATETIME_RANGE_MAX,
        "--binary_encoding", "base64"]
    cmd += ignored_columns_clause + debug_output_clause + defaults_file_clause
    logging.debug(f"MySQL command: {command_text(cmd)}")
    return cmd


def get_clickhouse_checksum_command(ch_host, database, table, pk, max_pk, where=None, ignored_columns=[], debug_output=False, partition_key = None, json_columns=()):
    partition_date = args.partition_date
    where_value = " 1=1 "
    if where:
        where_value += f" and {where} "
    if partition_date:
      # The packaged side's fstr() evaluates the where as a Python f-string,
      # which turns \' into '. This is the text the side received when the
      # command went through a shell.
      where_value += f""" and {{partition_expression}}=toDate(\\'{partition_date:%Y-%m-%d}\\') """

    ignored_columns_value = "_version,is_deleted,_is_deleted,__is_deleted"
    if len(ignored_columns) > 0:
        logging.info(f"Ignoring columns {ignored_columns} for table {table}")
        ignored_columns_value += ","+",".join(ignored_columns)

    debug_output_clause = []
    if debug_output:
        debug_output_clause = ["--debug_output"]

    partition_key_clause = []
    if partition_key:
        partition_key_clause = ["--partition_key", partition_key.replace('`','')]

    # String columns that replicate MySQL JSON: not compared by default on
    # either side (spec 13.06 FM-13.06-7).
    json_columns_clause = []
    if json_columns:
        json_columns_clause = ["--json_columns", ",".join(json_columns)]
    cmd = side_command(CLICKHOUSE_SIDE_MODULE) + [
        "--max_memory_usage", "80000000000", f"--threads={args.threads}",
        "--clickhouse_host", str(ch_host), "--clickhouse_database", str(database),
        "--tables_regex", exact_table_regex(table), "--where", where_value,
        "--min_datetime_value", DATETIME_RANGE_MIN, "--max_datetime_value", DATETIME_RANGE_MAX,
        "--exclude_columns", ignored_columns_value, "--sign_column", ""]
    cmd += json_columns_clause + debug_output_clause + partition_key_clause
    return cmd


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


    
def lock_tables(conn, table):
    lock_stmt = f"FLUSH TABLE `{table}` WITH READ LOCK"
    logging.info(f"Locking table with statement {lock_stmt}")
    execute_mysql(conn, lock_stmt)
    
    
def unlock_tables(conn, table):
    unlock_stmt = "UNLOCK TABLES"
    logging.info(f"Unlocking table {table} with statement {unlock_stmt}")
    execute_mysql(conn, unlock_stmt)

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
            table_overrides_map[table]['where'] = table_dict[table]['where']
    logging.info(f"Table overrides : {table_overrides_map}")
    verdicts = {}
    for database in databases:
        logging.info(f"Using MySQL database: {database}")
        try:
            conn = get_mysql_connection(mysql_host, mysql_user,
                                    mysql_password, args.mysql_port, database)
            tables = get_tables_from_regexp(conn, database, args.tables_regex)
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
                futures = []
                future_to_table = {}
                future_to_conn = {}
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
                    if args.lock_tables_on_source:
                        # we need a new connection for each table since the lock is per connection
                        conn = get_mysql_connection(mysql_host, mysql_user,
                                    mysql_password, args.mysql_port, database)
                        logging.info(f"Locking table {table} on source {mysql_host}")
                        lock_tables(conn, table)
                        time.sleep(args.sleep_after_lock)  # slight delay for the sink-connector to catch up
                        future_to_conn[table_name] = conn
                        
                    pk = mysql_pk_columns(conn, database, table)
                    pk_column = pk[0] if len(pk) > 0 else 'NULL'
                    (min_pk, max_pk) = get_min_max_pk_value(conn, table, pk_column, '1=1')
                    partition_key = get_table_partition_key(conn, database, table)
                    json_columns = mysql_json_columns(conn, database, table)
                    ignored_columns = []
                    if database in ignored_columns_map and table in ignored_columns_map[database]:
                        ignored_columns = list(ignored_columns_map[database][table].keys())
                        
                    logging.info(f"Ignored columns for table {table_name}: {ignored_columns}")
                    checksum = functools.partial(
                        compute_checksum, database, database_override_map, table_overrides_map, table, mysql_user, mysql_password, mysql_host, replica_hosts, pk_column, max_pk, args.where, ignored_columns = ignored_columns, debug_output = args.debug_output, defaults_file=args.defaults_file, partition_key = partition_key, json_columns=json_columns)
                    future = executor.submit(
                        verify_table, checksum, mysql_host, replica_hosts, table_name,
                        *recheck_settings(args))
                    futures.append(future)
                    future_to_table[future] = table_name
                for future in concurrent.futures.as_completed(futures):
                    conn =  future_to_conn.get(future_to_table[future], None)
                    table_name = future_to_table[future]
                    if future.exception() is not None:
                        logging.error("Exception in table " + table_name)
                        logging.error(future.exception())
                        if conn:
                            unlock_tables(conn, table_name)
                        raise future.exception()
                    else:
                        if conn:
                            unlock_tables(conn, table_name)
                        verdicts[table_name] = future.result()

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
        except:
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
    parser.add_argument('--fail_on_empty', action='store_true', default=False,
                        help='Exit non-zero when a table compared 0 rows on both sides (verdict EMPTY). Default: EMPTY is logged at INFO and the run exits 0, since empty partitions are normal in date-partitioned runs.')
    parser.add_argument('--recheck_differences', type=non_negative_int, default=1,
                        help='How many more times a table whose checksums differ is checksummed before it is reported as a "Checksum difference" (default 1, 0 disables). A table written during the run is read by each side at a different moment, so a single pass can differ although no row diverges.')
    parser.add_argument('--recheck_delay_seconds', type=non_negative_int, default=60,
                        help='Seconds to wait before each re-check of a differing table (default 60), so the connector can apply the writes the first pass raced with.')

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

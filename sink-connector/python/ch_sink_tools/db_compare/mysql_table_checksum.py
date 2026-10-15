# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : mysql_table_checksum
# -- Date         :
# -- Summary      : calculate a checksum for a mysql table 
# -- Credits      : https://www.sisense.com/blog/hashing-tables-to-ensure-consistency-in-postgres-redshift-and-mysql/
# --                
"""
import logging
import argparse
import traceback
import sys
import datetime
import json
import re
import os
import concurrent.futures
from ch_sink_tools.db.mysql import (
    execute_mysql, is_binary_datatype, get_tables_from_regex,
    get_mysql_connection, mysql_pk_columns, get_table_partition_key,
    divide_table_into_even_chunks, resolve_credentials_from_config,
)
from ch_sink_tools.db.checksum_common import (checksum_from_aggregate, DATETIME_MIN, DATETIME_MAX, datetime_bounds,
                                clamp_datetime_expression, clamped_datetime_flag, clamped_count_expression,
                                validate_timezone, shift_datetime_bounds, saturation_floor, warn_not_compared,
                                parse_exclude_columns)
runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def compute_checksum(table, statements, conn):
    """Run ``statements``; return the aggregate row as a flat list.

    The first statement that returns rows is the aggregate. With
    --debug_output, select_table_statements adds the per-row query after it:
    its rows are appended to out.<table>.mysql.txt and the aggregate is still
    returned, so the checksum line is printed as well (spec 13.06 D-13.06-27)."""
    result = None
    for statement in statements:
        (rows, rowcount) = execute_mysql(conn, statement)
        if rowcount != -1:
            logging.debug("Rows affected "+str(rowcount))
        if rows is None or rows.returns_rows != True:
            continue
        x = [element for tupl in rows for element in tupl]
        if result is None:
            result = x
        else:
            with open(f"out.{table}.mysql.txt", 'a') as debug_out:
                for line in x:
                    debug_out.write(str(line)+'\n')
    # the caller owns the connection lifecycle
    return result


# information_schema DATA_TYPE keywords of the IEEE 754 types (REAL is an alias
# of DOUBLE and never appears as a DATA_TYPE).
FLOATING_POINT_DATA_TYPES = frozenset({"float", "double"})

# (database, table, kind) already reported as not compared; a chunked table
# builds its expression once per chunk and must warn once (spec 11.02 section 3.9).
warned_tables = set()


def mysql_datetime_rendering(column_name):
    """Canonical text of a DATETIME / TIMESTAMP column (spec 11.02 section 3.4):
    'YYYY-MM-DD HH:MM:SS.ffffff', six digits for every declared precision.
    TIMESTAMP is rendered in the session time zone."""
    return f"date_format({column_name}, '%Y-%m-%d %H:%i:%s.%f')"


def mysql_column_expression(column, options, binary_encoding, same_charset, bounds=(DATETIME_MIN, DATETIME_MAX),
                            saturate_from=None):
    """Text rendering of one MySQL column (spec 11.02 section 3.3).

    ``column`` is an ``information_schema.columns`` row. Classification uses
    ``data_type`` (DATA_TYPE, the bare type keyword) and ``datetime_precision``;
    ``column_type`` (COLUMN_TYPE) carries user text such as enum/set labels and
    is never substring-matched -- an enum('float','json') is a string column.
    ``bounds`` are the canonical (min, max) datetime clamp bounds and
    ``saturate_from`` the start of the saturated last day (``saturation_floor()``).
    """
    column_name = '`' + column['column_name'] + '`'
    data_type = column['data_type'].lower()
    collation = column['collation']
    min_date_value = options.min_date_value
    max_date_value = options.max_date_value
    (min_datetime_value, max_datetime_value) = bounds
    if data_type == 'json':
        # convert to a compact representation, best effort, it is not perfect and it is advised to ignore those columns
        # https://bugs.mysql.com/bug.php?id=118990
        # https://github.com/Altinity/clickhouse-sink-connector/issues/1137
        return f"""REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(convert(json_pretty({column_name}) using utf8mb4),'": "','":"'),'":\\\\s(-*\\\\d|\\\\[|\\\\{{|true|false)','":$1'),'\\\\.0\\\\b',''),'\\\\s+(".*?)\\\\s*','$1'),'\\\\s*\\\\n\\\\s*',''), '\\\\\\\\u([0-9A-F]{{3}})a', '\\\\\\\\u$1A'), '\\\\\\\\u([0-9A-F]{{3}})b', '\\\\\\\\u$1B'), '\\\\\\\\u([0-9A-F]{{3}})c', '\\\\\\\\u$1C'), '\\\\\\\\u([0-9A-F]{{3}})d', '\\\\\\\\u$1D'), '\\\\\\\\u([0-9A-F]{{3}})e', '\\\\\\\\u$1E'), '\\\\\\\\u([0-9A-F]{{3}})f', '\\\\\\\\u$1F')"""
    if data_type in ('datetime', 'timestamp'):
        # ClickHouse DateTime64 cannot hold the whole MySQL range; the connector
        # clamps on write, so the rendered text is clamped to the same bounds here.
        return clamp_datetime_expression(mysql_datetime_rendering(column_name),
                                         min_datetime_value, max_datetime_value, 'mysql', saturate_from)
    if data_type == 'time':
        # The connector stores TIME as [-]HH:MM:SS.ffffff for every declared
        # precision (spec 07.03 section 3.2), so render six digits unconditionally.
        return f"cast({column_name} as time(6))"
    if data_type == 'date':  # Date are converted to Date32 in CH
        # CH date range is not the same as MySQL https://clickhouse.com/docs/en/sql-reference/data-types/date
        return f"case when {column_name} >='{max_date_value}' then CAST('{max_date_value}' AS date) else case when {column_name} <= '{min_date_value}' then CAST('{min_date_value}' AS date) else {column_name} end end"
    if data_type == 'bit' and column['column_type'].lower() == 'bit(1)':
        # Debezium emits BIT(1) as BOOLEAN and the connector stores Bool;
        # ClickHouse renders it as 1 / 0 (toUInt8), so render the bit as an integer.
        return f"{column_name}+0"
    if data_type == 'bit':
        # BIT(n>1): the connector stores lower-case hex text under every
        # binary.handling.mode (base64 applies to BINARY/VARBINARY/BLOB only), and
        # with --binary_encoding raw the ClickHouse side hexes the raw bytes. A
        # base64 rendering made every table with a BIT(n>1) column DIFFERENT on
        # clean data (spec 13.06 D-13.06-38).
        return "lower(hex(cast(" + column_name + " as binary)))"
    if is_binary_datatype(data_type):
        if binary_encoding == 'base64':
            return "replace(to_base64(cast(" + column_name + " as binary)),'\\n','')"
        return "lower(hex(cast(" + column_name + " as binary)))"
    if same_charset or collation is None:
        return column_name
    return f"convert({column_name} using utf8mb4)"


def build_mysql_row_expression(columns, options, binary_encoding, excluded_columns,
                               include_floating_point_columns, include_json_columns):
    """Build the pieces of the canonical row string from ``information_schema``
    rows in ordinal order (spec 11.02 section 3.3).

    Returns ``(select, nullables, data_types, clamped_expression, skipped)``;
    ``select`` is the comma separated argument list of ``concat_ws('#', ...)``,
    ``clamped_expression`` the per-row count of datetime values the clamp
    changed and ``skipped`` the columns not compared by kind. Skipped columns
    contribute nothing. The null flags are one trailing element with one
    ``ISNULL`` per compared column, nullable or not, so a column declared
    nullable here and non-Nullable on the replica gives the same flags for
    equal values (spec 13.06 D-13.06-40). ``nullables`` lists the compared
    columns declared nullable (the ones whose value is wrapped in ``ifnull``).
    """
    pieces = []
    nullables = []
    compared = []
    data_types = {}
    clamped_flags = []
    skipped = {"floating point": [], "JSON": []}
    # TIMESTAMP is an instant, rendered in UTC by the session (spec 11.02
    # section 3.4) and clamped with the UTC bounds; DATETIME is a wall clock of
    # the source zone and is clamped with the bounds rendered in that zone.
    utc_bounds = datetime_bounds(options)
    wall_clock_bounds = shift_datetime_bounds(utc_bounds, options.source_timezone)
    # The last day of the range is saturated on both sides (spec 11.02 section 3.4).
    utc_floor = saturation_floor(utc_bounds, 'UTC')
    wall_clock_floor = saturation_floor(utc_bounds, options.source_timezone)
    collations = [column['collation'] for column in columns if column['collation'] is not None]
    same_charset = len(collations) <= 1
    for column in columns:
        name = column['column_name']
        column_name = '`' + name + '`'
        data_type = column['data_type'].lower()
        if name in excluded_columns:
            logging.info("Excluding column " + name)
            continue
        if not include_floating_point_columns and data_type in FLOATING_POINT_DATA_TYPES:
            skipped["floating point"].append(name)
            continue
        if not include_json_columns and data_type == 'json':
            skipped["JSON"].append(name)
            continue
        (bounds, floor) = ((utc_bounds, utc_floor) if data_type == 'timestamp'
                           else (wall_clock_bounds, wall_clock_floor))
        expression = mysql_column_expression(column, options, binary_encoding, same_charset, bounds, floor)
        if data_type in ('datetime', 'timestamp'):
            clamped_flags.append(clamped_datetime_flag(mysql_datetime_rendering(column_name), bounds[0], bounds[1],
                                                       floor))
        if column['is_nullable'] == 'YES':
            nullables.append(column_name)
            expression = f"ifnull({expression},'')"
        pieces.append(expression)
        compared.append(column_name)
        data_types[name] = column['column_type']
    logging.debug(str(nullables))
    if len(compared) > 0:
        pieces.append("concat(" + ",".join("ISNULL(" + compared_column + ")" for compared_column in compared) + ")")
    return (",".join(pieces), nullables, data_types, clamped_count_expression(clamped_flags), skipped)


def get_table_checksum_query(table, conn, binary_encoding, where, excluded_columns,  include_floating_point_columns, include_json_columns):

    (rowset, rowcount) = execute_mysql(conn, "select COLUMN_NAME as column_name, DATA_TYPE as data_type, COLUMN_TYPE as column_type, IS_NULLABLE as is_nullable, COLLATION_NAME as collation, DATETIME_PRECISION as datetime_precision from information_schema.columns where table_schema='" +
                                       args.mysql_database+"' and table_name = '"+table+"' order by ordinal_position")

    logging.debug("Excluded columns: "+str(excluded_columns))
    row_list = [row for row in rowset.mappings()]
    (select, nullables, data_types, clamped_expression, skipped) = build_mysql_row_expression(
        row_list, args, binary_encoding, excluded_columns, include_floating_point_columns, include_json_columns)
    # A standalone run is told to pass the JSON columns to the ClickHouse side (spec 13.06 D-13.06-41).
    warn_not_compared(args.mysql_database, table, skipped, warned_tables, json_hint=True)
    # order is not important
    primary_key_columns = []
    logging.debug(str(primary_key_columns))
    order_by_columns = ""
    if len(primary_key_columns) > 0:
        order_by_columns = ','.join(primary_key_columns)

    query = "select "+select+"  as query from `"+args.mysql_database+"`.`"+table+"`"
    if where :
        query += " where "+where

    if len(primary_key_columns) > 0:
        query += " order by " + order_by_columns

    external_column_types = ""
    for column in primary_key_columns:
        external_column_types += ","+column+" "+data_types[column]

    logging.debug("order by columns "+order_by_columns)
    return (query, select, order_by_columns, external_column_types, clamped_expression)


def fstr(template, partition_expression):
        # Safe substitution: only replace {partition_expression} placeholder
        # instead of eval() which allows arbitrary code execution
        if partition_expression is not None:
            return template.replace('{partition_expression}', str(partition_expression))
        return template


def select_table_statements(table, query, select_query, order_by, external_column_types, _where, clamped_expression="0"):
    # time_zone = '+00:00': TIMESTAMP columns are printed as UTC instants
    # (spec 11.02 section 3.4); DATETIME is unaffected by the session zone.
    statements = ['set names utf8mb4', 'set session wait_timeout=28000', "set time_zone = '+00:00'"]
    # todo make sure the fifo is there
    external_table_name = args.mysql_database+"."+table
    limit = ""
    if args.debug_limit:
        limit = " limit "+args.debug_limit
    where = "1=1"
    if _where:
        where = _where

    statements.append(
        """set @md5sum := "", @a := cast(0 as signed), @b:= cast(0 as signed), @c:= cast(0 as signed), @d:=cast(0 as signed)""")

    sql = """
         select
           count(*) as "cnt",
           coalesce(max(a),0) as a,
           coalesce(max(b),0) as b,
	   coalesce(max(c),0) as c,
	   coalesce(max(d),0) as d,
           coalesce(sum(clamped),0) as clamped
         from (
          select @md5sum :=md5( convert(concat_ws('#',{select_query}) using utf8mb4  )) as `hash`,
		   @a:=@a+cast(conv(substring(@md5sum, 1, 8), -16, 10) as signed) as a,
                   @b:=@b+cast(conv(substring(@md5sum, 9, 8), -16, 10) as signed) as b,
		   @c:=@c+cast(conv(substring(@md5sum, 17, 8), -16, 10) as signed) as c,
		   @d:=@d+cast(conv(substring(@md5sum, 25, 8), -16, 10) as signed) as d,
                   {clamped_expression} as clamped
           from {schema}.{table} where {where}
         ) as t;
  """.format(select_query=select_query, schema=args.mysql_database, table=table, where=where, order_by=order_by, limit=limit, clamped_expression=clamped_expression)

    statements.append(sql)
    if args.debug_output:
        # The per-row strings, written to out.<table>.mysql.txt by
        # compute_checksum after the aggregate (spec 13.06 D-13.06-27).
        statements.append("""select concat_ws('#',{select_query})  as `hash`   from {schema}.{table} where  {where}  {limit}""".format(
            select_query=select_query, schema=args.mysql_database, table=table, where=where, order_by=order_by, limit=limit))
    return statements


def binary_log_status_statement(version):
    """SHOW BINARY LOG STATUS from MySQL 8.2 (8.4 removed SHOW MASTER STATUS),
    SHOW MASTER STATUS before. Chosen from @@version, never by trying: a
    failed statement inside the snapshot transaction is avoided."""
    match = re.match(r"(\d+)\.(\d+)", str(version))
    if match and (int(match.group(1)), int(match.group(2))) >= (8, 2):
        return "SHOW BINARY LOG STATUS"
    return "SHOW MASTER STATUS"


def binary_log_head(conn):
    """(file, position) of the end of the binary log. Needs the REPLICATION
    CLIENT privilege; raises when no row comes back (binary log disabled or
    privilege missing)."""
    (rowset, _) = execute_mysql(conn, "SELECT @@version")
    statement = binary_log_status_statement(rowset.fetchone()[0])
    (rowset, _) = execute_mysql(conn, statement)
    row = rowset.fetchone()
    if row is None:
        raise RuntimeError(f"Cannot read the binary log position: {statement} returned no row "
                           "(binary log disabled, or REPLICATION CLIENT missing)")
    return (str(row[0]), int(row[1]))


def snapshot_binlog_position(conn):
    """(file, position, kind) of the binary log for the consistent snapshot
    this session just started.

    Percona Server reports the position of the snapshot itself in the session
    status variables Binlog_snapshot_file / Binlog_snapshot_position: kind
    'exact'. Other servers have no such variables; the end of the binary log
    read right after the snapshot started is used instead: kind 'upper_bound'
    (it can include transactions committed between the two statements, which
    only makes a slice differ and be checked again, never match falsely)."""
    (rowset, _) = execute_mysql(conn, "SHOW SESSION STATUS WHERE Variable_name IN "
                                      "('Binlog_snapshot_file', 'Binlog_snapshot_position')")
    status = {str(row[0]): row[1] for row in rowset}
    if status.get('Binlog_snapshot_file') and status.get('Binlog_snapshot_position') not in (None, ''):
        return (str(status['Binlog_snapshot_file']), int(status['Binlog_snapshot_position']), 'exact')
    (binlog_file, binlog_position) = binary_log_head(conn)
    return (binlog_file, binlog_position, 'upper_bound')


EXCLUDED_KEY_COLUMN = re.compile(r"[A-Za-z0-9_$]+")  # used with fullmatch: no trailing newline slips through


def read_excluded_keys(stream):
    """The row filter that leaves out the keys the driver sends on ``stream``:
    one JSON line {"column": <integer key column> | null, "keys": [<int>, ...]}.
    Returns None when there is nothing to leave out. A missing or malformed
    line raises: the checksum is never computed without the driver's answer."""
    line = stream.readline()
    if not line:
        raise RuntimeError("--exclude_keys_from_stdin: no exclusion line on stdin (driver gone?)")
    payload = json.loads(line)
    keys = payload.get("keys") or []
    column = payload.get("column")
    if not keys:
        return None
    if not isinstance(column, str) or not EXCLUDED_KEY_COLUMN.fullmatch(column):
        raise RuntimeError(f"--exclude_keys_from_stdin: invalid key column {column!r}")
    if not all(isinstance(key, int) and not isinstance(key, bool) for key in keys):
        raise RuntimeError("--exclude_keys_from_stdin: keys must be integers")
    logging.info(f"Leaving out {len(keys)} key(s) changed after the snapshot began")
    return f"`{column}` not in ({','.join(str(key) for key in keys)})"


def get_tables_from_regexp(conn, tables_regexp):
    return get_tables_from_regex(conn, args.no_wc, args.mysql_database, tables_regexp)


def calculate_sql_checksum(conn, table, where, excluded_columns,  include_floating_point_columns, include_json_columns):
    result = None
    try:
        if args.ignore_tables_regex:
            rex_ignore_tables = re.compile(
                args.ignore_tables_regex, re.IGNORECASE)
            if rex_ignore_tables.match(table):
                logging.info("Ignoring "+table + " due to ignore_regex_tables")
                return None

        statements = []

        if getattr(args, "consistent_snapshot", False):
            # One InnoDB read view for the whole checksum query, and the binlog
            # position it corresponds to, so the driver can wait until the
            # connector has applied everything the view contains (spec 13.06
            # section 3.7.4). No lock is taken.
            execute_mysql(conn, "START TRANSACTION WITH CONSISTENT SNAPSHOT")
            (binlog_file, binlog_position, kind) = snapshot_binlog_position(conn)
            logging.info(f"Snapshot position for table {args.mysql_database}.{table} = "
                         f"{binlog_file} {binlog_position} {kind}")
            if getattr(args, "exclude_keys_from_stdin", False):
                # Held in the snapshot: the driver now waits for the connector
                # and answers with the keys changed since (version fence).
                exclusion = read_excluded_keys(sys.stdin)
                if exclusion:
                    # Parenthesised like the ClickHouse side's filter, so an OR in
                    # the table's where cannot bind the exclusion differently.
                    where = f"({where}) and {exclusion}" if where else exclusion
        (query, select_query, distributed_by,
         external_table_types, clamped_expression) = get_table_checksum_query(table, conn, args.binary_encoding, where, excluded_columns,  include_floating_point_columns, include_json_columns)
        statements = select_table_statements(
            table, query, select_query, distributed_by, external_table_types, where, clamped_expression)
        result = compute_checksum(table, statements, conn)
    finally:
        conn.close()

    return result


def calculate_checksum_single_thread(mysql_table, mysql_user, mysql_password, chunk, pk, where, excluded_columns,  include_floating_point_columns, include_json_columns):
    conn = get_mysql_connection(args.mysql_host, mysql_user, mysql_password, args.mysql_port, args.mysql_database)
    _where = "1=1"
    if where:
        _where += " and "+ where
    if pk:
        min_pk = int(chunk['min_pk'])
        max_pk = int(chunk['max_pk'])
        _where = f" {_where} and {pk} between {min_pk} and {max_pk}"

    # space-separated words and comma-separated lists alike (spec 13.06 D-13.06-17)
    parsed_excluded_columns = parse_exclude_columns(excluded_columns)
    result = calculate_sql_checksum(conn, mysql_table, _where, parsed_excluded_columns,  include_floating_point_columns, include_json_columns)
    return result


def calculate_checksum(mysql_table, mysql_user, mysql_password, excluded_columns, include_floating_point_columns, include_json_columns):
    if args.ignore_tables_regex:
        rex_ignore_tables = re.compile(args.ignore_tables_regex, re.IGNORECASE)
        if rex_ignore_tables.match(mysql_table):
            logging.info("Ignoring "+mysql_table +
                         " due to ignore_regex_tables")
            return
    statements = []

    conn = get_mysql_connection(args.mysql_host, mysql_user, mysql_password, args.mysql_port, args.mysql_database)
    pk = mysql_pk_columns(conn, args.mysql_database, mysql_table, is_integer=True)
    threads_per_table = 1
    if len(pk) > 0 and args.threads_per_table > 1 :
        pk = pk[0]
        threads_per_table = args.threads_per_table
    else:
        pk = None
        threads_per_table = 1
    where = "1=1"
    if args.where:
        where = args.where
        if "{partition_expression}" in where:
            partition_key = get_table_partition_key(conn, args.mysql_database, mysql_table)
            if partition_key is not None:
                where = fstr(where, partition_key)
    result = []

    # initialize debug output
    if args.debug_output:
       out_file = f"out.{mysql_table}.mysql.txt"
       debug_out = open(out_file, 'w')
       debug_out.close()

    with concurrent.futures.ThreadPoolExecutor(max_workers=threads_per_table) as executor:
            futures = []
            for chunk in divide_table_into_even_chunks(conn, mysql_table, args.chunk_size, pk, where):
                futures.append(executor.submit(
                    calculate_checksum_single_thread, mysql_table, mysql_user, mysql_password, chunk, pk, where, excluded_columns, include_floating_point_columns, include_json_columns))
            for future in concurrent.futures.as_completed(futures):
                try:
                    result.append(future.result())
                except Exception:
                    logging.error(f"Checksum failed for {mysql_table}")
                    raise
    # With --debug_output the per-row strings are in out.<table>.mysql.txt and
    # the checksum line is still printed (spec 13.06 D-13.06-27).
    logging.debug(str(result))
    # cnt, a, b, c, d and the clamped count, summed over the chunks
    totals = [0, 0, 0, 0, 0, 0]
    for r in result:
        for position, value in enumerate(r):
            totals[position] += value
    (cnt, a, b, c, d, clamped) = totals
    checksum = checksum_from_aggregate(cnt, a, b, c, d)
    if clamped > 0:
        (min_datetime_value, max_datetime_value) = datetime_bounds(args)
        logging.warning(f"{clamped} out-of-range datetime values clamped to [{min_datetime_value}, {max_datetime_value}] in table {args.mysql_database}.{mysql_table}")
    logging.info("Checksum for table "+args.mysql_database + "."+mysql_table+" = "+checksum + " count "+str(cnt))

# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


def build_argument_parser():
    parser = argparse.ArgumentParser(description='''Compute a ClickHouse compatible checksum.
          ''')
    # Required
    parser.add_argument('--mysql_host', help='MySQL host', required=True)
    parser.add_argument('--mysql_user', help='MySQL user', required=False)
    parser.add_argument('--mysql_password',
                        help='MySQL password, discouraged, please use a config file', required=False)
    parser.add_argument('--defaults_file',
                        help='MySQL config file default is ~/.my.cnf', required=False, default='~/.my.cnf')
    parser.add_argument('--mysql_database',
                        help='MySQL database', required=True)
    parser.add_argument('--mysql_port', help='MySQL port',
                        default=3306, required=False)
    parser.add_argument('--tables_regex', help='table regexp', required=True)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--order_by', help='order by` clause', required=False)
    parser.add_argument('--ignore_tables_regex',
                        help='Ignore table regexp', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Use --tables_regex as the table', required=False)
    parser.add_argument('--debug_output', action='store_true', default=False,
                        help='Output the raw format to a file called out.txt', required=False)
    parser.add_argument(
        '--debug_limit', help='Limit the debug output in lines', required=False)
    parser.add_argument(
        '--binary_encoding', choices=['hex', 'base64', 'raw'], default='hex', required=False,
        help='how the connector wrote binary values: hex text (default), base64 text (binary.handling.mode=base64) or raw bytes (persist.raw.bytes=true, compared as hex); pass the same value to the ClickHouse side')
    parser.add_argument(
        '--min_date_value', help='Minimum Date32/DateTime64 date', default='1900-01-01', required=False)
    parser.add_argument(
        '--max_date_value', help='Maximum Date32/Datetime64 date', default='2299-12-31', required=False)
    parser.add_argument(
            '--source_timezone', help='IANA time zone the connector interprets DATETIME values in (its database.connectionTimeZone); only used to clamp DATETIME columns with the same bounds as the ClickHouse side', default='UTC', required=False)
    parser.add_argument(
            '--min_datetime_value', help='Lower clamp bound for datetime/timestamp values (same value on both sides; default is the ClickHouse DateTime64 minimum)', default=DATETIME_MIN, required=False)
    parser.add_argument(
            '--max_datetime_value', help='Upper clamp bound for datetime/timestamp values (same value on both sides; default is the ClickHouse DateTime64 maximum)', default=DATETIME_MAX, required=False)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--exclude_columns', help='columns exclude',
                        nargs='+', default=[])
    parser.add_argument('--threads_per_table', type=int,
                        help='number of parallel threads per table', default=1)
    parser.add_argument('--chunk_size', type=int, help='Chunk size', default=10000)
    parser.add_argument('--consistent_snapshot', action='store_true', default=False,
                        help='Read the table in one START TRANSACTION WITH CONSISTENT SNAPSHOT (no lock) and log the binlog position of that snapshot. Requires --threads_per_table 1.')
    parser.add_argument('--exclude_keys_from_stdin', action='store_true', default=False,
                        help='With --consistent_snapshot: after logging the snapshot position, hold the snapshot and read one JSON line {"column": ..., "keys": [...]} from stdin; those keys are left out of the checksum (the driver\'s version fence).')
    parser.add_argument('--threads', type=int,
                        help='number of tables in parallel to compute', default=1)
    parser.add_argument('--include_floating_point_columns', action='store_true', default=False,
                        help='Floating point data types like float or double can not be compared, we do not include them by default', required=False)
    parser.add_argument('--include_json_columns', action='store_true', default=False,
                        help='JSON columns are not compared by default (each table logs a WARNING naming them); this compares a best-effort compact text rendering against the text the connector stored. Pass it to both sides.', required=False)
    return parser


def main():
    parser = build_argument_parser()
    global args
    args = parser.parse_args()
    (args.min_datetime_value, args.max_datetime_value) = datetime_bounds(args)
    validate_timezone(args.source_timezone, '--source_timezone')
    if args.consistent_snapshot and args.threads_per_table > 1:
        # PK chunks run on separate connections, each with its own read view.
        parser.error("--consistent_snapshot reads through one connection: use --threads_per_table 1")
    if args.exclude_keys_from_stdin and not args.consistent_snapshot:
        parser.error("--exclude_keys_from_stdin applies inside a snapshot: add --consistent_snapshot")

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

    mysql_user = args.mysql_user
    mysql_password = args.mysql_password

    # check parameters
    if args.mysql_password:
        logging.warning("Using password on the command line is not secure, please specify a config file ")
        assert args.mysql_user is not None, "--mysql_user must be specified"
    else:
        config_file = args.defaults_file
        (mysql_user, mysql_password) = resolve_credentials_from_config(config_file)

    try:
        conn = get_mysql_connection(args.mysql_host, mysql_user,
                                mysql_password, args.mysql_port, args.mysql_database)
        tables = get_tables_from_regexp(conn, args.tables_regex)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            future_to_table = {}
            # --no_wc: get_tables_from_regex returns [[<tables_regex>]], the table name
            # itself, not a result set (spec 13.06 D-13.06-26).
            table_rows = [{'table_name': row[0]} for row in tables] if args.no_wc else tables.mappings().fetchall()
            for table in table_rows:
                future = executor.submit(
                    calculate_checksum, table['table_name'], mysql_user, mysql_password, args.exclude_columns, args.include_floating_point_columns, args.include_json_columns)
                futures.append(future)
                future_to_table[future] = table['table_name']
            for future in concurrent.futures.as_completed(futures):
                if future.exception() is not None:
                    logging.error("Exception in table " + future_to_table[future])
                    raise future.exception()

    except (KeyboardInterrupt, SystemExit):
        logging.info("Received interrupt")
        os._exit(1)
    except Exception as e:
        logging.error("Exception in main thread : " + str(e))
        logging.error(traceback.format_exc())
        sys.exit(1)
    logging.debug("Exiting Main Thread")
    sys.exit(0)


if __name__ == '__main__':
    main()

"""
#r/ -- ============================================================================
# -- FileName     : clickhouse_table_checksum
# -- Date         :
# -- Summary      : calculate a checksum for a clickhouse table
# -- Credits      : https://www.sisense.com/blog/hashing-tables-to-ensure-consistency-in-postgres-redshift-and-mysql/
# --
"""
import logging
import argparse
import traceback
import sys
from sys import argv
import datetime
import warnings
import re
import os
import concurrent.futures
from db.clickhouse import *
from db.checksum_common import (checksum_from_aggregate, DATETIME_MIN, DATETIME_MAX, datetime_bounds,
                                clamp_datetime_expression, clamped_datetime_flag, clamped_count_expression)

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def get_connection(clickhouse_user, clickhouse_password):

    conn = clickhouse_connection(args.clickhouse_host, database=args.clickhouse_database,
                                 user=clickhouse_user, password=clickhouse_password,
                                 port=args.clickhouse_port,
                                 secure=args.secure)
    return conn


def compute_checksum(table, clickhouse_user, clickhouse_password, statements):
    conn = get_connection(clickhouse_user, clickhouse_password)
    debug_out = None
    if args.debug_output:
        out_file = f"out.{table}.ch.txt"
        #logging.info(f"Debug output to {out_file}")
        debug_out = open(out_file, 'w')
    else:
        logging.info("Skipping writing to file")
    try:
        for sql in statements:
            (result, rowcount) = execute_sql(conn, sql)
            if rowcount != -1:
                logging.debug("Rows affected "+str(rowcount))
            if result != None and rowcount > 0:
                x = [element for tupl in result for element in tupl]

                if args.debug_output:
                    for line in x:
                        if isinstance(line, bytes):
                            debug_out.write(line.decode('utf-8'))
                        else:
                            debug_out.write(line)
                        debug_out.write('\n')
                else:
                    logging.debug(str(x))
                    (cnt, a, b, c, d) = x[0:5]
                    clamped = x[5] if len(x) > 5 else 0
                    checksum = checksum_from_aggregate(cnt, a, b, c, d)
                    if clamped > 0:
                        (min_datetime_value, max_datetime_value) = datetime_bounds(args)
                        logging.warning(f"{clamped} out-of-range datetime values clamped to [{min_datetime_value}, {max_datetime_value}] in table {args.clickhouse_database}.{table}")
                    logging.info("Checksum for table "+args.clickhouse_database +
                                 "."+table+" = "+checksum + " count "+str(cnt))

        if args.debug_output:
            debug_out.close()
    finally:
        conn.close()


def get_primary_key_columns(conn, table_schema, table_name):
    sql = """
    SELECT
    name
    FROM system.columns
    WHERE (database = '{table_schema}') AND (table = '{table_name}') AND (is_in_primary_key = 1)
    ORDER BY position ASC
""".format(table_schema=table_schema, table_name=table_name)
    (rowset, count) = execute_sql(conn, sql)
    res = []
    for row in rowset:
        if row[0] is not None:
            res.append(row[0])
    return res


# Metadata columns managed by the sink connector itself. The un-exclusion rule in
# get_table_checksum_query() exists only for these (e.g. the is_deleted/_is_deleted
# pair): a user-supplied excluded column must never be silently re-added just
# because an unrelated underscore-prefixed column happens to share its name.
SINK_METADATA_COLUMNS = frozenset(
    {"_sign", "_version", "is_deleted", "_is_deleted", "__is_deleted"}
)


def is_datetime_type(data_type):
    """DateTime, DateTime32, DateTime64 and their Nullable / time-zoned forms."""
    return "DateTime" in data_type


def clickhouse_datetime_rendering(column_name):
    """Canonical text of a DateTime / DateTime64 column (spec 11.02 section 3.4):
    'YYYY-MM-DD HH:MM:SS.ffffff', six digits whatever the column's scale."""
    return f"toString(toDateTime64({column_name}, 6))"


def clickhouse_column_expression(column_name, data_type, numeric_scale, options, bounds=(DATETIME_MIN, DATETIME_MAX)):
    """Text rendering of one ClickHouse column (spec 11.02 section 3.3).

    ``column_name`` is already double-quoted. ``options`` is the parsed argument
    namespace (only its rendering options are read). ``bounds`` are the
    canonical (min, max) datetime clamp bounds.
    """
    if 'Bool' == data_type:
        return "toString(toUInt8(" + column_name + "))"
    if "Decimal" in data_type:
        # toString() drops trailing zeros; MySQL prints the declared scale.
        return "toDecimalString(" + column_name + "," + str(numeric_scale) + ")"
    if is_datetime_type(data_type):
        return clamp_datetime_expression(clickhouse_datetime_rendering(column_name), bounds[0], bounds[1], 'clickhouse')
    if column_name.strip('"') in options.hex_columns:
        return "toString(unhex(" + column_name + "))"
    return "toString(" + column_name + ")"


def build_clickhouse_row_expression(columns_metadata, options):
    """Build the canonical row expression from ``system.columns`` rows.

    ``columns_metadata`` rows are ``(name, type, is_nullable, numeric_scale)`` in
    position order, already filtered of excluded columns. Returns
    ``(select, nullables, columns, data_types, clamped_expression)`` where
    ``select`` is the pieces joined by ``||'#'||`` and ``clamped_expression``
    the per-row count of datetime values the clamp changed.

    The pieces are collected in a list and joined, never built by appending a
    separator after each column: a column skipped by type (floating point,
    JSON) must contribute neither a value nor a separator, otherwise a table
    whose last column is a skipped Float64 hashes ``1#bob#`` here against
    ``1#bob`` from MySQL's concat_ws (spec 11.02 section 3.3).
    """
    parts = []
    nullables = []
    columns = []
    data_types = {}
    clamped_flags = []
    bounds = datetime_bounds(options)
    for row in columns_metadata:
        column_name = '"' + row[0] + '"'
        data_type = row[1]
        is_nullable = row[2]
        numeric_scale = row[3]
        columns.append(row[0])
        data_types[row[0]] = data_type
        if not options.include_floating_point_columns:
            if 'Float' in data_type:
                logging.info(f"Excluding floating point column {column_name} of type {data_type}")
                continue
        if not options.include_json_columns:
            if 'json' in data_type:
                logging.info(f"Excluding json column {column_name} of type {data_type}")
                continue
        expression = clickhouse_column_expression(column_name, data_type, numeric_scale, options, bounds)
        if is_datetime_type(data_type):
            clamped_flags.append(clamped_datetime_flag(clickhouse_datetime_rendering(column_name), bounds[0], bounds[1]))
        if is_nullable == 1:
            nullables.append(column_name)
            expression = "case when " + column_name + " is null then '' else " + expression + " end"
        parts.append(expression)
    logging.debug(str(nullables))
    if len(nullables) > 0:
        parts.append(" || ".join(
            "case when " + nullable + " is null then '1' else '0' end" for nullable in nullables))
    select = "||'#'||".join(parts)
    return (select, nullables, columns, data_types, clamped_count_expression(clamped_flags))


def get_table_checksum_query(conn, table):
    excluded_columns = "','".join(args.exclude_columns)
    excluded_columns = [f'{column}' for column in excluded_columns.split(',')]
    logging.info(f"Excluded columns, {excluded_columns}")
    checksum_query="select name, type, if(match(type,'Nullable'),1,0) is_nullable, numeric_scale from system.columns where database='" + args.clickhouse_database+"' and table = '"+table+"' order by position"
    (rowset, rowcount) = execute_sql(conn, checksum_query)

    columns_metadata  = []
    for row in rowset:
        columns_metadata.append(row)
    columns_metadata_map = { r[0]: r for r in columns_metadata }
    # sometimes we have excluded columns like is_deleted and _is_deleted, we would exclude the one prefixed with _
    filtered_columns_metadata = []
    for row in columns_metadata:
        prefixed_column = "_"+row[0]
        if (row[0] in excluded_columns
                and prefixed_column in columns_metadata_map
                and row[0] in SINK_METADATA_COLUMNS):
            logging.info(f"Not excluding column {row[0]} as {prefixed_column} is also excluded")
        elif row[0] in excluded_columns:
            logging.info(f"Excluding column {row[0]}")
            continue
        filtered_columns_metadata.append(row)

    (select, nullables, columns, data_types, clamped_expression) = build_clickhouse_row_expression(filtered_columns_metadata, args)

    primary_key_columns = get_primary_key_columns(conn,
        args.clickhouse_database, table)
    logging.debug(str(primary_key_columns))
    order_by_columns = ""
    if len(primary_key_columns) > 0:
        order_by_columns = ','.join(primary_key_columns)
    else:
        order_by_columns = ','.join(columns)

    query = "select "+select+"||','  as query from " + \
        args.clickhouse_database+"."+table

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
    statements = []
    external_table_name = args.clickhouse_database+"."+table
    limit = ""
    if args.debug_limit:
        limit = " limit "+args.debug_limit
    where = "1=1"
    if _where:
       where = _where
    schema=args.clickhouse_database
    # skip deleted rows
    if args.sign_column != '':
      where+= f" and {args.sign_column} > 0 "

    memory_setting = ""
    max_memory_usage = args.max_memory_usage
    if max_memory_usage:
        memory_setting = f", max_memory_usage = {max_memory_usage}"

    sql = f"""select
      count(*) as "cnt",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 1, 8))))),0) as "a",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 9, 8))))),0) as "b",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 17, 8))))),0) as "c",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 25, 8))))),0) as "d",
      coalesce(sum(clamped),0) as "clamped"
    from (
     select hex(MD5(

       {select_query}

      )) as "hash",
      {clamped_expression} as clamped

      from {schema}.{table} final where {where} /*order by {order_by}*/ {limit}

	  ) as t settings do_not_merge_across_partitions_select_final=1 {memory_setting}"""
    if args.debug_output:
        sql = f"""select  {select_query}  as "hash"   from {schema}.{table} final where  {where} {limit} settings do_not_merge_across_partitions_select_final=1"""
    statements.append(sql)
    return statements


def get_tables_from_regex(conn):
    if args.no_wc:
        return [[args.tables_regex]]

    schema = args.clickhouse_database
    strCommand = "select name from system.tables where database = '{d}' and match(name,'{t}') order by 1".format(
        d=schema, t=args.tables_regex)
    logging.info(f"REGEX QUERY: {strCommand}")
    (rowset, rowcount) = execute_sql(conn, strCommand)
    x = rowset
    return x


def calculate_checksum(table, clickhouse_user, clickhouse_password, where, partition_key):
    if args.ignore_tables_regex:
        rex_ignore_tables = re.compile(args.ignore_tables_regex, re.IGNORECASE)
        if rex_ignore_tables.match(table):
            logging.info("Ignoring "+table + " due to ignore_regex_tables")
            return
    threads = []
    threadID = 1
    # calculate the current date

    statements = []
    #
    # Create new threads to execute the sync
    conn = get_connection(clickhouse_user, clickhouse_password)
    # we need to count the values in CH first
    sql = "select count(*) cnt from "+args.clickhouse_database+"."+table
    if where:
        if "{partition_expression}" in where:
           if partition_key is None:
                partition_key = get_table_partition_key(conn, args.clickhouse_database, table)
                logging.info(partition_key)
                if len(partition_key) > 0 :
                    partition_key = partition_key[0][0]
           if partition_key is None or partition_key=='':
               logging.warning(f"{args.clickhouse_database}.{table} has no partitioning key")
           where = fstr(where, partition_key)
        sql = sql + " where " + where


    (rowset, rowcount) = execute_sql(conn, sql)
    if rowcount == 0:
        logging.info("No rows in ClickHouse. Nothing to sync.")
        logging.info("Checksum for table {schema}.{table} = d41d8cd98f00b204e9800998ecf8427e count 0".format(
            schema=args.clickhouse_database, table=table))
        return
    # generate the file from ClickHouse
    (query, select_query, distributed_by,
     external_table_types, clamped_expression) = get_table_checksum_query(conn, table)
    statements = select_table_statements(
        table, query, select_query, distributed_by, external_table_types, where, clamped_expression)
    compute_checksum(table, clickhouse_user, clickhouse_password, statements)


# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)

create_function_format_decimal = '''CREATE FUNCTION if not exists format_decimal AS (x, scale) -> toDecimalString(x, scale)'''

def main():

    parser = argparse.ArgumentParser(description='''
  Compute the table checksum using the same technique as pt-checksum, md5 algorithm.

          ''')
    # Required
    parser.add_argument('--clickhouse_host',  help='ClickHouse host', required=True)
    parser.add_argument('--clickhouse_user', help='ClickHouse user', required=False)
    parser.add_argument('--clickhouse_password', help='CH password (discouraged option use a configuration file)', required=False, default=None)
    parser.add_argument('--clickhouse_config_file', help='CH config file either xml or yaml, default is ./clickhouse-client.xml', required=False, default='./clickhouse-client.xml')
    parser.add_argument('--clickhouse_database', help='ClickHouse database', required=True)
    parser.add_argument('--clickhouse_port',  help='ClickHouse port', default=9000, required=False)
    parser.add_argument('--secure', help='True or False', default=False, required=False)
    parser.add_argument('--sign_column', help='Override sign column, by default its _sign', default='_sign', required=False)
    parser.add_argument('--tables_regex', help='table regexp', required=True)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--order_by', help='order by` clause', required=False)
    parser.add_argument('--partition_key', help='partition key', required=False)
    parser.add_argument('--ignore_tables_regex', help='Ignore table regexp', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False, help='Runs wc first to determine the table names from the regex', required=False)
    parser.add_argument('--debug_output', action='store_true', default=False, help='Output the raw format to a file called out.txt', required=False)
    parser.add_argument('--debug_limit', help='Limit the debug output in lines', required=False)
    parser.add_argument('--hex_columns', help='columns to convert to hex', nargs='+', default=[])
    parser.add_argument('--debug', dest='debug', action='store_true', default=False)
    # TODO change this to standard MaterializedMySQL columns https://github.com/Altinity/clickhouse-sink-connector/issues/78
    parser.add_argument('--exclude_columns', help='columns exclude', nargs='*', default=['_sign,_version,is_deleted,_is_deleted'])
    parser.add_argument('--threads', type=int, help='number of parallel threads', default=1)
    parser.add_argument('--min_datetime_value', help='Lower clamp bound for datetime values (same value on both sides; default is the ClickHouse DateTime64 minimum)', default=DATETIME_MIN, required=False)
    parser.add_argument('--max_datetime_value', help='Upper clamp bound for datetime values (same value on both sides; default is the ClickHouse DateTime64 maximum)', default=DATETIME_MAX, required=False)
    parser.add_argument('--max_memory_usage', help='increase  max_memory_usage', required=False)
    parser.add_argument('--include_floating_point_columns', action='store_true', default=False,
                        help='Floating point data types like float or double can not be compared, we do not include them by default', required=False)
    parser.add_argument('--include_json_columns', action='store_true', default=True,
                        help='JSON data types are included by default. This flag is a no-op (always True). Use --exclude_columns to skip JSON columns.', required=False)
    global args
    args = parser.parse_args()
    (args.min_datetime_value, args.max_datetime_value) = datetime_bounds(args)

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

    clickhouse_user = args.clickhouse_user
    clickhouse_password = args.clickhouse_password

    # check parameters
    if args.clickhouse_password:
        logging.warning("Using password on the command line is not secure, please specify a config file ")
        assert args.clickhouse_user is not None, "--clickhouse_user must be specified"
    else:
        config_file = args.clickhouse_config_file
        (clickhouse_user, clickhouse_password) = resolve_credentials_from_config(config_file)
    try:
        conn =  get_connection(clickhouse_user, clickhouse_password)
        tables = get_tables_from_regex(conn)
        # CH does not print decimal with trailing zero, we need a custom function
        execute_sql(conn, create_function_format_decimal)

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            for table in tables:
              futures.append(executor.submit(calculate_checksum, table[0], clickhouse_user, clickhouse_password, args.where, args.partition_key))
            for future in concurrent.futures.as_completed(futures):
              if future.exception() is not None:
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


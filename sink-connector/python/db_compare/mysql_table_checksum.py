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
import re
import os
import concurrent.futures
from db.mysql import *
from db.checksum_common import checksum_from_aggregate
runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def compute_checksum(table, statements, conn):
    sql = ""
    debug_out = None
    result = None
    if args.debug_output:
        out_file = f"out.{table}.mysql.txt"
        # logging.info(f"Debug output to {out_file}")
        debug_out = open(out_file, 'a')
    try:
        for statement in statements:
            sql = statement

            (result, rowcount) = execute_mysql(conn, sql)
            if rowcount != -1:
                logging.debug("Rows affected "+str(rowcount))
            if result != None and result.returns_rows == True:
                x = [element for tupl in result for element in tupl]
                if not args.debug_output:
                    result = x 
                if args.debug_output:
                    for line in x:
                        debug_out.write(str(line)+'\n')
                if args.debug_output:
                        debug_out.close()
    except Exception:
        raise  # propagate to caller which owns connection lifecycle

    return result


# information_schema DATA_TYPE keywords of the IEEE 754 types (REAL is an alias
# of DOUBLE and never appears as a DATA_TYPE).
FLOATING_POINT_DATA_TYPES = frozenset({"float", "double"})


def mysql_column_expression(column, options, binary_encoding, same_charset):
    """Text rendering of one MySQL column (spec 11.02 section 3.3).

    ``column`` is an ``information_schema.columns`` row. Classification uses
    ``data_type`` (DATA_TYPE, the bare type keyword) and ``datetime_precision``;
    ``column_type`` (COLUMN_TYPE) carries user text such as enum/set labels and
    is never substring-matched -- an enum('float','json') is a string column.
    """
    column_name = '`' + column['column_name'] + '`'
    data_type = column['data_type'].lower()
    precision = column['datetime_precision']
    collation = column['collation']
    min_date_value = options.min_date_value
    max_date_value = options.max_date_value
    max_datetime_value = options.max_datetime_value
    if data_type == 'json':
        # convert to a compact representation, best effort, it is not perfect and it is advised to ignore those columns
        # https://bugs.mysql.com/bug.php?id=118990
        # https://github.com/Altinity/clickhouse-sink-connector/issues/1137
        return f"""REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(REGEXP_REPLACE(regexp_replace(regexp_replace(regexp_replace(regexp_replace(regexp_replace(convert(json_pretty({column_name}) using utf8mb4),'": "','":"'),'":\\s(-*\\d|\\[|\\{{|true|false)','":$1'),'\\.0\\b',''),'\\s+(".*?)\\s*','$1'),'\\s*\\n\\\s*',''), '\\\\u([0-9A-F]{{3}})a', '\\\\u$1A'), '\\\\u([0-9A-F]{{3}})b', '\\\\u$1B'), '\\\\u([0-9A-F]{{3}})c', '\\\\u$1C'), '\\\\u([0-9A-F]{{3}})d', '\\\\u$1D'), '\\\\u([0-9A-F]{{3}})e', '\\\\u$1E'), '\\\\u([0-9A-F]{{3}})f', '\\\\u$1F')"""
    if data_type == 'datetime':
        # CH datetime range is not the same as MySQL https://clickhouse.com/docs/en/sql-reference/data-types/datetime64/
        if precision is None or int(precision) <= 3:
            return f"case when {column_name} >=  substr('{max_datetime_value}', 1, length({column_name})) then substr(TRIM(TRAILING '0' FROM CAST('{max_datetime_value}' AS datetime(3))),1,length({column_name})) else case when {column_name} <= '{options.min_datetime_value}' then TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('{options.min_datetime_value}' AS datetime(3)))) else substr(TRIM(TRAILING '.' from (TRIM(TRAILING '0' from cast({column_name} as char)))),1,length({column_name})) end end"
        return f"case when {column_name} >= substr('{max_datetime_value}', 1, length({column_name})) then substr(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('{max_datetime_value}' AS datetime(6)))),1,length({column_name})) else case when {column_name} <= '{options.min_datetime_value}' then TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('{options.min_datetime_value}' AS datetime(6)))) else  substr(TRIM(TRAILING '.' from (TRIM(TRAILING '0' from cast({column_name} as char)))),1,length({column_name})) end end"
    if data_type == 'time':
        return f"substr(cast({column_name} as time(6)),1,length({column_name}))"
    if data_type == 'timestamp':
        return f"substr(TRIM(TRAILING '.' from (TRIM(TRAILING '0' from cast({column_name} as char)))),1,length({column_name}))"
    if data_type == 'date':  # Date are converted to Date32 in CH
        # CH date range is not the same as MySQL https://clickhouse.com/docs/en/sql-reference/data-types/date
        return f"case when {column_name} >='{max_date_value}' then CAST('{max_date_value}' AS date) else case when {column_name} <= '{min_date_value}' then CAST('{min_date_value}' AS date) else {column_name} end end"
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

    Returns ``(select, nullables, data_types)``; ``select`` is the comma
    separated argument list of ``concat_ws('#', ...)``. Skipped columns
    contribute nothing; the nullability flags are one trailing element.
    """
    pieces = []
    nullables = []
    data_types = {}
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
            logging.info(f"Excluding floating point column {column_name} of type {column['column_type']}")
            continue
        if not include_json_columns and data_type == 'json':
            logging.info(f"Excluding json column {column_name} of type {column['column_type']}")
            continue
        expression = mysql_column_expression(column, options, binary_encoding, same_charset)
        if column['is_nullable'] == 'YES':
            nullables.append(column_name)
            expression = f"ifnull({expression},'')"
        pieces.append(expression)
        data_types[name] = column['column_type']
    logging.debug(str(nullables))
    if len(nullables) > 0:
        pieces.append("concat(" + ",".join("ISNULL(" + nullable + ")" for nullable in nullables) + ")")
    return (",".join(pieces), nullables, data_types)


def get_table_checksum_query(table, conn, binary_encoding, where, excluded_columns,  include_floating_point_columns, include_json_columns):

    (rowset, rowcount) = execute_mysql(conn, "select COLUMN_NAME as column_name, DATA_TYPE as data_type, COLUMN_TYPE as column_type, IS_NULLABLE as is_nullable, COLLATION_NAME as collation, DATETIME_PRECISION as datetime_precision from information_schema.columns where table_schema='" +
                                       args.mysql_database+"' and table_name = '"+table+"' order by ordinal_position")

    logging.debug("Excluded columns: "+str(excluded_columns))
    row_list = [row for row in rowset.mappings()]
    (select, nullables, data_types) = build_mysql_row_expression(
        row_list, args, binary_encoding, excluded_columns, include_floating_point_columns, include_json_columns)
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
    return (query, select, order_by_columns, external_column_types)


def fstr(template, partition_expression):
        # Safe substitution: only replace {partition_expression} placeholder
        # instead of eval() which allows arbitrary code execution
        if partition_expression is not None:
            return template.replace('{partition_expression}', str(partition_expression))
        return template


def select_table_statements(table, query, select_query, order_by, external_column_types, _where):
    statements = ['set names utf8mb4', 'set session wait_timeout=28000']
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
	   coalesce(max(d),0) as d
         from (
          select @md5sum :=md5( convert(concat_ws('#',{select_query}) using utf8mb4  )) as `hash`,
		   @a:=@a+cast(conv(substring(@md5sum, 1, 8), -16, 10) as signed) as a,
                   @b:=@b+cast(conv(substring(@md5sum, 9, 8), -16, 10) as signed) as b,
		   @c:=@c+cast(conv(substring(@md5sum, 17, 8), -16, 10) as signed) as c,
		   @d:=@d+cast(conv(substring(@md5sum, 25, 8), -16, 10) as signed) as d
           from {schema}.{table} where {where}
         ) as t;
  """.format(select_query=select_query, schema=args.mysql_database, table=table, where=where, order_by=order_by, limit=limit)

    if args.debug_output:
        sql = """select concat_ws('#',{select_query})  as `hash`   from {schema}.{table} where  {where}  {limit}""".format(
            select_query=select_query, schema=args.mysql_database, table=table, where=where, order_by=order_by, limit=limit)
    statements.append(sql)
    return statements


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

        (query, select_query, distributed_by,
         external_table_types) = get_table_checksum_query(table, conn, args.binary_encoding, where, excluded_columns,  include_floating_point_columns, include_json_columns)
        statements = select_table_statements(
            table, query, select_query, distributed_by, external_table_types, where)
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

    parsed_excluded_columns = []
    for col in excluded_columns:
        parsed_excluded_columns.extend(col.split(','))  # split values with commas
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
    if args.debug_output:
        # checksum is not output in debug_output mode
        return
    logging.debug(str(result))
    to_add = (0,0,0,0,0)
    for r in result:
       to_add  = (to_add[0]+r[0], to_add[1]+r[1], to_add[2]+r[2], to_add[3]+r[3], to_add[4]+r[4])
    (cnt, a, b, c, d) = to_add
    checksum = checksum_from_aggregate(cnt, a, b, c, d)
    logging.info("Checksum for table "+args.mysql_database + "."+mysql_table+" = "+checksum + " count "+str(cnt))

# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


def main():

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
        '--binary_encoding', help='either hex or base64 to encode MySQL binary content', default='hex', required=False)
    parser.add_argument(
        '--min_date_value', help='Minimum Date32/DateTime64 date', default='1900-01-01', required=False)
    parser.add_argument(
        '--max_date_value', help='Maximum Date32/Datetime64 date', default='2299-12-31', required=False)
    parser.add_argument(
            '--min_datetime_value', help='Min Datetime64 datetime', default='1970-01-01 00:00:00', required=False)
    parser.add_argument(
            '--max_datetime_value', help='Maximum Datetime64 datetime', default='2299-12-31 23:59:59', required=False)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--exclude_columns', help='columns exclude',
                        nargs='+', default=[])
    parser.add_argument('--threads_per_table', type=int,
                        help='number of parallel threads per table', default=1)
    parser.add_argument('--chunk_size', type=int, help='Chunk size', default=10000)
    parser.add_argument('--threads', type=int,
                        help='number of tables in parallel to compute', default=1)
    parser.add_argument('--include_floating_point_columns', action='store_true', default=False,
                        help='Floating point data types like float or double can not be compared, we do not include them by default', required=False)
    parser.add_argument('--include_json_columns', action='store_true', default=True,
                        help='JSON data types are included by default. This flag is a no-op (always True). Use --exclude_columns to skip JSON columns.', required=False)
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
            for table in tables.mappings().fetchall():
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

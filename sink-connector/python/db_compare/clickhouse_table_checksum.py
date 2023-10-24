"""
# -- ============================================================================
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
import hashlib
from clickhouse_driver import connect
import concurrent.futures


runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def clickhouse_connection(host, database='default', user='default', port=9000, password='',
                          secure=False):
    conn = connect(host=host,
                   user=user,
                   password=password,
                   port=port,
                   database=database,
                   connect_timeout=20,
                   secure=secure
                   )
    return conn


def clickhouse_execute_conn(conn, sql):
    logging.debug(sql)
    cursor = conn.cursor()
    cursor.execute(sql)
    result = cursor.fetchall()
    return result


def get_connection():

    conn = clickhouse_connection(args.clickhouse_host, database=args.clickhouse_database,
                                 user=args.clickhouse_user, password=args.clickhouse_password,
                                 port=args.clickhouse_port,
                                 secure=args.secure)
    return conn


def execute_sql(conn, strSql):
    """
    # -- =======================================================================
    # -- Connect to the SQL server and execute the command
    # -- =======================================================================
    """
    logging.debug("SQL="+strSql)
    rowset = None
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        rowset = clickhouse_execute_conn(conn, strSql)
        rowcount = len(rowset)
    if len(w) > 0:
        logging.warning("SQL warnings : "+str(len(w)))
        logging.warning("first warning : "+str(w[0].message))

    return (rowset, rowcount)


def execute_statement(strSql):
    """
    # -- =======================================================================
    # -- Connect to the SQL server and execute the command
    # -- =======================================================================
    """
    conn = get_connection()
    (rowset, rowcount) = execute_sql(conn, strSql)
    conn.close()
    return (rowset, rowcount)


def compute_checksum(table, statements):

    conn = get_connection()

    debug_out = None
    if args.debug_output:
        out_file = f"out.{table}.ch.txt"
        #logging.info(f"Debug output to {out_file}")
        debug_out = open(out_file, 'w')
    else:
        logging.info("Skipping writing to file")
    try:
        for statement in statements:
            sql = statement
            (result, rowcount) = execute_sql(conn, sql)
            if rowcount != -1:
                logging.debug("Rows affected "+str(rowcount))
            if result != None and rowcount > 0:
                x = [element for tupl in result for element in tupl]

                md5_sum = ""
                cnt = -1
                if args.debug_output:
                    for line in x:
                        if isinstance(line, bytes):
                            debug_out.write(line.decode('utf-8'))
                        else:
                            debug_out.write(line)
                        debug_out.write('\n')
                else:
                    for line in x:
                        logging.debug(str(line))
                        md5_sum += str(line) + '#'
                        if cnt == - 1:
                            cnt = str(line)

                    logging.debug(md5_sum)
                    m = hashlib.md5()
                    m.update(md5_sum.encode('utf-8'))
                    logging.info("Checksum for table "+args.clickhouse_database +
                                 "."+table+" = "+m.hexdigest() + " count "+str(cnt))

        if args.debug_output:
            debug_out.close()
    finally:
        conn.close()


def get_primary_key_columns(table_schema, table_name):
    sql = """
    SELECT
    name
    FROM system.columns
    WHERE (database = '{table_schema}') AND (table = '{table_name}') AND (is_in_primary_key = 1)
    ORDER BY position ASC
""".format(table_schema=table_schema, table_name=table_name)
    (rowset, count) = execute_statement(sql)
    res = []
    for row in rowset:
        if row[0] is not None:
            res.append(row[0])
    return res


def get_table_checksum_query(table):
    #logging.info(f"Excluded columns before join, {args.exclude_columns}")
    excluded_columns = "','".join(args.exclude_columns)
    excluded_columns = [f'{column}' for column in excluded_columns.split(',')]
    #excluded_columns = "'"+excluded_columns+"'"
    logging.info(f"Excluded columns, {excluded_columns}")
    excluded_columns_str = ','.join((f"'{col}'" for col in excluded_columns))
    checksum_query="select name, type, if(match(type,'Nullable'),1,0) is_nullable, numeric_scale from system.columns where database='" + args.clickhouse_database+"' and table = '"+table+"' and name not in ("+ excluded_columns_str +") order by position"

    (rowset, rowcount) = execute_statement(checksum_query)
    #logging.info(f"CHECKSUM QUERY: {checksum_query}")

    select = ""
    nullables = []
    columns = []
    data_types = {}
    first_column = True
    for row in rowset:
        column_name = '"'+row[0]+'"'
        data_type = row[1]
        is_nullable = row[2]
        numeric_scale = row[3]
        columns.append(row[0])
        unhex = row[0] in args.hex_columns
        if not first_column:
            select += "||"

        if is_nullable == 1:
            nullables.append(column_name)
            select += " case when "+column_name+" is null then '' else "
            if first_column:
                select += " '#' || "

        if not first_column:
            select += "'#'||"

        if 'timestamp' in data_type:
            select += "replace(to_char("+column_name + \
                ",'YYYY-MM-DD HH24:MI:SS.US'),'1900-01-01 ','')"
        else:
            if 'Bool' == data_type:
                select += "toString(toUInt8("+column_name+"))"
            elif 'date' == data_type:
                select += "to_char("+column_name + ",'YYYY-MM-DD')"
            elif "Decimal" in data_type:
                # custom function due to https://github.com/ClickHouse/ClickHouse/issues/30934
                # requires this function : CREATE OR REPLACE FUNCTION format_decimal AS (x, scale) -> if(locate(toString(x),'.')>0,concat(toString(x),repeat('0',toUInt8(scale-(length(toString(x))-locate(toString(x),'.'))))),concat(toString(x),'.',repeat('0',toUInt8(scale))))
                select += "format_decimal("+column_name + \
                    ","+str(numeric_scale)+")"
            elif "DateTime64(0)" == data_type:
                select += f"toString({column_name})"
            elif "DateTime" in data_type:
                select += f"trim(TRAILING '.' from (trim(TRAILING '0' FROM toString({column_name}))))"
            else:
                if 'time without time zone' == data_type:
                    select += "replace(to_char("+column_name + \
                        ",'HH24:MI:SS.US'),'1900-01-01 ','')"
                else:
                    if unhex:
                        select += "toString(unhex("+column_name + "))"
                    else:
                        select += "toString("+column_name + ")"

        if is_nullable == 1:
            select += " end"
        first_column = False
        data_types[row[0]] = data_type
    logging.debug(str(nullables))
    if len(nullables) > 0:
        select += "||'#'"
        for nullable in nullables:
            select += "|| case when "+nullable+" is null then '1' else '0' end "
    query = "select "+select+"||','  as query from " + \
        args.clickhouse_database+"."+table

    primary_key_columns = get_primary_key_columns(
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
    return (query, select, order_by_columns, external_column_types)


def select_table_statements(table, query, select_query, order_by, external_column_types):
    statements = []
    external_table_name = args.clickhouse_database+"."+table
    limit = ""
    if args.debug_limit:
        limit = " limit "+args.debug_limit
    where = "1=1"
    if args.where:
        where = args.where

    # skip deleted rows
    if args.sign_column != '':
      where+= f" and {args.sign_column} > 0 "

    sql = """ select
      count(*) as "cnt",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 1, 8))))),0) as "a",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 9, 8))))),0) as "b",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 17, 8))))),0) as "c",
      coalesce(sum(reinterpretAsInt64(reverse(unhex(substring(hash, 25, 8))))),0) as "d"
    from (
     select hex(MD5(

       {select_query}

      )) as "hash"

      from {schema}.{table} final where {where} /*order by {order_by}*/ {limit}
	  
	  ) as t""".format(select_query=select_query, schema=args.clickhouse_database, table=table, where=where, order_by=order_by, limit=limit)

    if args.debug_output:
        sql = """select  {select_query}  as "hash"   from {schema}.{table} final where  {where} order by {order_by} {limit}""".format(
            select_query=select_query, schema=args.clickhouse_database, table=table, where=where, order_by=order_by, limit=limit)
    statements.append(sql)
    return statements


def get_tables_from_regex(strDSN):
    if args.no_wc:
        return [[args.tables_regex]]

    schema = args.clickhouse_database
    strCommand = "select name from system.tables where database = '{d}' and match(name,'{t}') order by 1".format(
        d=schema, t=args.tables_regex)
    logging.info(f"REGEX QUERY: {strCommand}")
    (rowset, rowcount) = execute_statement(strCommand)
    x = rowset
    return x


def calculate_checksum(table):
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

    # we need to count the values in CH first
    sql = "select count(*) cnt from "+args.clickhouse_database+"."+table
    if args.where:
        sql = sql + " where " + args.where

    (rowset, rowcount) = execute_statement(sql)
    if rowcount == 0:
        logging.info("No rows in ClickHouse. Nothing to sync.")
        logging.info("Checksum for table {schema}.{table} = d41d8cd98f00b204e9800998ecf8427e count 0".format(
            schema=args.clickhouse_database, table=table))
        return

    # generate the file from ClickHouse
    (query, select_query, distributed_by,
     external_table_types) = get_table_checksum_query(table)
    statements = select_table_statements(
        table, query, select_query, distributed_by, external_table_types)
    compute_checksum(table, statements)


# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)

create_function_format_decimal = '''CREATE OR REPLACE FUNCTION format_decimal AS (x, scale) -> if(locate(toString(x),'.')>0,concat(toString(x),repeat('0',toUInt8(scale-(length(toString(x))-locate(toString(x),'.'))))),concat(toString(x),if(scale=0,'','.'),repeat('0',toUInt8(scale))))'''


def main():

    parser = argparse.ArgumentParser(description='''
  Compute the table checksum using the same technique as pt-checksum, md5 algorithm.

          ''')
    # Required
    parser.add_argument('--clickhouse_host',
                        help='ClickHouse host', required=True)
    parser.add_argument('--clickhouse_user',
                        help='ClickHouse user', required=True)
    parser.add_argument('--clickhouse_password',
                        help='ClickHouse password', required=True)
    parser.add_argument('--clickhouse_database',
                        help='ClickHouse database', required=True)
    parser.add_argument('--clickhouse_port',
                        help='ClickHouse port', default=9000, required=False)
    parser.add_argument('--secure',
                        help='True or False', default=False, required=False)
    parser.add_argument('--sign_column', help='Override sign column, by default its _sign', default='_sign', required=False)
    parser.add_argument('--tables_regex', help='table regexp', required=True)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--order_by', help='order by` clause', required=False)
    parser.add_argument('--ignore_tables_regex',
                        help='Ignore table regexp', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Runs wc first to determine the table names from the regex', required=False)
    parser.add_argument('--debug_output', action='store_true', default=False,
                        help='Output the raw format to a file called out.txt', required=False)
    parser.add_argument(
        '--debug_limit', help='Limit the debug output in lines', required=False)
    parser.add_argument(
        '--hex_columns', help='columns to convert to hex', nargs='+', default=[])
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    # TODO change this to standard MaterializedMySQL columns https://github.com/Altinity/clickhouse-sink-connector/issues/78
    parser.add_argument('--exclude_columns', help='columns exclude',
                        nargs='*', default=['_sign,_version,is_deleted'])
    parser.add_argument('--threads', type=int,
                        help='number of parallel threads', default=1)

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

    thisScript = argv[0]

    try:
        tables = get_tables_from_regex(args.tables_regex)
        # CH does not print decimal with trailing zero, we need a custom function
        execute_statement(create_function_format_decimal)

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            for table in tables:
              futures.append(executor.submit(calculate_checksum, table[0]))
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

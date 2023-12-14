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
import hashlib
import concurrent.futures
from db.mysql import *
runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def compute_checksum(table, statements, conn):
    sql = ""
    debug_out = None
    if args.debug_output:
        out_file = f"out.{table}.mysql.txt"
        # logging.info(f"Debug output to {out_file}")
        debug_out = open(out_file, 'w')
    try:
        for statement in statements:
            sql = statement

            (result, rowcount) = execute_mysql(conn, sql)
            if rowcount != -1:
                logging.debug("Rows affected "+str(rowcount))
            if result != None and result.returns_rows == True:
                x = [element for tupl in result for element in tupl]
                md5_sum = ""
                cnt = -1
                if args.debug_output:
                    for line in x:
                        debug_out.write(str(line)+'\n')
                else:
                    for line in x:
                        logging.debug(str(line))
                        md5_sum += str(line) + '#'
                        if cnt == - 1:
                            cnt = str(line)

                    logging.debug(md5_sum)
                    m = hashlib.md5()
                    m.update(md5_sum.encode('utf-8'))
                    logging.info("Checksum for table "+args.mysql_database +
                                 "."+table+" = "+m.hexdigest() + " count "+str(cnt))
                    if args.debug_output:
                        debug_out.close()
    finally:
        conn.close()


def get_table_checksum_query(table, conn, binary_encoding):

    (rowset, rowcount) = execute_mysql(conn, "select COLUMN_NAME as column_name, column_type as data_type, IS_NULLABLE as is_nullable from information_schema.columns where table_schema='" +
                                       args.mysql_database+"' and table_name = '"+table+"' order by ordinal_position")

    select = ""
    nullables = []
    data_types = {}
    first_column = True
    min_date_value = args.min_date_value
    max_date_value = args.max_date_value
    max_datetime_value = args.max_datetime_value
    for row in rowset:
        column_name = '`'+row['column_name']+'`'
        data_type = row['data_type']
        is_nullable = row['is_nullable']

        if not first_column:
            select += ","

        if is_nullable == 'YES':
            nullables.append(column_name)
        if 'datetime' == data_type or 'datetime(1)' == data_type or 'datetime(2)' == data_type or 'datetime(3)' == data_type:
            # CH datetime range is not the same as MySQL https://clickhouse.com/docs/en/sql-reference/data-types/datetime64/
            select += f"case when {column_name} >=  substr('{max_datetime_value}', 1, length({column_name})) then substr(TRIM(TRAILING '0' FROM CAST('{max_datetime_value}' AS datetime(3))),1,length({column_name})) else case when {column_name} <= '{min_date_value} 00:00:00' then TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('{min_date_value} 00:00:00.000' AS datetime(3)))) else TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM {column_name})) end end"
        elif 'datetime(4)' == data_type or 'datetime(5)' == data_type or 'datetime(6)' == data_type:
            # CH datetime range is not the same as MySQL https://clickhouse.com/docs/en/sql-reference/data-types/datetime64/ii
            select += f"case when {column_name} >= substr('{max_datetime_value}', 1, length({column_name})) then substr(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('{max_datetime_value}' AS datetime(6)))),1,length({column_name})) else case when {column_name} <= '{min_date_value} 00:00:00' then TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST('{min_date_value} 00:00:00.000000' AS datetime(6)))) else TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM {column_name})) end end"
        elif 'time' == data_type or 'time(1)' == data_type or 'time(2)' == data_type or 'time(3)' == data_type or 'time(4)' == data_type or 'time(5)' == data_type or 'time(6)' == data_type:
            select += f"substr(cast({column_name} as time(6)),1,length({column_name}))"
        elif 'timestamp' == data_type or 'timestamp(1)' == data_type or 'timestamp(2)' == data_type or 'timestamp(3)' == data_type or 'timestamp(4)' == data_type or 'timestamp(5)' == data_type or 'timestamp(6)' == data_type:
            select += f"substr(TRIM(TRAILING '.' from (TRIM(TRAILING '0' from cast({column_name} as char)))),1,length({column_name}))"
        else:
            if 'date' == data_type:  # Date are converted to Date32 in CH
              # CH date range is not the same as MySQL https://clickhouse.com/docs/en/sql-reference/data-types/date
                select += f"case when {column_name} >='{max_date_value}' then CAST('{max_date_value}' AS {data_type}) else case when {column_name} <= '{min_date_value}' then CAST('{min_date_value}' AS {data_type}) else {column_name} end end"
            else:
                if is_binary_datatype(data_type):
                    binary_encode = "lower(hex(cast(" + \
                        column_name+"as binary)))"
                    if binary_encoding == 'base64':
                        binary_encode = "replace(to_base64(cast(" + \
                            column_name+" as binary)),'\\n','')"
                    select += binary_encode
                else:
                    select += column_name + ""
        first_column = False
        data_types[row['column_name']] = data_type

    logging.debug(str(nullables))
    if len(nullables) > 0:
        select += ", concat("
        first = True
        for nullable in nullables:
            if not first:
                select += ','
            else:
                first = False
            select += "ISNULL("+nullable+")"
        select += ")"
    # order is not important
    primary_key_columns = []
    logging.debug(str(primary_key_columns))
    order_by_columns = ""
    if len(primary_key_columns) > 0:
        order_by_columns = ','.join(primary_key_columns)

    query = "select "+select+"  as query from "+args.mysql_database+"."+table
    if args.where:
        query += " where "+args.where

    if len(primary_key_columns) > 0:
        query += " order by " + order_by_columns

    external_column_types = ""
    for column in primary_key_columns:
        external_column_types += ","+column+" "+data_types[column]

    logging.debug("order by columns "+order_by_columns)
    return (query, select, order_by_columns, external_column_types)


def select_table_statements(table, query, select_query, order_by, external_column_types):
    statements = ['set names utf8mb4']
    # todo make sure the fifo is there
    external_table_name = args.mysql_database+"."+table
    limit = ""
    if args.debug_limit:
        limit = " limit "+args.debug_limit
    where = "1=1"
    if args.where:
        where = args.where

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


def get_tables_from_regex(conn, strDSN):
    if args.no_wc:
        return [[args.tables_regex]]
    schema = args.mysql_database
    strCommand = "select TABLE_NAME as table_name from information_schema.tables where table_type='BASE TABLE' and table_schema = '{d}' and table_name rlike '{t}' order by 1".format(
        d=schema, t=args.tables_regex)
    (rowset, rowcount) = execute_mysql(conn, strCommand)
    x = rowset
    conn.close()

    return x


def calculate_sql_checksum(conn, table):

    try:
        if args.ignore_tables_regex:
            rex_ignore_tables = re.compile(
                args.ignore_tables_regex, re.IGNORECASE)
            if rex_ignore_tables.match(table):
                logging.info("Ignoring "+table + " due to ignore_regex_tables")
                return

        statements = []

        (query, select_query, distributed_by,
         external_table_types) = get_table_checksum_query(table, conn, args.binary_encoding)
        statements = select_table_statements(
            table, query, select_query, distributed_by, external_table_types)
        compute_checksum(table, statements, conn)
    finally:
        conn.close()


def calculate_checksum(mysql_table, mysql_user, mysql_password):
    if args.ignore_tables_regex:
        rex_ignore_tables = re.compile(args.ignore_tables_regex, re.IGNORECASE)
        if rex_ignore_tables.match(mysql_table):
            logging.info("Ignoring "+mysql_table +
                         " due to ignore_regex_tables")
            return
    statements = []

    conn = get_mysql_connection(args.mysql_host, mysql_user, mysql_password, args.mysql_port, args.mysql_database)
    calculate_sql_checksum(conn, mysql_table)


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
            '--max_datetime_value', help='Maximum Datetime64 datetime', default='2299-12-31 23:59:59', required=False)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--exclude_columns', help='columns exclude',
                        nargs='+', default=['_sign', '_version'])
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
        tables = get_tables_from_regex(conn, args.tables_regex)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            for table in tables.fetchall():
                futures.append(executor.submit(
                    calculate_checksum, table['table_name'], mysql_user, mysql_password))
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

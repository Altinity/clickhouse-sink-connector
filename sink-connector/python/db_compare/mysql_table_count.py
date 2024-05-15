# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : mysql_table_count
# -- Date         :
# -- Summary      : compute exact count for a (partitioned) mysql table 
# -- Credits      : https://www.percona.com/blog/mysql-8-0-14-a-road-to-parallel-query-execution-is-wide-open/
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
runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def compute_count(table, statements, mysql_user, mysql_password):
    sql = ""
    count = 0
    conn = get_mysql_connection(args.mysql_host, mysql_user, mysql_password, args.mysql_port, args.mysql_database)
    try:
        logging.debug(str(statements))
        for statement in list(statements):
            sql = statement

            (result, rowcount) = execute_mysql(conn, sql)
            if rowcount != -1:
                logging.debug("Rows affected "+str(rowcount))
            if result != None and result.returns_rows == True:
                x = [element for tupl in result for element in tupl]
                count += x[0]
    finally:
        conn.close()
    return count

@staticmethod
def fstr(template, partition_expression):
        return eval(f"f'{template}'")

def select_table_statements(conn, table):
    # TODO adjust the number as a parameter
    statements = []
    # todo make sure the fifo is there
    external_table_name = args.mysql_database+"."+table
    where = ""
    schema = args.mysql_database
    
    if args.where:
        where = f"WHERE {args.where}"
        
    partitions = get_partitions_from_regex(conn,
                                           args.mysql_database,
                                           '^'+table+'$',
                                           exclude_tables_regex=args.exclude_tables_regex,
                                           include_partitions_regex=args.include_partitions_regex,
                                           non_partitioned_tables_only=args.non_partitioned_tables_only)


    partitions = partitions.fetchall()
    if len(partitions) > 0:
        for partition in partitions:
            partition_name = partition['partition_name']
            partition_expression = partition['partition_expression']
            partition_clause = ""
            if partition_name is not None:
                partition_clause = f" partition({partition_name})"
                where = fstr(where, partition_expression) 
            sql = f"select count(*) from {schema}.{table} {partition_clause} {where}"
            statements.append(('set local innodb_parallel_read_threads=32', sql))
    return statements


def get_tables_from_regexp(conn, tables_regexp):
    return get_tables_from_regex(conn, args.no_wc, args.mysql_database, tables_regexp, exclude_tables_regex=args.exclude_tables_regex, non_partitioned_tables_only=args.non_partitioned_tables_only, include_partitions_regex=args.include_partitions_regex)


def calculate_sql_count(conn, table, mysql_user, mysql_password):

    try:
        if args.exclude_tables_regex:
            rex_exclude_tables = re.compile(
                args.exclude_tables_regex, re.IGNORECASE)
            if rex_exclude_tables.match(table):
                logging.info("Excluding "+table + " due to exclude_regex_tables")
                return

        statements = []

        statements = select_table_statements(conn, table)
        row_count = 0
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads_per_table) as executor:
            futures = []
            for queries in statements:
                futures.append(executor.submit(
                    compute_count, table, queries, mysql_user, mysql_password))
            for future in concurrent.futures.as_completed(futures):
                result = future.result()
                row_count += result
                if future.exception() is not None:
                    raise future.exception()
            logging.info("Count for table "+args.mysql_database + "."+table+" = "+str(row_count))
    finally:
        conn.close()


def calculate_table_count(mysql_table, mysql_user, mysql_password):
    if args.exclude_tables_regex:
        rex_exclude_tables = re.compile(args.exclude_tables_regex, re.IGNORECASE)
        if rex_exclude_tables.match(mysql_table):
            logging.info("Excluding "+mysql_table +
                         " due to exclude_regex_tables")
            return
    statements = []

    conn = get_mysql_connection(args.mysql_host, mysql_user, mysql_password, args.mysql_port, args.mysql_database)
    calculate_sql_count(conn, mysql_table, mysql_user, mysql_password)


# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)


def main():

    parser = argparse.ArgumentParser(description='''Compute a quicker MySQL count
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
    parser.add_argument('--include_tables_regex', help='table regexp', required=False, default='.')
    parser.add_argument('--exclude_tables_regex',
                        help='exclude table regexp', required=False)
    parser.add_argument('--include_partitions_regex', help='partitions regex', required=False, default=None)
    parser.add_argument('--non_partitioned_tables_only', dest='non_partitioned_tables_only',
                        action='store_true', default=False)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--order_by', help='order by` clause', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Use --tables_regex as the table', required=False)
    parser.add_argument('--debug_output', action='store_true', default=False,
                        help='Output the raw format to a file called out.txt', required=False)
    parser.add_argument(
        '--debug_limit', help='Limit the debug output in lines', required=False)

    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--exclude_columns', help='columns exclude',
                        nargs='+', default=['_sign', '_version'])
    parser.add_argument('--threads', type=int,
                        help='number of parallel threads', default=1)
    parser.add_argument('--threads_per_table', type=int,
                        help='number of parallel threads per table', default=1)
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
        tables = get_tables_from_regexp(conn, args.include_tables_regex)
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            for table in tables.fetchall():
                futures.append(executor.submit(
                    calculate_table_count, table['table_name'], mysql_user, mysql_password))
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

"""
#r/ -- ============================================================================
# -- FileName     : clickhouse_table_count
# -- Date         : 
# -- Summary      : calculate a checksum for a clickhouse table 
# -- Credits      : 
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
import concurrent.futures
from db.clickhouse import *

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def get_connection(clickhouse_user, clickhouse_password):

    conn = clickhouse_connection(args.clickhouse_host, database=args.clickhouse_database,
                                 user=clickhouse_user, password=clickhouse_password,
                                 port=args.clickhouse_port,
                                 secure=args.secure)
    return conn


def get_tables_from_regex(conn):
    if args.no_wc:
        return [[args.tables_regex]]

    schema = args.clickhouse_database
    partition_clause = ""
    if args.include_partitions_regex:
        partition_clause = f" and (database, table) in (select distinct database, table from system.parts where database = '{schema}' and match(table,'{args.tables_regex}') and active and match(partition,'{args.include_partitions_regex}'))"

    strCommand = f"select name, partition_key from system.tables where database = '{schema}' and match(name,'{args.tables_regex}') {partition_clause} order by 1"
    logging.debug(f"REGEX QUERY: {strCommand}")
    (rowset, rowcount) = execute_sql(conn, strCommand)
    x = rowset
    return x


def calculate_table_count(table, partition_key, clickhouse_user, clickhouse_password):
    if args.ignore_tables_regex:
        rex_ignore_tables = re.compile(args.ignore_tables_regex, re.IGNORECASE)
        if rex_ignore_tables.match(table):
            logging.info("Ignoring "+table + " due to ignore_regex_tables")
            return
    threads = []
    threadID = 1
    # 
    conn = get_connection(clickhouse_user, clickhouse_password)
    partition_query = f"select distinct partition from system.parts where database = '{args.clickhouse_database}' and table='{table}' and active and match(partition,'{args.include_partitions_regex}') order by partition"
    (rowset, rowcount) = execute_sql(conn, partition_query)
    conn.close()
    conn = get_connection(clickhouse_user, clickhouse_password)
    table_count = 0
    for row in rowset:
      partition_value = row[0]
      # 
      sql = f"select count(*) cnt from {args.clickhouse_database}.{table} final where 1=1"
      if args.include_partitions_regex and partition_key != '':
          sql += f" and {partition_key} = '{partition_value}' " 
      if args.where:
          sql = sql + " and " + args.where
      sql += " settings do_not_merge_across_partitions_select_final=1"
      # unsafe due to https://github.com/ClickHouse/ClickHouse/issues/49685
      #sql += " settings do_not_merge_across_partitions_select_final=1" 
      (rowset, rowcount) = execute_sql(conn, sql)
      table_count += rowset[0][0]

    logging.info(f"Count for table {args.clickhouse_database}.{table} = {table_count}")    
    conn.close()

# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()


def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)

def main():

    parser = argparse.ArgumentParser(description='''
  Compute the table count using system.tables 

          ''')
    # Required
    parser.add_argument('--clickhouse_host',
                        help='ClickHouse host', required=True)
    parser.add_argument('--clickhouse_user',
                        help='ClickHouse user', required=False)
    parser.add_argument('--clickhouse_password',
                        help='CH password (discouraged option use a configuration file)', required=False, default=None)
    parser.add_argument('--clickhouse_config_file',
                        help='CH config file either xml or yaml, default is ./clickhouse-client.xml', required=False, default='./clickhouse-client.xml')
    parser.add_argument('--clickhouse_database',
                        help='ClickHouse database', required=True)
    parser.add_argument('--clickhouse_port',
                        help='ClickHouse port', default=9000, required=False)
    parser.add_argument('--secure',
                        help='True or False', default=False, required=False)
    parser.add_argument('--tables_regex', help='table regexp', required=True, default='.')
    parser.add_argument('--include_partitions_regex', help='partitions regex', required=False, default=None)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--ignore_tables_regex',
                        help='Ignore table regexp', required=False)
    parser.add_argument('--no_wc', action='store_true', default=False,
                        help='Runs wc first to determine the table names from the regex', required=False)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
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

        with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
            futures = []
            for table in tables:
              futures.append(executor.submit(calculate_table_count, table[0], table[1], clickhouse_user, clickhouse_password))
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

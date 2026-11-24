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
from ch_sink_tools.db.clickhouse import (
    clickhouse_connection,
    execute_sql,
    resolve_credentials_from_config,
)
from ch_sink_tools.db_compare._sql_text import clickhouse_literal, clickhouse_table_reference
from ch_sink_tools.db_compare.clickhouse_table_checksum import (
    partition_key_within_sorting_key, sign_column_from_engine,
)

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


def get_connection(clickhouse_user, clickhouse_password):

    conn = clickhouse_connection(args.clickhouse_host, database=args.clickhouse_database,
                                 user=clickhouse_user, password=clickhouse_password,
                                 port=args.clickhouse_port,
                                 secure=args.secure)
    return conn


def get_tables_from_regex(conn):
    if args.no_wc:
        # The regex is the table name; its partition key is read only when a
        # partition regex needs it (calculate_table_count).
        return [[args.tables_regex, None]]

    schema = args.clickhouse_database
    partition_clause = ""
    if args.include_partitions_regex:
        partition_clause = (f" and (database, table) in (select distinct database, table from system.parts where database = {clickhouse_literal(schema)} "
                            f"and match(table,{clickhouse_literal(args.tables_regex)}) and active and match(partition,{clickhouse_literal(args.include_partitions_regex)}))")

    strCommand = f"select name, partition_key from system.tables where database = {clickhouse_literal(schema)} and match(name,{clickhouse_literal(args.tables_regex)}) {partition_clause} order by 1"
    logging.debug(f"REGEX QUERY: {strCommand}")
    (rowset, rowcount) = execute_sql(conn, strCommand)
    x = rowset
    return x


def build_count_sql(database, table, where, sign_column, final_per_partition, partition_key=None, partition_value=None):
    """The count statement for one table, or one partition of it (spec 11.02 section 3.8).

    Rows are read FINAL with the same row filter as the checksum side: the sign
    of a Collapsing engine must be > 0 (``sign_column``). ``where`` is one
    operand, parenthesised before it is ANDed onto the rest.
    do_not_merge_across_partitions_select_final=1 is added only when
    ``final_per_partition`` is true, that is every partition-key column is in
    the sorting key. Otherwise a row that an UPDATE moved to another partition
    would keep its old version visible and be counted twice
    (https://github.com/ClickHouse/ClickHouse/issues/49685)."""
    sql = f"select count(*) cnt from {clickhouse_table_reference(database, table)} final where 1=1"
    if partition_value is not None and partition_key:
        sql += f" and {partition_key} = {clickhouse_literal(partition_value)} "
    if where:
        sql += " and (" + where + ")"
    if sign_column:
        sql += f" and {sign_column} > 0"
    if final_per_partition:
        sql += " settings do_not_merge_across_partitions_select_final=1"
    return sql


def engine_full_of(conn, database, table):
    """``engine_full`` of the table, '' when the catalog has no row for it."""
    (rowset, rowcount) = execute_sql(conn, f"select engine_full from system.tables where database = {clickhouse_literal(database)} and name = {clickhouse_literal(table)}")
    return rowset[0][0] if rowset else ''


def final_per_partition_for_table(conn, database, table):
    """True when per-partition FINAL is exact for ``table`` (partition key within the sorting key)."""
    (rowset, rowcount) = execute_sql(conn, "select name, type, 0, 0, is_in_partition_key, is_in_sorting_key from system.columns "
                                           f"where database = {clickhouse_literal(database)} and table = {clickhouse_literal(table)}")
    return partition_key_within_sorting_key(list(rowset))


def calculate_table_count(table, partition_key, clickhouse_user, clickhouse_password):
    if args.ignore_tables_regex:
        rex_ignore_tables = re.compile(args.ignore_tables_regex, re.IGNORECASE)
        if rex_ignore_tables.match(table):
            logging.info("Ignoring "+table + " due to ignore_regex_tables")
            return
    database = args.clickhouse_database
    conn = get_connection(clickhouse_user, clickhouse_password)
    try:
        if args.include_partitions_regex:
            if partition_key is None:
                (rowset, rowcount) = execute_sql(conn, f"select partition_key from system.tables where database = {clickhouse_literal(database)} and name = {clickhouse_literal(table)}")
                partition_key = rowset[0][0] if rowset else ''
            partition_query = (f"select distinct partition from system.parts where database = {clickhouse_literal(database)} "
                               f"and table={clickhouse_literal(table)} and active and match(partition,{clickhouse_literal(args.include_partitions_regex)}) order by partition")
            (rowset, rowcount) = execute_sql(conn, partition_query)
            partition_values = [row[0] for row in rowset]
        else:
            # No partition regex: one count over the whole table. The partition
            # list used to be read with match(partition,'None'), which matches no
            # partition, so every table printed 0 (spec 13.06 D-13.06-18).
            partition_values = [None]
        # The same row filter as the checksum side (spec 11.02 section 3.8).
        sign_column = getattr(args, "sign_column", "")
        if not sign_column:
            sign_column = sign_column_from_engine(engine_full_of(conn, database, table))
        final_per_partition = final_per_partition_for_table(conn, database, table)
    finally:
        conn.close()
    conn = get_connection(clickhouse_user, clickhouse_password)
    try:
        table_count = 0
        for partition_value in partition_values:
            sql = build_count_sql(database, table, args.where, sign_column, final_per_partition, partition_key, partition_value)
            (rowset, rowcount) = execute_sql(conn, sql)
            table_count += rowset[0][0]

    finally:
        conn.close()

    logging.info(f"Count for table {database}.{table} = {table_count}")

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
    parser.add_argument('--sign_column', help='Column whose value > 0 marks a live row. Default: derived from the engine like the checksum side -- the sign of a (Replicated)(Versioned)CollapsingMergeTree, no filter otherwise', default='', required=False)
    parser.add_argument('--tables_regex', help='table regexp', required=True)
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

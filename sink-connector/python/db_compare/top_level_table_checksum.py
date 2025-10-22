#!/usr/bin/env python3
import yaml
import sys
import argparse
import logging
from db.mysql import *
import concurrent.futures
from datetime import datetime
from subprocess import Popen, PIPE
import subprocess
import time

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


def parse_checksum(data, table):
    # Step 1: Decode the byte string into a regular string
    decoded_data = data.decode('utf-8').strip()  # Remove the trailing newline with .strip()
    # Step 2: Split the string into components
    parts = decoded_data.split()
    checksum = None
    row_count = None
    if len(parts) == 3:
        # Extract the three values
        table = parts[0]      
        checksum = parts[1]   
        row_count = int(parts[2]) 
    else:
        logging.error(f"Invalid checksum output from {data} for table {table}")
    return (table, checksum, row_count)


def run_quick_safe_checksum(cmd, host, table):
    start = time.perf_counter()    
    (rc, stdout) = run_quick_safe_command(cmd)
    duration = time.perf_counter() - start
    if rc == '0':
        (table, checksum, count) = parse_checksum(stdout, table)
        logging.info(f"{( host, table, checksum, count)} in {duration:0.3f} seconds" )
        return ( host, table, checksum, count)
    else:
        logging.error(f"{cmd}. failed")
        return None
    

def compute_checksum (database, table, mysql_user, mysql_password, mysql_host, replica_hosts, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None):
    table_name = f"{database}.{table}"
    logging.info(f"Checksumming {table_name}")
    commands = []
    cmd = get_mysql_checksum_command(mysql_host, database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output, defaults_file=defaults_file)
   
    commands.append((mysql_host,cmd))
    
    for ch_host in replica_hosts:
         cmd = get_clickhouse_checksum_command(ch_host, database, table, pk, max_pk, where=where, ignored_columns=ignored_columns, debug_output=debug_output)
         commands.append((ch_host, cmd))
    results = []    
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(commands)) as executor:
            futures = []
            for (host, cmd) in commands:
                future = executor.submit(
                    run_quick_safe_checksum, cmd, host, table)
                futures.append(future)
            for future in concurrent.futures.as_completed(futures):
                if future.exception() is not None:
                    raise future.exception()
                else:
                    results.append(future.result())
    return results


def get_tables_from_regexp(conn, database, tables_regexp):
    return get_tables_from_regex(conn, args.no_wc, database, tables_regexp, include_partitions_regex=args.include_partitions_regex, exclude_tables_regex=args.exclude_tables_regex, non_partitioned_tables_only=args.non_partitioned_tables_only)


def get_mysql_checksum_command(mysql_host, database, table, pk, max_pk, where, ignored_columns=[], debug_output=False, defaults_file=None):
    partition_date = args.partition_date
    where_argument = '--where " 1=1 '
    if where:
        where_argument += f" and {where} "
    if partition_date:
      where_argument += f""" and {{partition_expression}}={partition_date:%Y%m%d}"""
    where_argument += '"'
    
    ignored_columns_clause = ""
    if len(ignored_columns) > 0:
        logging.info(f"Ignoring columns {ignored_columns} for table {table}")
        ignored_columns_clause = "--exclude_columns "+",".join(ignored_columns)
        
    debug_output_clause = ""
    if debug_output:
        debug_output_clause = "--debug_output"
    
    defaults_file_clause = ""
    if defaults_file:
        defaults_file_clause = f"--defaults_file={defaults_file}"
    cmd = f"""set -e pipefail;python db_compare/mysql_table_checksum.py --threads_per_table {args.threads_per_table} --threads={args.threads} --min_date_value "1900-01-01" --mysql_host {mysql_host} --mysql_database {database} --tables_regex "^{table}$" {where_argument} --min_datetime_value "1969-12-31 18:00:00"  --max_datetime_value "2299-12-31 00:00:00"  --binary_encoding base64 {ignored_columns_clause} {debug_output_clause} {defaults_file_clause} | grep -i checksum | awk '{{print $11" "$13" "$15}}' """ 
    logging.debug(f"MySQL command: {cmd}")
    return cmd 


def get_clickhouse_checksum_command(ch_host, database, table, pk, max_pk, where=None, ignored_columns=[], debug_output=False):
    partition_date = args.partition_date   
    where_argument = '--where " 1=1 '
    if where:
        where_argument += f" and {where} "
    if partition_date:
      where_argument += f""" and {{partition_expression}}="""+f"""toDate(\\\\'{partition_date:%Y-%m-%d}\\\\') """

    where_argument += '"'
    
    ignored_columns_clause = "--exclude_columns _version,is_deleted,_is_deleted"
    if len(ignored_columns) > 0:
        logging.info(f"Ignoring columns {ignored_columns} for table {table}")
        ignored_columns_clause += ","+",".join(ignored_columns)
    
    debug_output_clause = ""
    if debug_output:
        debug_output_clause = "--debug_output"
        
    cmd = f"""set -e pipefail;python db_compare/clickhouse_table_checksum.py --max_memory_usage 80000000000 --threads={args.threads} --clickhouse_host {ch_host} --clickhouse_database  {database}  --tables_regex "^{table}$" {where_argument}  --min_datetime_value "1969-12-31 18:00:00"  --max_datetime_value "2299-12-31 00:00:00" {ignored_columns_clause} --sign_column "" {debug_output_clause} |grep -i checksum | awk '{{print $11" "$13" "$15}}' """ 
    return cmd 


def analyze_differences(results, mysql_host, replica_hosts):
    source_results = [result for result in results if result[0] == mysql_host]
    replica_results = [result for result in results if result[0] in replica_hosts]
    if len(source_results) == 1:
        (mysql_checksum, mysql_count) = (source_results[0][2], source_results[0][3])
        is_difference = False
        for replica_result in replica_results:
            (checksum, count) = (replica_result[2], replica_result[3])
            if  (checksum, count) !=  (mysql_checksum, mysql_count):
                logging.warning(f"Checksum difference : {replica_result} to {source_results[0]}")
                is_difference = True
        if not is_difference:
            logging.info(f"No difference for {source_results[0][1]}")
   
    
def lock_tables(conn, table):
    lock_stmt = f"FLUSH TABLE {table} WITH READ LOCK"
    logging.info(f"Locking table with statement {lock_stmt}")
    execute_mysql(conn, lock_stmt)
    
    
def unlock_tables(conn, table):
    unlock_stmt = "UNLOCK TABLES"
    logging.info(f"Unlocking table {table} with statement {unlock_stmt}")
    execute_mysql(conn, unlock_stmt)
    
def run_config(config):
    """Display the parsed configuration."""
    logging.info("\nConfiguration Details:")
    
    mysql_host = config['source']['mysql']['host']
    logging.info(f"Source MySQL Host: {mysql_host}")
    
    replica_hosts = []
    for i, replica in enumerate(config['replicas']):
        replica_hosts.append(replica['clickhouse']['host'])
   
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
                for table in tables.fetchall():
                    if args.lock_tables_on_source:
                        # we need a new connection for each table since the lock is per connection
                        conn = get_mysql_connection(mysql_host, mysql_user,
                                    mysql_password, args.mysql_port, database)
                        logging.info(f"Locking table {table['table_name']} on source {mysql_host}")
                        lock_tables(conn, table['table_name'])
                        time.sleep(args.sleep_after_lock)  # slight delay for the sink-connector to catch up
                        future_to_conn[table['table_name']] = conn
                        
                    pk = mysql_pk_columns(conn, database, table['table_name'])
                    pk_column = pk[0] if len(pk) > 0 else 'NULL'
                    (min_pk, max_pk) = get_min_max_pk_value(conn, table['table_name'], pk_column, '1=1')
                    table_name = f"{database}.{table['table_name']}"
                    ignored_columns = []
                    if database in ignored_columns_map and table['table_name'] in ignored_columns_map[database]:
                        ignored_columns = list(ignored_columns_map[database][table['table_name']].keys())
                    future = executor.submit(
                        compute_checksum, database, table['table_name'], mysql_user, mysql_password, mysql_host, replica_hosts, pk_column, max_pk, args.where, ignored_columns = ignored_columns, debug_output = args.debug_output, defaults_file=args.defaults_file)
                    futures.append(future)
                    future_to_table[future] = table['table_name']
                for future in concurrent.futures.as_completed(futures):
                    conn =  future_to_conn.get(future_to_table[future], None)
                    table = future_to_table[future]
                    if future.exception() is not None:
                        logging.error("Exception in table " + table)
                        logging.error(future.exception())
                        if conn:
                            unlock_tables(conn, table)
                        raise future.exception()
                    else:
                        if conn:
                            unlock_tables(conn, table)
                        analyze_differences(future.result(), mysql_host, replica_hosts)      

        except (KeyboardInterrupt, SystemExit):
            logging.info("Received interrupt")
            os._exit(1)
        except Exception as e:
            logging.error("Exception in main thread : " + str(e))
            logging.error(traceback.format_exc())
            sys.exit(1)

    logging.debug("Exiting Main Thread")
    sys.exit(0)
    
def run_quick_safe_command(cmd):
    logging.debug("cmd " + cmd)
    process = subprocess.Popen(cmd,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT,
                               shell=True)
    stdout, stderr = process.communicate()
    rc = str(process.poll())
    if stdout:
        logging.debug(str(stdout).strip())
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

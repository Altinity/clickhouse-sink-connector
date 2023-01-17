# python db_load/clickhouse_myloader.py --clickhouse_host localhost  --clickhouse_schema world --mydumper_dir $HOME/dbdumps/world --db_user root --db_password root --threads 16 --ch_module clickhouse-client-22.5.1.2079 --mysql_source_schema world
import clickhouse_driver
from clickhouse_driver import connect
import argparse
import sys
import logging
import concurrent.futures
import re
import gzip
import os
import sys
import time
import glob
import subprocess
from multiprocessing import Pool
from pathlib import Path
import time
import datetime
import zoneinfo


def run_process(process):
    os.system('{}'.format(process))


def run_command(cmd):
    """
    # -- ======================================================================
    # -- run the command that is passed as cmd and return True or False
    # -- ======================================================================
    """
    logging.debug("cmd " + cmd)
    process = subprocess.Popen(cmd,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT,
                               shell=True)
    for line in process.stdout:
        logging.info(line.decode().strip())
        time.sleep(0.02)
    rc = str(process.poll())
    logging.debug("return code = " + str(rc))
    return rc


def run_quick_command(cmd):
    logging.debug("cmd " + cmd)
    process = subprocess.Popen(cmd,
                               stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT,
                               shell=True)
    stdout, stderr = process.communicate()
    rc = str(process.poll())
    if stdout:
        logging.info(str(stdout).strip())
    logging.debug("return code = " + rc)
    if rc != "0":
        logging.error("command failed : terminating")
        raise AssertionError
    return rc, stdout

def clickhouse_connection(host, database='default', user='default', port=9000, password=''):
    conn = connect(host=host,
                   user=user,
                   password=password,
                   port=port,
                   database=database,
                   connect_timeout=20,
                   )
    return conn


def clickhouse_execute_conn(conn, sql):
    logging.debug(sql)
    cursor = conn.cursor()
    cursor.execute(sql)
    result = cursor.fetchall()
    return result


def get_connection(args, database='default'):

    conn = clickhouse_connection(args.clickhouse_host, database=database,
                                 user=args.clickhouse_user, password=args.clickhouse_password, port=9000)
    return conn


def parse_schema_path(path):
    p = Path(path)
    name = p.name
    name = name.replace('-schema.sql.gz', '')
    db_table = name
    table = db_table.split('.')[1]
    db = db_table.split('.')[0]
    return (db, table)


def find_primary_key(source):
    pattern = r'primary[\s]+key.*\((.*?)\).*'
    regex = re.compile(pattern, re.IGNORECASE)
    for match in regex.finditer(source):
        logging.info("PK :"+match.group(1))
        return match.group(1)
    return None

# you may dump from a machine with a different TZ
# this is essential to dump timestamps


def find_dump_timezone(source):
    pattern = r'SET TIME_ZONE=\'(.*?)\''
    regex = re.compile(pattern, re.IGNORECASE)
    for match in regex.finditer(source):
        logging.info(match.group(1))
        return match.group(1)
    return None


def convert_to_clickhouse_table(user_name, table_name, source):
    primary_key = find_primary_key(source)
    if primary_key is None:
        logging.warning("No PK found for "+table_name +
                        " defaulting to order by tuple()")
        primary_key = "tuple()"

    settings = "index_granularity = 8192"

    # TODO partitioning
    partitioning_options = ""
    src = source
    # get rid of SQL comments
    src = re.sub(r'.*\/\*.*', '', src)

    # no autoincrement in ClickHouse
    src = re.sub(r'\bAUTO_INCREMENT\b', '', src)
    # -- ===========================================================================
    src = re.sub(r'\stime\s', ' String ', src)
    src = re.sub(r'\sdate\s', ' Date32 ', src)
    src = re.sub(r'\spoint\s', ' String ', src)
    src = re.sub(r'\sgeometry\s', ' String ', src)
    # dangerous
    src = re.sub(r'\bDEFAULT\b.*,', ',', src)
    src = re.sub(r'\sCOLLATE\s(.*?)([\s,])', ' \\2', src, )
    src = re.sub(r'\sCHARACTER\sSET\s(.*?)([\s,])', ' \\2', src)

    # ClickHouse does not support constraints, indices, primary and unique keys
    src = re.sub(r'.*\bCONSTRAINT\b.*', '', src)
    src = re.sub(r'.*\bPRIMARY KEY\b.*', '', src)
    src = re.sub(r'.*\bUNIQUE\b.*', '', src)
    src = re.sub(r'.*\bKEY\b.*', '', src)
    src = re.sub(r'.*\bforeign\b.*', '', src)

    # adding virtual columns ver and sign
    src = re.sub(r'\) ENGINE',
                 '  `sign` Int8 DEFAULT 1,\n  `ver` UInt64 DEFAULT 0\n) ENGINE', src)
    src = re.sub(r'ENGINE=InnoDB[^;]*', 'ENGINE = ReplacingMergeTree(ver) ' +
                 partitioning_options + ' ORDER BY ('+primary_key+') SETTINGS '+settings, src)

    lines = src.splitlines()
    res = ""
    
    columns_pattern = r'^\s*(`.*?`)\s+(.*?)\s+'
    
    # crude implementation, it should be possible to use DESCRIBE file, potentially add CH bugs
    columns = [] 
    for line in lines:
        altered_line = line
        # column without nullable info are default nullable in MySQL, while they are not null in ClickHouse
        if "NULL" not in line and "DEFAULT" not in line:
            altered_line = re.sub(r',$', ' DEFAULT NULL,', altered_line)
        res += altered_line + '\n'
        
        match = re.match(columns_pattern, altered_line)
        if match:
            column_name = match.group(1)
            datatype = match.group(2)        
            logging.info(f"{column_name} {datatype}")
            columns.append({'column_name':column_name,'datatype':datatype})
    return (res, columns)


def get_unix_timezone_from_mysql_timezone(timezone):
    tz = "UTC"
    for tz in zoneinfo.available_timezones():
        offset = datetime.datetime.now(zoneinfo.ZoneInfo(
            tz)).utcoffset().total_seconds()/60/60
        timezone_from_offset = ""
        if offset >= 0:
            timezone_from_offset += "+"
        else:
            timezone_from_offset += "-"
        offset_int = int(offset)
        timezone_from_offset += f"{abs(offset_int):02}:{abs(round((offset-offset_int)*60)):02}"
        logging.debug(tz + "  => "+timezone_from_offset)
        if timezone == timezone_from_offset:
            break
    return tz


def load_schema(args, dry_run=False):
    schema_map = {}
    # create database
    with get_connection(args) as conn:

        database_file = args.mydumper_dir + \
            f"/{args.mysql_source_database}-schema-create.sql.gz"

        with gzip.open(database_file, "r") as db_file:
            source = db_file.read().decode('UTF-8')
            logging.info(source)
            if not dry_run:
                clickhouse_execute_conn(conn, source)

    # create tables
    timezone = None
    with get_connection(args, args.clickhouse_database) as conn:

        schema_file = args.mydumper_dir + '/*-schema.sql.gz'

        for file in glob.glob(schema_file):
            (db, table) = parse_schema_path(file)
            logging.info(f"{file} {db}.{table}")
            with gzip.open(file, "r") as schema_file:
                source = schema_file.read().decode('UTF-8')
                logging.info(source)
                (table_source, columns) = convert_to_clickhouse_table(db, table, source)
                logging.info(table_source)
                timezone = find_dump_timezone(source)
                logging.info(f"Timezone {timezone}")
                
                schema_map[f"{db}.{table}"] = columns
                if not dry_run:
                    clickhouse_execute_conn(conn, table_source)
    tz = get_unix_timezone_from_mysql_timezone(timezone)

    return (tz, schema_map)

def get_column_list(schema_map, schema, table, virtual_columns, transform=False):
    key = f"{schema}.{table}"
    column_list ="*"
    if key in schema_map:
        columns = schema_map[key]
        column_list = ""
        first = True
        for column in columns:
            if column['column_name'] not in virtual_columns:
                datatype = column['datatype']
                column_name = column['column_name'].replace('`','\\`')
                
                if first:
                    first=False
                else:
                    column_list +=","
                # binary data is escaped
                if transform and ("varbinary" in datatype or "blob" in datatype or "point" == datatype): 
                    column_list += "lower(hex("+column_name+"))" 
                else:
                    column_list += column_name 
    return column_list


def load_data(args, timezone, schema_map, dry_run = False):
    clickhouse_host = args.clickhouse_host
    ch_schema = args.clickhouse_database
    ch_module = args.ch_module
    mysql_schema = args.mysql_source_database

    schema_file = args.mydumper_dir + '/*-schema.sql.gz'
    for files in glob.glob(schema_file):
        (schema, table_name) = parse_schema_path(files)
        dfile = files.split("-")[0]
        print(f"{files}")
        cmd = f"""zcat  {files}"""
        data_files = glob.glob(dfile + ".*dat.gz")
        columns = get_column_list(schema_map, schema, table_name, args.virtual_columns, transform=False)
        transformed_columns =  get_column_list(schema_map, schema, table_name, args.virtual_columns, transform=True)
        for data_file in data_files:
            # double quote escape logic https://github.com/ClickHouse/ClickHouse/issues/10624
            structure = columns.replace(","," String,")+" String"
            #cmd = f"""export TZ={timezone}; module load {ch_module}; gunzip --stdout {data_file}  | sed -e 's/\\\\"/""/g' | sed -e "s/\\\\\\'/'/g" | clickhouse-client --use_client_time_zone 1 -h {clickhouse_host} --query="INSERT INTO {ch_schema}.{table_name}({columns})  SELECT {transformed_columns} FROM input('{structure}') FORMAT CSV" -u{args.clickhouse_user} --password {args.clickhouse_password} -mn """
            cmd = f"""export TZ={timezone}; module load {ch_module}; gunzip --stdout {data_file}  | clickhouse-client --use_client_time_zone 1 -h {clickhouse_host} --query="INSERT INTO {ch_schema}.{table_name}({columns})  SELECT {transformed_columns} FROM input('{structure}') FORMAT CSV" -u{args.clickhouse_user} --password {args.clickhouse_password} -mn """
            logging.info(cmd)
            (rc, result) = run_quick_command(cmd)
            logging.debug(result)
            

def main():
    root = logging.getLogger()
    root.setLevel(logging.INFO)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    parser = argparse.ArgumentParser(description='''
    Convert MySQL tables to ClickHouse tables.
   ''')

    parser = argparse.ArgumentParser(description='''
            ''')

    parser.add_argument('--clickhouse_host', help='CH host', required=True)
    parser.add_argument('--clickhouse_user', help='CH user', required=True)
    parser.add_argument('--clickhouse_password',
                        help='CH password', required=True)
    parser.add_argument('--clickhouse_database',
                        help='Clickhouse database name', required=True)
    parser.add_argument('--mysql_source_database',
                        help='MySQL source schema', required=True)
    parser.add_argument(
        '--mydumper_dir', help='Location of mydumper files', required=True)
    parser.add_argument('--threads', default=32,
                        help='Number of threads', required=True)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--ch_module', help='ClickHouse module',
                        required=False, default="clickhouse-client-22.5.1.2079")
    parser.add_argument('--schema_only', dest='schema_only',
                        action='store_true', default=False)
    parser.add_argument('--data_only', dest='data_only',
                        action='store_true', default=False)
    parser.add_argument('--dry_run', dest='dry_run',
                        action='store_true', default=False)
    parser.add_argument('--virtual_columns', help='virtual_columns',
                        nargs='+', default=['`sign`', '`ver`'])
      
    args = parser.parse_args()
    schema = not args.data_only
    data = not args.schema_only
    timezone = None
    schema_map = {}
    if schema:
        (timezone, schema_map) = load_schema(args, dry_run = args.dry_run)
    if data:
        if timezone is None:
            (timezone, schema_map) = load_schema(args, dry_run=True)
            
        logging.info(str(schema_map))
        load_data(args, timezone, schema_map, dry_run = args.dry_run)


if __name__ == '__main__':
    main()
    logging.info("Finished")

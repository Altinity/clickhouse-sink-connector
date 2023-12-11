# python db_load/clickhouse_myloader.py --clickhouse_host localhost  --clickhouse_schema world --dump_dir $HOME/dbdumps/world --db_user root --db_password root --threads 16 --ch_module clickhouse-client-22.5.1.2079 --mysql_source_schema world
from subprocess import Popen, PIPE
from db.mysql import is_binary_datatype
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
from db.clickhouse import *
from db_load.mysql_parser.mysql_parser import convert_to_clickhouse_table_antlr

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.dirname(SCRIPT_DIR))


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
    return rc, stdout


def get_connection(args, clickhouse_user, clickhouse_password, database='default'):
    conn = clickhouse_connection(args.clickhouse_host, database=database,
                                 user=clickhouse_user, password=clickhouse_password, port=args.clickhouse_port)
    return conn


def parse_schema_path(path):
    p = Path(path)
    name = p.name
    name = name.replace('-schema.sql.gz', '')
    db_table = name
    table = db_table.split('.')[1]
    db = db_table.split('.')[0]
    return (db, table)


def parse_schema_path_mysqlshell(path):
    p = Path(path)
    name = p.name
    name = name.replace('.sql', '')
    db_table = name
    table = db_table.split('@')[1]
    db = db_table.split('@')[0]
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


def find_create_table(source):
    pattern = r'CREATE TABLE'
    regex = re.compile(pattern, re.IGNORECASE)
    for match in regex.finditer(source):
        return True
    return False


def find_partitioning_options(source):
    # initial support for partitioning by range columns
    pattern = r'PARTITION\s+BY\s+RANGE\s+COLUMNS\((.*?)\)'
    regex = re.compile(pattern, re.IGNORECASE)
    partitioning_keys = None
    for match in regex.finditer(source):
        partitioning_keys = match.group(1)
        logging.info("Partitioning key :"+partitioning_keys)
        break
    partitioning_options = ""
    if partitioning_keys:
        partitioning_options = f"PARTITION BY {partitioning_keys}"
    return partitioning_options


def convert_to_clickhouse_table_regexp(user_name, table_name, source, rmt_delete_support, datetime_timezone):

    # do we have a table in the source

    if not find_create_table(source):
        return ('', [])

    primary_key = find_primary_key(source)
    if primary_key is None:
        logging.warning("No PK found for "+table_name +
                        " defaulting to order by tuple()")
        primary_key = "tuple()"

    settings = "index_granularity = 8192"

    # partitioning
    partitioning_options = find_partitioning_options(source)
    src = source
    # get rid of SQL comments
    src = re.sub(r'\/\*(.*?)\*\/;', '', src)
    src = re.sub(r'\/\*(.*?)\*\/', '', src)
    # no autoincrement in ClickHouse
    src = re.sub(r'\bAUTO_INCREMENT\b', '', src)
    # -- ===========================================================================
    src = re.sub(r'\stime\s', ' String ', src)
    src = re.sub(r'\stime(.*?)\s', ' String ', src)
    src = re.sub(r'\sjson\s', ' String ', src)
    # Date32 may be a better alternative as Date range are close to MySQL
    src = re.sub(r'\sdate\s', ' Date32 ', src)
    src = re.sub(r'\sdatetime\s', ' DateTime64(3) ', src)
    src = re.sub(r'\sdatetime(.*?)\s', ' DateTime64\\1 ', src)
    src = re.sub(r'\stimestamp\s', ' DateTime64(3) ', src)
    src = re.sub(r'\stimestamp(.*?)\s', ' DateTime64\\1 ', src)
    src = re.sub(r'\spoint\s', ' Point ', src)
    # src = re.sub(r'\sdouble\s', ' Decimal(38,10) ', src)
    src = re.sub(r'\sgeometry\s', ' Geometry ', src)
    # dangerous
    src = re.sub(r'\bDEFAULT\b.*,', ',', src)
    src = re.sub(r'\sCOLLATE\s(.*?)([\s,])', ' \\2', src, )
    src = re.sub(r'\sCHARACTER\sSET\s(.*?)([\s,])', ' \\2', src)
    # it is a challenge to convert MySQL expression in generated columns
    src = re.sub(r'.*GENERATED ALWAYS AS.*', ' ', src)
    src = re.sub(r'\bVIRTUAL\b', ' ', src)
    # ClickHouse does not support constraints, indices, primary and unique keys
    src = re.sub(r'.*\bCONSTRAINT\b.*', '', src)
    src = re.sub(r'.*\bPRIMARY KEY\b.*\(.*', '', src)
    # primary key on the column itself
    src = re.sub(r'\bPRIMARY KEY\b', '', src)
    src = re.sub(r'.*\bUNIQUE\b.*', '', src)
    src = re.sub(r'.*\bKEY\b.*', '', src)
    src = re.sub(r'.*\bforeign\b.*', '', src)
    src = re.sub(r'', '', src)
    # adding virtual columns ver and sign
    virtual_columns = "`_sign` Int8 DEFAULT 1,\n  `_version` UInt64 DEFAULT 0\n"
    if rmt_delete_support:
        virtual_columns = "`is_deleted` UInt8 DEFAULT 0,\n  `_version` UInt64 DEFAULT 0\n"

    src = re.sub(r'\) ENGINE',
                 '  '+virtual_columns+') ENGINE', src)

    rmt_engine = "ENGINE = ReplacingMergeTree(_version) "
    if rmt_delete_support:
        rmt_engine = "ENGINE = ReplacingMergeTree(_version, is_deleted) "

    src = re.sub(r'ENGINE=InnoDB[^;]*', rmt_engine +
                 partitioning_options + ' ORDER BY ('+primary_key+') SETTINGS '+settings, src)

    lines = src.splitlines()
    res = ""

    columns_pattern = r'^\s*(`.*?`)\s+(.*?)\s+'

    # crude implementation, it should be possible to use DESCRIBE file, potentially add CH bugs
    columns = []
    for line in lines:
        altered_line = line
        # column without nullable info are default nullable in MySQL, while they are not null in ClickHouse
        if ("NULL" not in line and "DEFAULT" not in line):
            altered_line = re.sub(r',$', ' DEFAULT NULL,', altered_line)

        match = re.match(columns_pattern, altered_line)
        if match:
            column_name = match.group(1)
            datatype = match.group(2)
            nullable = False if "NOT NULL" in line else True
            logging.info(f"{column_name} {datatype}")
            columns.append({'column_name': column_name,
                           'datatype': datatype, 'nullable': nullable})

            # tables with no PK miss commas
            if altered_line.strip() != "" and not altered_line.endswith(',') and not altered_line.endswith(';'):
                altered_line += ","

        res += altered_line + '\n'

    # convert binary types to String until CH support GIS binary : https://dev.mysql.com/doc/refman/8.0/en/gis-data-formats.html#gis-wkb-format
    res = re.sub(r'\sPoint\s', ' String ', res)
    res = re.sub(r'\sGeometry\s', ' String ', res)
    res = re.sub(r'\sgeomcollection\s', ' String ', res)
    res = re.sub(r'\slinestring\s', ' String ', res)
    res = re.sub(r'\smultilinestring\s', ' String ', res)
    res = re.sub(r'\smultipoint\s', ' String ', res)
    res = re.sub(r'\smultipolygon\s', ' String ', res)
    res = re.sub(r'\spolygon\s', ' String ', res)
    res = re.sub(r'\sbit\s', ' String ', res)
    res = re.sub(r'\sbit(.*?)\s', ' String ', res)
    res = re.sub(r'\sbinary\s', ' String ', res)
    res = re.sub(r'\sbinary(.*?)\s', ' String ', res)
    res = re.sub(r'\sset\([^\)]*?\)', ' String ', res)
    res = res.replace(" `_version` UInt64 DEFAULT 0,",
                      " `_version` UInt64 DEFAULT 0")
    return (res, columns)


def convert_to_clickhouse_table(user_name, table_name, source, rmt_delete_support, use_regexp_parser, datetime_timezone):
    # do we have a table in the source
    if not find_create_table(source):
        return ('', [])

    src = source
    # if use_regexp_parser == True:
    #   return convert_to_clickhouse_table_regexp(user_name, table_name, source, rmt_delete_support, datetime_timezone)
    # the progressive grammar trims the comment
    partition_options = find_partitioning_options(source)

    try:
        return convert_to_clickhouse_table_antlr(src, rmt_delete_support, partition_options, datetime_timezone)
    except Exception as ex:
        logging.info(f"Use regexp DDL converter")
        logging.info(f"{ex}")
        return convert_to_clickhouse_table_regexp(user_name, table_name, source, rmt_delete_support, datetime_timezone)


def get_unix_timezone_from_mysql_timezone(timezone):
    tz = "UTC"
    timezones = zoneinfo.available_timezones()
    sorted(timezones)
    for tz in timezones:
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


def load_schema(args, clickhouse_user=None, clickhouse_password=None,  dry_run=False, datetime_timezone=None):

    if args.mysqlshell:
        return load_schema_mysqlshell(args,   clickhouse_user=clickhouse_user, clickhouse_password=clickhouse_password, dry_run=dry_run, datetime_timezone=datetime_timezone)

    schema_map = {}
    # create database
    with get_connection(args, clickhouse_user, clickhouse_password) as conn:

        database_file = args.dump_dir + \
            f"/{args.mysql_source_database}-schema-create.sql.gz"

        with gzip.open(database_file, "r") as db_file:
            source = db_file.read().decode('UTF-8')
            logging.info(source)
            if not dry_run:
                clickhouse_execute_conn(conn, source)

    # create tables
    timezone = None
    with get_connection(args, clickhouse_user, clickhouse_password, args.clickhouse_database) as conn:

        schema_file = args.dump_dir + '/*-schema.sql.gz'

        for file in glob.glob(schema_file):
            (db, table) = parse_schema_path(file)
            logging.info(f"{file} {db}.{table}")
            with gzip.open(file, "r") as schema_file:
                source = schema_file.read().decode('UTF-8')
                logging.info(source)
                (table_source, columns) = convert_to_clickhouse_table(
                    db, table, source, args.rmt_delete_support, args.use_regexp_parser, datetime_timezone)
                logging.info(table_source)
                timezone = find_dump_timezone(source)
                logging.info(f"Timezone {timezone}")

                if table_source != '':
                    schema_map[f"{db}.{table}"] = columns
                    if not dry_run:
                        clickhouse_execute_conn(conn, table_source)

    tz = get_unix_timezone_from_mysql_timezone(timezone)

    return (tz, schema_map)


def load_schema_mysqlshell(args, clickhouse_user, clickhouse_password, dry_run=False, datetime_timezone=None):

    schema_map = {}
    # create database
    with get_connection(args, clickhouse_user, clickhouse_password) as conn:

        source = f"create database if not exists {args.clickhouse_database}"
        if not dry_run:
            try:
                clickhouse_execute_conn(conn, source)
            except Exception as e:
                logging.error(f"Database create error: {e}")
    # create tables
    timezone = '+00:00'
    with get_connection(args, clickhouse_user, clickhouse_password, args.clickhouse_database) as conn:

        schema_file_wildcard = args.dump_dir + \
            f"/{args.mysql_source_database}@*.sql"
        schema_files = glob.glob(schema_file_wildcard)
        if len(schema_files) == 0:
            logging.error("Cannot find schema files")
            return

        for file in schema_files:
            if not re.search(r'@[^.]+\.sql', file):
                continue

            (db, table) = parse_schema_path_mysqlshell(file)
            logging.info(f"{file} {db}.{table}")
            with open(file, "r") as schema_file:
                source = schema_file.read()
                logging.info(source)
                (table_source, columns) = convert_to_clickhouse_table(
                    db, table, source, args.rmt_delete_support, args.use_regexp_parser, datetime_timezone)
                logging.info(table_source)
                # timezone = find_dump_timezone(source)
                logging.info(f"Timezone {timezone}")
                if table_source != '':
                    schema_map[f"{db}.{table}"] = columns
                    if not dry_run:
                        clickhouse_execute_conn(conn, table_source)

    tz = get_unix_timezone_from_mysql_timezone(timezone)

    return (tz, schema_map)


def get_column_list(schema_map, schema, table, virtual_columns, transform=False, mysqlshell=False):
    key = f"{schema}.{table}"
    column_list = "*"
    if key in schema_map:
        columns = schema_map[key]
        column_list = ""
        first = True
        for column in columns:
            if column['column_name'] not in virtual_columns:
                datatype = column['datatype']
                column_name = column['column_name'].replace('`', '\\`')

                if first:
                    first = False
                else:
                    column_list += ","
                # binary data is escaped
                logging.debug(f"{table} {column_name} {datatype}")
                if transform and is_binary_datatype(datatype):
                    if mysqlshell:
                        column_list += f"if({column_name}='\\N', null, lower(hex(base64Decode({column_name}))))"
                    else:
                        column_list += "lower(hex("+column_name+"))"
                else:
                    column_list += column_name
    return column_list


def load_data(args, timezone, schema_map, clickhouse_user=None, clickhouse_password=None, dry_run=False):

    if args.mysqlshell:
        load_data_mysqlshell(args, timezone, schema_map, clickhouse_user=clickhouse_user, clickhouse_password=clickhouse_password, dry_run=False)

    clickhouse_host = args.clickhouse_host
    ch_schema = args.clickhouse_database
    password = clickhouse_password
    password_option = ""
    if password is not None:
        password_option= f"--password '{password}'"
    config_file_option = ""
    if args.clickhouse_config_file is not None:
       config_file_option= f"--config-file '{args.clickhouse_config_file}'"
    schema_file = args.dump_dir + '/*-schema.sql.gz'
    for files in glob.glob(schema_file):
        (schema, table_name) = parse_schema_path(files)
        dfile = files.split("-")[0]
        print(f"{files}")
        data_files = glob.glob(dfile + ".*dat.gz")
        columns = get_column_list(
            schema_map, schema, table_name, args.virtual_columns, transform=False)
        transformed_columns = get_column_list(
            schema_map, schema, table_name, args.virtual_columns, transform=True)
        for data_file in data_files:
            # double quote escape logic https://github.com/ClickHouse/ClickHouse/issues/10624
            structure = columns.replace(
                ",", " Nullable(String),")+" Nullable(String)"
            cmd = f"""export TZ={timezone}; gunzip --stdout {data_file}  | sed -e 's/\\\\"/""/g' | sed -e "s/\\\\\\'/'/g" | clickhouse-client {config_file_option} --use_client_time_zone 1 -h {clickhouse_host} --query="INSERT INTO {ch_schema}.{table_name}({columns})  SELECT {transformed_columns} FROM input('{structure}') FORMAT CSV" -u{clickhouse_user} {password_option} -mn """
            execute_load(cmd)


def execute_load(cmd):
    logging.info(cmd)
    (rc, result) = run_quick_command(cmd)
    logging.debug(result)
    if rc != '0':
        raise AssertionError("command "+cmd + " failed")


def load_data_mysqlshell(args, timezone, schema_map, clickhouse_user=None, clickhouse_password=None, dry_run=False):

    clickhouse_host = args.clickhouse_host
    ch_schema = args.clickhouse_database

    schema_files = args.dump_dir + f"/{args.mysql_source_database}@*.sql"
    password = args.clickhouse_password
    password_option = ""
    if password is not None:
        password_option= f"--password '{password}'"
    config_file_option = ""
    if args.clickhouse_config_file is not None:
       config_file_option= f"--config-file '{args.clickhouse_config_file}'"

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.threads) as executor:
        futures = []
        for file in glob.glob(schema_files):
            if not re.search(r'@[^.]+\.sql', file):
                continue

            (schema, table_name) = parse_schema_path_mysqlshell(file)
            dfile = args.dump_dir + '/'
            # sakila@store@@0.tsv.zst
            data_files = glob.glob(
                dfile + f"{schema}@{table_name}@*.tsv.zst") + glob.glob(dfile + f"{schema}@{table_name}.tsv.zst")
            columns = get_column_list(
                schema_map, schema, table_name, args.virtual_columns, transform=False, mysqlshell=args.mysqlshell)
            transformed_columns = get_column_list(
                schema_map, schema, table_name, args.virtual_columns, transform=True, mysqlshell=args.mysqlshell)
            for data_file in data_files:
                # double quote escape logic https://github.com/ClickHouse/ClickHouse/issues/10624
                column_metadata_list = schema_map[schema+"."+table_name]
                structure = ""
                for column in column_metadata_list:
                    logging.info(str(column))
                    if column['column_name'] in args.virtual_columns:
                        continue
                    column_name = column['column_name'].replace('`', '\\`')
                    if structure != "":
                        structure += ", "
                    structure += " "+column_name + " "
                    datatype = column['datatype']
                    mysql_datetype = column['mysql_datatype']
                    if 'timestamp' in mysql_datetype.lower():
                        if column['nullable'] == True:
                            structure += f" Nullable({datatype})"
                        else:
                            structure += f" {datatype}"
                    else:
                        if column['nullable'] == True:
                            structure += " Nullable(String)"
                        else:
                            structure += " String"

                cmd = f"""export TZ={timezone}; zstd -d --stdout {data_file}  | clickhouse-client {config_file_option} --use_client_time_zone 1 --throw_if_no_data_to_insert=0  -h {clickhouse_host} --query="INSERT INTO {ch_schema}.{table_name}({columns})  SELECT {transformed_columns} FROM input('{structure}') FORMAT TSV" -u{args.clickhouse_user} {password_option} -mn """
                futures.append(executor.submit(execute_load, cmd))

        for future in concurrent.futures.as_completed(futures):
            if future.exception() is not None:
                raise future.exception()


def check_program_exists(name):
    p = Popen(['/usr/bin/which', name], stdout=PIPE, stderr=PIPE)
    p.communicate()
    return p.returncode == 0


def main():
    root = logging.getLogger()
    root.setLevel(logging.INFO)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.INFO)
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(threadName)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    parser = argparse.ArgumentParser(description='''
    Load a dump taking by mydumper or util.dumpSchemas to ClickHouse \n
    \n
    clickhouse-client should be in the PATH\n
    for --mysqlshell , zstd should be in the PATH
   ''')

    parser.add_argument('--clickhouse_host', help='CH host', required=True)
    parser.add_argument('--clickhouse_user', help='CH user', required=False)
    parser.add_argument('--clickhouse_password',
                        help='CH password (discouraged option use a configuration file)', required=False, default=None)
    parser.add_argument('--clickhouse_config_file',
                        help='CH config file either xml or yaml, default is ./clickhouse-client.xml', required=False, default='./clickhouse-client.xml')
    parser.add_argument('--clickhouse_port', type=int,
                        default=9000, help='ClickHouse port', required=False)
    parser.add_argument('--clickhouse_database',
                        help='Clickhouse database name', required=True)
    parser.add_argument('--mysql_source_database',
                        help='MySQL source schema', required=True)
    parser.add_argument(
        '--dump_dir', help='Location of dump files', required=True)
    parser.add_argument('--threads', type=int, default=8,
                        help='Number of threads', required=True)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--schema_only', dest='schema_only',
                        action='store_true', default=False)
    parser.add_argument('--data_only', dest='data_only',
                        action='store_true', default=False)
    parser.add_argument('--use_regexp_parser',
                        action='store_true', default=False)

    parser.add_argument('--dry_run', dest='dry_run',
                        action='store_true', default=False)
    parser.add_argument('--virtual_columns', help='virtual_columns',
                        nargs='+', default=['`_sign`', '`_version`', '`is_deleted`'])
    parser.add_argument('--mysqlshell', help='using a util.dumpSchemas', dest='mysqlshell',
                        action='store_true', default=False)
    parser.add_argument('--rmt_delete_support', help='Use RMT deletes', dest='rmt_delete_support',
                        action='store_true', default=False)
    parser.add_argument('--clickhouse_datetime_timezone',
                        help='Timezone for CH date times', required=False, default=None)
    args = parser.parse_args()
    schema = not args.data_only
    data = not args.schema_only
    timezone = None
    schema_map = {}
    clickhouse_user = args.clickhouse_user
    clickhouse_password = args.clickhouse_password

    # check parameters
    if args.clickhouse_password:
        logging.warning("Using password on the command line is not secure, please specify a config file ")
        assert args.clickhouse_user is not None, "--clickhouse_user must be specified"
    else:
        config_file = args.clickhouse_config_file
        (clickhouse_user, clickhouse_password) = resolve_credentials_from_config(config_file)

    # check dependencies
    assert check_program_exists(
        'clickhouse-client'), "clickhouse-client should be in the PATH"
    assert args.mysqlshell and check_program_exists(
        'zstd'), "zstd should be in the PATH for util.dumpSchemas load"

    if schema:
        (timezone, schema_map) = load_schema(args,  clickhouse_user=clickhouse_user, clickhouse_password=clickhouse_password, dry_run=args.dry_run,
                                             datetime_timezone=args.clickhouse_datetime_timezone)
    if data:
        if timezone is None:
            (timezone, schema_map) = load_schema(args, clickhouse_user=clickhouse_user, clickhouse_password=clickhouse_password, dry_run=True)

        logging.debug(str(schema_map))
        load_data(args, timezone, schema_map, clickhouse_user=clickhouse_user, clickhouse_password=clickhouse_password,dry_run=args.dry_run)


if __name__ == '__main__':
    main()
    logging.info("Finished")

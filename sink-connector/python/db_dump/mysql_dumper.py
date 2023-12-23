# -- ============================================================================
"""
# -- ============================================================================
# -- FileName     : mysql_dumper.py
# -- Date         :
# -- Summary      : dumps a MySQL database using mysqlsh
# -- Credits      : https://dev.mysql.com/doc/mysql-shell/8.0/en/mysql-shell-utilities-dump-instance-schema.html
# --                
"""
import logging
import argparse
import traceback
import sys
import datetime
import os
from db.mysql import *
from subprocess import Popen, PIPE
import subprocess
import time

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")


SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.append(os.path.dirname(SCRIPT_DIR))


def check_program_exists(name):
    p = Popen(['/usr/bin/which', name], stdout=PIPE, stderr=PIPE)
    p.communicate()
    return p.returncode == 0

# hack to add the user to the logger, which needs it apparently
old_factory = logging.getLogRecordFactory()

def record_factory(*args, **kwargs):
    record = old_factory(*args, **kwargs)
    record.user = "me"
    return record


logging.setLogRecordFactory(record_factory)

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


def generate_mysqlsh_dump_tables_clause(dump_dir,
                                        dry_run,
                                        database,
                                        tables_to_dump,
                                        data_only, 
                                        schema_only,
                                        where,
                                        partition_map,
                                        threads):
    table_array_clause = tables_to_dump
    dump_options = {"dryRun":int(dry_run), "ddlOnly":int(schema_only), "dataOnly":int(data_only), "threads":threads}
    if partition_map:
        dump_options['partitions'] = partition_map
    dump_clause=f""" util.dumpTables('{database}',{table_array_clause}, '{dump_dir}', {dump_options} ); """
    return dump_clause
 
    
def generate_mysqlsh_command(dump_dir,
                             dry_run,
                             mysql_host,
                             mysql_user,
                             mysql_password,
                             mysql_port,
                             defaults_file,
                             database,
                             tables_to_dump,
                             data_only, 
                             schema_only,
                             where,
                             partition_map,
                             threads):
    mysql_user_clause = ""
    if mysql_user is not None:
        mysql_user_clause = f" --user {mysql_user}"
    mysql_password_clause = ""
    if mysql_password is not None:
        mysql_password_clause = f""" --password "{mysql_password}" """
    mysql_port_clause = ""
    if mysql_port is not None:
        mysql_port_clause = f" --port {mysql_port}"
    defaults_file_clause = ""
    if defaults_file is not None:
        defaults_file_clause = f" --defaults-file={defaults_file}"
    
    dump_clause = generate_mysqlsh_dump_tables_clause(dump_dir,
                                                      dry_run,
                                                      database,
                                                      tables_to_dump,
                                                      data_only, 
                                                      schema_only,
                                                      where,
                                                      partition_map,
                                                      threads)
    cmd = f"""mysqlsh {defaults_file_clause} -h {mysql_host} {mysql_user_clause} {mysql_password_clause} {mysql_port_clause} -e "{dump_clause}" """
    return cmd
    
    
def main():

    parser = argparse.ArgumentParser(description='''Wrapper for mysqlsh dump''')
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
    parser.add_argument('--dump_dir', help='Location of dump files', required=True)
    parser.add_argument('--include_tables_regex', help='table regexp', required=False, default=None)
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--exclude_tables_regex',
                        help='exclude table regexp', required=False)
    parser.add_argument('--include_partitions_regex', help='partitions regex', required=False, default=None)
    parser.add_argument('--threads', type=int,
                        help='number of parallel threads', default=1)
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--schema_only', dest='schema_only',
                        action='store_true', default=False)
    parser.add_argument('--data_only', dest='data_only',
                        action='store_true', default=False)
    parser.add_argument('--non_partitioned_tables_only', dest='non_partitioned_tables_only',
                        action='store_true', default=False)
    parser.add_argument('--dry_run', dest='dry_run',
                        action='store_true', default=False)
    
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

    assert check_program_exists("mysqlsh"), "mysqlsh should in the PATH"
    
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
        tables = get_tables_from_regex(conn, False, 
                                       args.mysql_database, 
                                       args.include_tables_regex, 
                                       exclude_tables_regex=args.exclude_tables_regex, 
                                       non_partitioned_tables_only=args.non_partitioned_tables_only)
        partitions = get_partitions_from_regex(conn, 
                                               args.mysql_database, 
                                               args.include_tables_regex, 
                                               exclude_tables_regex=args.exclude_tables_regex, 
                                               include_partitions_regex=args.include_partitions_regex,
                                               non_partitioned_tables_only=args.non_partitioned_tables_only)
        
    
        tables_to_dump = []
        for table in tables.fetchall():
            logging.debug(table['table_name'])
            tables_to_dump.append(table['table_name'])
        
        partition_map = {}
        for partition in partitions.fetchall():
            schema = partition['table_schema']
            table = partition['table_name']
            partition_name = partition['partition_name']
            key = schema+"."+table
            if key not in partition_map:
                partition_map[key]=[partition_name]
            else:
                partition_map[key].append(partition_name)
        logging.debug(partition_map)
        cmd = generate_mysqlsh_command(args.dump_dir,
                                       args.dry_run,
                                       args.mysql_host,
                                       args.mysql_user,
                                       args.mysql_password,
                                       args.mysql_port,
                                       args.defaults_file,
                                       args.mysql_database,
                                       tables_to_dump,
                                       args.data_only, 
                                       args.schema_only,
                                       args.where,
                                       partition_map,
                                       args.threads
                                       )
        rc = run_command(cmd)
        assert rc == "0", "mysqldumper failed, check the log."
             
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



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
import json
import os
from ch_sink_tools.db.mysql import (
    get_mysql_connection,
    get_tables_from_regex,
    get_partitions_from_regex,
    resolve_credentials_from_config,
)
from subprocess import Popen, PIPE
import subprocess
import time
import tempfile

runTime = datetime.datetime.now().strftime("%Y.%m.%d-%H.%M.%S")




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
    # End of output does not mean the child has exited: wait() for the real status
    # (poll() here can return None and report a good dump as failed).
    rc = str(process.wait())
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


# -- ======================================================================
# -- Post-dump verification and snapshot position handoff (Spec 13.03 3.8)
# -- MySQL Shell writes the dump metadata itself (Dumper::write_dump_started_metadata
# -- in modules/util/dump/dumper.cc): '@.json' holds binlogFile, binlogPosition,
# -- gtidExecuted, gtidExecutedInconsistent, consistent, schemas, basenames, begin;
# -- '<basename>.json' holds the list of dumped 'tables'; '@.done.json' is written
# -- last and holds 'end'.
# -- ======================================================================
SNAPSHOT_POSITION_FILE = "snapshot_position.json"
SNAPSHOT_POSITION_FORMAT_VERSION = 1


class DumpVerificationError(Exception):
    """The dump finished but cannot be trusted as a snapshot."""


def _read_dump_json(dump_dir, name):
    path = os.path.join(dump_dir, name)
    if not os.path.isfile(path):
        raise DumpVerificationError(f"MySQL Shell dump metadata file {name} is missing from {dump_dir}")
    try:
        with open(path) as f:
            return json.load(f)
    except ValueError as e:
        raise DumpVerificationError(f"MySQL Shell dump metadata file {path} is not valid JSON: {e}")


def read_dumped_tables(dump_dir, database):
    """Return the table names MySQL Shell recorded for database ('tables' of '<basename>.json')."""
    metadata = _read_dump_json(dump_dir, "@.json")
    basename = (metadata.get("basenames") or {}).get(database)
    if not basename:
        raise DumpVerificationError(f"@.json in {dump_dir} has no 'basenames' entry for schema {database}")
    schema_metadata = _read_dump_json(dump_dir, basename + ".json")
    tables = schema_metadata.get("tables")
    if not isinstance(tables, list):
        raise DumpVerificationError(f"{basename}.json in {dump_dir} has no 'tables' list")
    return tables


def find_missing_tables(dump_dir, database, source_tables):
    """Tables present in the source listing but absent from the dump, sorted."""
    dumped = set(read_dumped_tables(dump_dir, database))
    return sorted(set(source_tables) - dumped)


def read_snapshot_position(dump_dir):
    """Read and validate the snapshot position MySQL Shell recorded in '@.json'.

    Raises DumpVerificationError when the dump is incomplete, was not consistent, or
    carries no usable binlog position (MySQL Shell leaves it out when the account lacks
    REPLICATION CLIENT, and it is empty when binary logging is off).
    """
    done = _read_dump_json(dump_dir, "@.done.json")
    metadata = _read_dump_json(dump_dir, "@.json")
    if metadata.get("consistent") is not True:
        raise DumpVerificationError(
            f"dump in {dump_dir} is not consistent (@.json consistent={metadata.get('consistent')!r}): "
            "its binlog position does not describe the data")
    if metadata.get("gtidExecutedInconsistent") is True:
        raise DumpVerificationError(
            f"dump in {dump_dir} reports gtidExecutedInconsistent=true: the GTID set does not describe the data")
    binlog_file = metadata.get("binlogFile")
    binlog_position = metadata.get("binlogPosition")
    if not isinstance(binlog_file, str) or not binlog_file:
        raise DumpVerificationError(
            f"@.json in {dump_dir} has no binlogFile (got {binlog_file!r}): the dump account needs "
            "REPLICATION CLIENT and the source needs binary logging enabled")
    if isinstance(binlog_position, bool) or not isinstance(binlog_position, int) or binlog_position < 0:
        raise DumpVerificationError(f"@.json in {dump_dir} has no valid binlogPosition (got {binlog_position!r})")
    gtid_executed = metadata.get("gtidExecuted")
    if not isinstance(gtid_executed, str):
        raise DumpVerificationError(f"@.json in {dump_dir} has no gtidExecuted (got {gtid_executed!r})")
    return {
        "binlog_file": binlog_file,
        "binlog_position": binlog_position,
        # MySQL Shell keeps the newlines of @@gtid_executed; the set itself has no whitespace
        "gtid_executed": "".join(gtid_executed.split()),
        "server_hostname": metadata.get("server"),
        "mysqlsh_version": metadata.get("dumper"),
        "dump_started": metadata.get("begin"),
        "dump_finished": done.get("end"),
    }


def write_snapshot_position(dump_dir, position, source_host, source_port, database, tables):
    """Write the machine-readable handoff file and return its path."""
    handoff = {
        "format_version": SNAPSHOT_POSITION_FORMAT_VERSION,
        "source_host": source_host,
        "source_port": int(source_port),
        "database": database,
        "tables": sorted(tables),
    }
    handoff.update(position)
    # keys read by `ch-mysql-resync rewind-sql --position-file` (Spec 13.08)
    handoff["file"] = position["binlog_file"]
    handoff["pos"] = position["binlog_position"]
    handoff["taken_at"] = position["dump_started"]
    path = os.path.join(dump_dir, SNAPSHOT_POSITION_FILE)
    # mode 'x': never overwrite an existing handoff file (FileExistsError instead)
    with open(path, "x") as f:
        json.dump(handoff, f, indent=2, sort_keys=True)
        f.write("\n")
    return path


def select_tables(conn, args):
    """Resolve (tables_to_dump, partition_map) for the requested scope from information_schema."""
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
    if not args.partitioned_tables_only:
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
            partition_map[key]=[partition_name] if partition_name is not None else []
        else:
            partition_map[key].append(partition_name)
        if args.partitioned_tables_only:
            if table not in tables_to_dump:
               tables_to_dump.append(table)
    logging.debug(partition_map)
    return (tables_to_dump, partition_map)


def verify_dump(args, mysql_user, mysql_password):
    """After mysqlsh succeeded: fail loudly unless the dump is complete, holds every
    in-scope source table, and (for a consistent data dump) carries a usable snapshot
    position, which is logged and written to snapshot_position.json."""
    if args.dry_run:
        logging.info("dry run: MySQL Shell wrote no dump files, post-dump verification skipped")
        return None
    _read_dump_json(args.dump_dir, "@.done.json")
    # Fresh connection and listing, taken after the snapshot: a table created after the
    # pre-dump selection is in the source but not in the dump.
    conn = get_mysql_connection(args.mysql_host, mysql_user,
                                mysql_password, args.mysql_port, args.mysql_database)
    try:
        (source_tables, _) = select_tables(conn, args)
    finally:
        conn.close()
    missing = find_missing_tables(args.dump_dir, args.mysql_database, source_tables)
    if missing:
        raise DumpVerificationError(
            f"{len(missing)} in-scope source table(s) missing from the dump in {args.dump_dir} "
            f"(created after the table list was resolved?): {missing}. Re-run the dump.")
    dumped_tables = read_dumped_tables(args.dump_dir, args.mysql_database)
    if args.schema_only:
        logging.info("schema-only dump: no snapshot position handoff written")
        return None
    position = read_snapshot_position(args.dump_dir)
    path = write_snapshot_position(args.dump_dir, position, args.mysql_host, args.mysql_port,
                                   args.mysql_database, dumped_tables)
    logging.info(f"snapshot position: binlog {position['binlog_file']}:{position['binlog_position']} "
                 f"gtid_executed '{position['gtid_executed']}' (dump started {position['dump_started']}, "
                 f"finished {position['dump_finished']}) written to {path}")
    return position


def generate_mysqlsh_dump_tables_clause(dump_dir,
                                        dry_run,
                                        database,
                                        tables_to_dump,
                                        data_only, 
                                        schema_only,
                                        where,
                                        partition_map,
                                        threads,
                                        bytes_per_chunk):
    table_array_clause = tables_to_dump
    dump_options = {"dryRun":int(dry_run), "ddlOnly":int(schema_only), "dataOnly":int(data_only), "threads":threads, "bytesPerChunk":bytes_per_chunk}
    if partition_map and not schema_only:
        dump_options['partitions'] = partition_map
    logging.info(f"{dump_options}")
    dump_clause=f""" util.dumpTables('{database}',{table_array_clause}, '{dump_dir}', {dump_options} ); """
    logging.info(dump_clause)
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
                             threads, 
                             bytes_per_chunk,
                             temp_file):
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
                                                      threads,
                                                      bytes_per_chunk)
    temp_file.write(dump_clause)
    temp_file.flush()
    cmd = f"""mysqlsh {defaults_file_clause} -h {mysql_host} {mysql_user_clause} {mysql_password_clause} {mysql_port_clause} -f {temp_file.name} """
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
    parser.add_argument('--include_tables_regex', help='table regexp', required=False, default='.')
    parser.add_argument('--where', help='where clause', required=False)
    parser.add_argument('--exclude_tables_regex',
                        help='exclude table regexp', required=False)
    parser.add_argument('--include_partitions_regex', help='partitions regex', required=False, default=None)
    parser.add_argument('--threads', type=int,
                        help='number of parallel threads', default=1)
    parser.add_argument('--bytes_per_chunk', help='bytesPerChunk mysqlsh variable', required=False, default='64M')
    parser.add_argument('--debug', dest='debug',
                        action='store_true', default=False)
    parser.add_argument('--schema_only', dest='schema_only',
                        action='store_true', default=False)
    parser.add_argument('--data_only', dest='data_only',
                        action='store_true', default=False)
    parser.add_argument('--non_partitioned_tables_only', dest='non_partitioned_tables_only',
                        action='store_true', default=False)
    parser.add_argument('--partitioned_tables_only', dest='partitioned_tables_only',
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

    # explicit checks, not assert: assertions are stripped under python -O
    if not check_program_exists("mysqlsh"):
        logging.error("mysqlsh should be in the PATH")
        sys.exit(1)

    # check parameters
    if args.mysql_password:
        logging.warning("Using password on the command line is not secure, please specify a config file ")
        if args.mysql_user is None:
            logging.error("--mysql_user must be specified")
            sys.exit(1)
    else:
        config_file = args.defaults_file
        (mysql_user, mysql_password) = resolve_credentials_from_config(config_file)

    try:
        conn = get_mysql_connection(args.mysql_host, mysql_user,
                                mysql_password, args.mysql_port, args.mysql_database)
        (tables_to_dump, partition_map) = select_tables(conn, args)
        if args.include_partitions_regex is None:
            # No partition filter requested: dump whole tables. An explicit list resolved
            # before the snapshot would silently drop partitions added in the meantime.
            partition_map = None
        # the generated json can be bigger than the shell allows, so using the -f option with
        # a temporary file
        tmp = tempfile.NamedTemporaryFile()
        with open(tmp.name, 'w') as temp_file:
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
                                       args.threads,
                                       args.bytes_per_chunk,
                                       temp_file
                                       )
          rc = run_command(cmd)
          if rc != "0":
              raise RuntimeError(f"mysqldumper failed (mysqlsh exit status {rc}), check the log.")
        verify_dump(args, mysql_user, mysql_password)
             
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



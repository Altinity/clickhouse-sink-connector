from sqlalchemy import create_engine
import logging
import warnings
import os
import configparser

binary_datatypes = ('blob', 'varbinary', 'point', 'geometry', 'bit', 'binary', 'linestring',
                    'geomcollection', 'multilinestring', 'multipolygon', 'multipoint', 'polygon')

def is_binary_datatype(datatype):
    if "blob" in datatype or "binary" in datatype or "varbinary" in datatype or "bit" in datatype:
        return True
    else:
        return datatype.lower() in binary_datatypes


def get_mysql_connection(mysql_host, mysql_user, mysql_passwd, mysql_port, mysql_database):
    url = 'mysql+pymysql://{user}:{passwd}@{host}:{port}/{db}?charset=utf8mb4'.format(
        host=mysql_host, user=mysql_user, passwd=mysql_passwd, port=int(mysql_port), db=mysql_database)
    engine = create_engine(url)
    conn = engine.connect()
    return conn


def get_tables_from_regex_sql(conn, no_wc,  mysql_database, include_tables_regex, exclude_tables_regex=None, non_partitioned_tables_only=False, include_partitions_regex=None):
    schema = mysql_database
    exclude_regex_clause = ""
    if exclude_tables_regex is not None:
         exclude_regex_clause = f"and table_name not rlike '{exclude_tables_regex}'"
    non_partitioned_tables_clause = ""
    if non_partitioned_tables_only:
        non_partitioned_tables_clause = f" and (table_schema, table_name) in (select table_schema, table_name from information_schema.partitions where table_schema = '{schema}' group by table_schema, table_name having count(*) = 1 )"   
    partitioned_tables_clause = ''
    if include_partitions_regex is not None:
        partitioned_tables_clause = f" and (table_schema, table_name) in (select table_schema, table_name from information_schema.partitions where table_schema = '{schema}' and partition_name rlike '{include_partitions_regex}' group by table_schema, table_name having count(*) > 0 )"
        
    strCommand = f"select TABLE_SCHEMA as table_schema, TABLE_NAME as table_name from information_schema.tables where table_type='BASE TABLE' and table_schema = '{schema}' and table_name rlike '{include_tables_regex}' {exclude_regex_clause} {non_partitioned_tables_clause} {partitioned_tables_clause} order by 1"
    return strCommand


def get_tables_from_regex(conn, no_wc,  mysql_database, include_tables_regex, exclude_tables_regex=None, non_partitioned_tables_only=False,include_partitions_regex=None):
    if no_wc:
        return [[include_tables_regex]]
       
    strCommand = get_tables_from_regex_sql(conn, no_wc,  mysql_database, include_tables_regex, exclude_tables_regex=exclude_tables_regex, non_partitioned_tables_only=non_partitioned_tables_only, include_partitions_regex=include_partitions_regex)
    
    (rowset, rowcount) = execute_mysql(conn, strCommand)
    x = rowset

    return x


def get_partitions_from_regex(conn, mysql_database, include_tables_regex, exclude_tables_regex=None, include_partitions_regex=None, non_partitioned_tables_only=False):
    
    table_sql =  get_tables_from_regex_sql(conn, False,  mysql_database, include_tables_regex, exclude_tables_regex=exclude_tables_regex, non_partitioned_tables_only=non_partitioned_tables_only)
    
    include_regex_clause = ""
    if include_partitions_regex is not None:
         include_regex_clause = f"and partition_name rlike '{include_partitions_regex}'"
         
    strCommand = f"select TABLE_SCHEMA as table_schema, TABLE_NAME as table_name, PARTITION_NAME as partition_name from information_schema.partitions where table_schema = '{mysql_database}' {include_regex_clause} and (table_schema, table_name) IN ({table_sql}) order by 1,2,3"
    (rowset, rowcount) = execute_mysql(conn, strCommand)
    x = rowset

    return x


def execute_mysql(conn, strSql):
    """
    # -- =======================================================================
    # -- Connect to the SQL server and execute the command
    # -- =======================================================================
    """
    logging.debug("SQL="+strSql)
    rowset = None
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        rowset = conn.execute(strSql)
        rowcount = -1
    if len(w) > 0:
        logging.warning("SQL warnings : "+str(len(w)))
        logging.warning("first warning : "+str(w[0].message))

    return (rowset, rowcount)


def resolve_credentials_from_config(config_file):
    assert config_file is not None, "A config file --default_file must be passed if --password is not specified"
    config_file = os.path.expanduser(config_file)
    assert os.path.isfile(config_file), f"Path {config_file} must exist"
    assert config_file.endswith(".cnf"), f"Supported configuration extensions .cnf"
    # ini file read
    config = configparser.ConfigParser()
    config.read(config_file)
    assert  'client' in config, f"Expected a [client] section in f{config_file}"
    mysql_user = config['client']['user']
    mysql_password = config['client']['password']
    logging.debug(f"mysql_user {mysql_user} mysql_password ****")
    return (mysql_user, mysql_password)
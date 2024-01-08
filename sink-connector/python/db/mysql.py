from sqlalchemy import create_engine
import logging
import warnings
import os
import configparser
config = configparser.ConfigParser()

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
    logging.debug(f"mysql_user {mysql_user} mysql_password {mysql_password}")
    return (mysql_user, mysql_password)
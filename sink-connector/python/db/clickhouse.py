import logging
import warnings 
from clickhouse_driver import connect
import xml.etree.ElementTree as ET
import yaml
import os


def clickhouse_connection(host, database='default', user='default',  password='', port=9000,
                          secure=False):
    conn = connect(host=host,
                   user=user,
                   password=password,
                   port=port,
                   database=database,
                   connect_timeout=20,
                   secure=secure
                   )
    return conn


def clickhouse_execute_conn(conn, sql):
    logging.debug(sql)
    cursor = conn.cursor()
    cursor.execute(sql)
    result = cursor.fetchall()
    return result


def execute_sql(conn, strSql):
    """
    # -- =======================================================================
    # -- Connect to the SQL server and execute the command
    # -- =======================================================================
    """
    logging.debug("SQL="+strSql)
    rowset = None
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        rowset = clickhouse_execute_conn(conn, strSql)
        rowcount = len(rowset)
    if len(w) > 0:
        logging.warning("SQL warnings : "+str(len(w)))
        logging.warning("first warning : "+str(w[0].message))

    return (rowset, rowcount)


def resolve_credentials_from_config(config_file):
    assert config_file is not None, "A config file --clickhouse_config_file must be passed if --password is not specified"
    assert os.path.isfile(config_file), f"Path {config_file} must exist"
    assert config_file.endswith(".xml") or config_file.endswith(".yml") or config_file.endswith(".yaml"), f"Supported configuration extensions .xml or .yaml or .yml"

    if config_file.endswith(".xml"):
        tree = ET.parse(config_file)
        root = tree.getroot()
        clickhouse_user = root.findtext('user')
        clickhouse_password = root.findtext('password')
    elif config_file.endswith(".yml") or config_file.endswith(".yaml"):
        with open(config_file, 'r') as f:
            valuesYaml = yaml.load(f, Loader=yaml.FullLoader)
            clickhouse_user = valuesYaml['config']['user']
            clickhouse_password = valuesYaml['config']['password']
    logging.debug(f"clickhouse_user {clickhouse_user} clickhouse_password {clickhouse_password}")
    return (clickhouse_user, clickhouse_password)
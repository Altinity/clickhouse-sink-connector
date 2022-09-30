from sqlalchemy import create_engine
import logging
import warnings

binary_datatypes = ('blob', 'varbinary', 'point', 'geometry', 'bit', 'binary')

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
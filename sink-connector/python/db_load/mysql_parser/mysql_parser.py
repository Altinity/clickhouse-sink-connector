import sys
from antlr4 import *
from antlr4.tree.Trees import Trees 
from db_load.mysql_parser.MySqlLexer import MySqlLexer
from db_load.mysql_parser.MySqlParser import MySqlParser
from db_load.mysql_parser.CreateTableMySQLParserListener import CreateTableMySQLParserListener
from antlr4.error.ErrorListener import ErrorListener
import logging
from io import StringIO

class MyErrorListener( ErrorListener ):

    def __init__(self):
        super(MyErrorListener, self).__init__()

    def syntaxError(self, recognizer, offendingSymbol, line, column, msg, e):
        raise Exception(f"Syntax error at line {line} column {column}")


def convert_to_clickhouse_table_antlr(source, rmt_delete_support, partition_options='', datetime_timezone=None):
    columns = []
    input_stream = InputStream(source)
    lexer = MySqlLexer(input_stream)
    stream = CommonTokenStream(lexer)
    parser = MySqlParser(stream)
    parser.addErrorListener( MyErrorListener() )
    tree = parser.sqlStatements()
    listener = CreateTableMySQLParserListener(rmt_delete_support, partition_options, datetime_timezone=datetime_timezone)
    walker = ParseTreeWalker()
    walker.walk(listener, tree) 
    logging.debug(Trees.toStringTree(tree, None, parser)) 
     
    (res, columns) = listener.get_clickhouse_sql()

    return (res, columns)

def main(argv):
    root = logging.getLogger()
    root.setLevel(logging.DEBUG)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(logging.DEBUG)
    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(threadName)s - %(levelname)s - %(message)s')
    handler.setFormatter(formatter)
    root.addHandler(handler)

    with open(argv[1], 'r') as file:
        source = file.read()
        logging.info(f"source = {source}")
        (res, columns) = convert_to_clickhouse_table_antlr(source,True)
        logging.info(f"target = {res}")

if __name__ == '__main__':
    main(sys.argv)

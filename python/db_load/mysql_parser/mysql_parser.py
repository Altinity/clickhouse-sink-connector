import sys
from antlr4 import *
from antlr4.tree.Trees import Trees 
from db_load.mysql_parser.MySQLLexer import MySQLLexer
from db_load.mysql_parser.MySQLParser import MySQLParser
from db_load.mysql_parser.CreateTableMySQLParserListener import CreateTableMySQLParserListener
from antlr4.error.ErrorListener import ErrorListener
import logging
from io import StringIO

class MyErrorListener( ErrorListener ):

    def __init__(self):
        super(MyErrorListener, self).__init__()

    def syntaxError(self, recognizer, offendingSymbol, line, column, msg, e):
        raise Exception(f"Syntax error at line {line} column {column}")


def convert_to_clickhouse_table_antlr(source):
    columns = []
    input_stream = InputStream(source)
    lexer = MySQLLexer(input_stream)
    stream = CommonTokenStream(lexer)
    parser = MySQLParser(stream)
    parser.addErrorListener( MyErrorListener() )
    tree = parser.query()
    listener = CreateTableMySQLParserListener()
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
        (res, columns)  = convert_to_clickhouse_table_antlr(source)
        print(res)

if __name__ == '__main__':
    main(sys.argv)
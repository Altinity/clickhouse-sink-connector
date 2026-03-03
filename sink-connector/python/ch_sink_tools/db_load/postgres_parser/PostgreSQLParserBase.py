# sink-connector/python/antlr_grammars/postgres/PostgreSQLParserBase.py
#
# Base class stub for the ANTLR-generated PostgreSQLParser.
# Sourced from: https://github.com/antlr/grammars-v4/tree/master/sql/postgresql/Python
#
# PostgreSQLParser.g4 declares:  superClass = PostgreSQLParserBase
#
import sys
from antlr4 import *


class PostgreSQLParserBase(Parser):
    """
    Minimal Python base class required by PostgreSQLParser.g4.

    The grammar calls OnlyAcceptableOps() as a semantic predicate inside
    operator rules; it must return True so that all operator tokens are
    accepted during CDC DDL parsing (we are not enforcing operator
    whitelist restrictions at parse time).
    """

    def __init__(self, input, output=sys.stdout):
        super().__init__(input, output)

    def OnlyAcceptableOps(self):
        """
        Semantic predicate called from the parser grammar for operator
        tokens.  Return True to accept any operator (permissive mode),
        which is appropriate for CDC DDL parsing where we do not need to
        reject non-standard operator tokens.
        """
        return True

# sink-connector/python/antlr_grammars/postgres/PostgreSQLLexerBase.py
#
# Base class stub for the ANTLR-generated PostgreSQLLexer.
# Sourced from: https://github.com/antlr/grammars-v4/tree/master/sql/postgresql/Python
#
# PostgreSQLLexer.g4 declares:  superClass = PostgreSQLLexerBase
#
import sys
from antlr4 import *


class PostgreSQLLexerBase(Lexer):
    """
    Minimal Python base class required by PostgreSQLLexer.g4.

    The grammar calls the following methods from lexer actions/predicates;
    all must exist even if the Python target does not exercise every path.
    """

    def __init__(self, input, output=sys.stdout):
        super().__init__(input, output)
        # Stack used to track dollar-quoted string tags, e.g. $body$...$body$
        self._tags = []

    # ------------------------------------------------------------------
    # Dollar-quoting helpers
    # ------------------------------------------------------------------

    def pushTag(self):
        """Push the current token text as the active dollar-quote tag."""
        self._tags.append(self.text)

    def popTag(self):
        """Pop the most-recently-pushed dollar-quote tag."""
        if self._tags:
            self._tags.pop()

    def checkPop(self):
        """Return True if there is at least one tag on the stack."""
        return len(self._tags) > 0

    def isTag(self):
        """Return True if the current token text matches the top tag."""
        return len(self._tags) > 0 and self._tags[-1] == self.text

    # ------------------------------------------------------------------
    # Configuration predicates
    # ------------------------------------------------------------------

    def isStandardConformingStrings(self):
        """
        Return True to treat backslash as a plain character inside
        single-quoted strings (standard SQL behaviour).
        Set to False to enable PostgreSQL escape-string syntax (E'...').
        """
        return True

    def isSemicolon(self):
        """Return True when the current token is a semicolon."""
        return self.text == ";"

    # ------------------------------------------------------------------
    # Error-recovery stub
    # ------------------------------------------------------------------

    def UnterminatedBlockComment(self):
        """
        Called by the lexer when an unterminated block comment is detected.
        In a full implementation this would emit a custom error token;
        here we silently ignore it so the rest of the input can still
        be tokenised.
        """
        pass

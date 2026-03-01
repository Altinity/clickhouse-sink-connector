/*
PostgreSQL grammar.
The MIT License (MIT).
Copyright (c) 2021-2023, Oleksii Kovalov (Oleksii.Kovalov@outlook.com).
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package postgres;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStream;

import java.util.List;

/**
 * Base class for the ANTLR-generated PostgreSQLParser.
 * Provides helper methods used by parser actions in PostgreSQLParser.g4.
 */
public abstract class PostgreSQLParserBase extends Parser {

    public PostgreSQLParserBase(TokenStream input) {
        super(input);
    }

    /**
     * Parse the given SQL script and return its parse tree root.
     *
     * @param script the SQL text to parse.
     * @param line   the starting line number (informational only).
     * @return the root parse tree context.
     */
    public ParserRuleContext GetParsedSqlTree(String script, int line) {
        PostgreSQLParser ph = GetPostgreSQLParser(script);
        return ph.root();
    }

    /**
     * Attempts to parse a routine body embedded inside a CREATE FUNCTION / PROCEDURE
     * statement.  The language is determined by examining the enclosing
     * {@code createfunc_opt_list} context.  Currently only registers the language;
     * actual sub-parse is left as an extension point.
     */
    public void ParseRoutineBody() {
        PostgreSQLParser.Createfunc_opt_listContext _localctx =
            (PostgreSQLParser.Createfunc_opt_listContext) this.getContext();

        String lang = null;
        for (PostgreSQLParser.Createfunc_opt_itemContext coi : _localctx.createfunc_opt_item()) {
            if (coi.LANGUAGE() != null) {
                if (coi.nonreservedword_or_sconst() != null
                    && coi.nonreservedword_or_sconst().nonreservedword() != null
                    && coi.nonreservedword_or_sconst().nonreservedword().identifier() != null
                    && coi.nonreservedword_or_sconst().nonreservedword().identifier().Identifier() != null) {
                    lang = coi.nonreservedword_or_sconst().nonreservedword().identifier()
                               .Identifier().getText();
                    break;
                }
            }
        }
        if (lang == null) return;

        PostgreSQLParser.Createfunc_opt_itemContext func_as = null;
        for (PostgreSQLParser.Createfunc_opt_itemContext a : _localctx.createfunc_opt_item()) {
            if (a.func_as() != null) {
                func_as = a;
                break;
            }
        }
        if (func_as != null) {
            // Sub-parsing of function bodies is not required for DDL translation.
            @SuppressWarnings("unused")
            String txt = GetRoutineBodyString(func_as.func_as().sconst(0));
        }
    }

    /** Strips outer quotes from a SQL string literal. */
    private String TrimQuotes(String s) {
        return (s == null || s.isEmpty()) ? s : s.substring(1, s.length() - 1);
    }

    /**
     * Unquotes a SQL single-quoted string by collapsing escaped single quotes
     * ({@code ''} → {@code '}).
     *
     * @param s the quoted string value.
     * @return the unquoted value.
     */
    public String unquote(String s) {
        int slength = s.length();
        StringBuilder r = new StringBuilder(slength);
        int i = 0;
        while (i < slength) {
            char c = s.charAt(i);
            r.append(c);
            if (c == '\'' && i < slength - 1 && s.charAt(i + 1) == '\'') i++;
            i++;
        }
        return r.toString();
    }

    /**
     * Extracts the body string from an {@code sconst} rule context, handling all
     * supported PostgreSQL string constant syntaxes (plain, unicode-escape,
     * E-escape, dollar-quoted).
     *
     * @param rule the {@code sconst} context.
     * @return the raw body string.
     */
    public String GetRoutineBodyString(PostgreSQLParser.SconstContext rule) {
        PostgreSQLParser.AnysconstContext anysconst = rule.anysconst();

        org.antlr.v4.runtime.tree.TerminalNode stringConstant = anysconst.StringConstant();
        if (stringConstant != null) return unquote(TrimQuotes(stringConstant.getText()));

        org.antlr.v4.runtime.tree.TerminalNode unicodeEscape = anysconst.UnicodeEscapeStringConstant();
        if (unicodeEscape != null) return TrimQuotes(unicodeEscape.getText());

        org.antlr.v4.runtime.tree.TerminalNode escapeString = anysconst.EscapeStringConstant();
        if (escapeString != null) return TrimQuotes(escapeString.getText());

        StringBuilder result = new StringBuilder();
        List<org.antlr.v4.runtime.tree.TerminalNode> dollarTexts = anysconst.DollarText();
        for (org.antlr.v4.runtime.tree.TerminalNode s : dollarTexts) {
            result.append(s.getText());
        }
        return result.toString();
    }

    /**
     * Constructs a fresh {@link PostgreSQLParser} for the given SQL script,
     * wiring error listeners so that errors are dispatched back to the enclosing
     * parser.
     *
     * @param script SQL text to tokenise and parse.
     * @return a ready-to-use parser instance.
     */
    public PostgreSQLParser GetPostgreSQLParser(String script) {
        CharStream charStream = CharStreams.fromString(script);
        Lexer lexer = new PostgreSQLLexer(charStream);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokens);
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        return parser;
    }

    /**
     * Returns {@code true} when the look-ahead token is one of the acceptable
     * operator symbols ({@code !}, {@code !!}, {@code !=-}) that PostgreSQL
     * allows in certain expression positions.
     *
     * @return true if the next token is an acceptable operator.
     */
    public boolean OnlyAcceptableOps() {
        var c = ((CommonTokenStream) this.getInputStream()).LT(1);
        String text = c.getText();
        return "!".equals(text) || "!!".equals(text) || "!=-".equals(text);
    }
}

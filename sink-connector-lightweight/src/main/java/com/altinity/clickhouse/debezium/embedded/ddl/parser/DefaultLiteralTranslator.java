package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import io.debezium.ddl.parser.mysql.generated.MySqlParser;

import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Rewrites a MySQL {@code DEFAULT} expression to ClickHouse literal syntax
 * (Spec 06.04 section 3.2.1): literal defaults (strings, numbers, NULL,
 * bit-strings and hexadecimal literals) plus the two implicit-default rules
 * that are themselves literal -- a MySQL ENUM NOT NULL's implicit first
 * member, and the {@code CURRENT_TIMESTAMP} test used to decide whether a
 * default needs the DDL event's own instant.
 *
 * <p>A literal may be wrapped in parentheses -- {@code DEFAULT (0)},
 * {@code DEFAULT ('x')}, {@code DEFAULT ((0))}, {@code DEFAULT (-1)}. MySQL
 * parses a parenthesised default through its {@code '(' expression ')'}
 * rule, so the wrapping is not evidence of a computed value: the value MySQL
 * back-filled the pre-existing rows with is the literal itself, and
 * {@code DEFAULT 0} reproduces it exactly. The wrapping is therefore
 * transparent here when the parentheses enclose nothing but one literal.</p>
 *
 * <p>Stateless and side-effect free; extracted from
 * {@link MySqlDDLParserListenerImpl} so it is owned and tested on its own.
 * A default that is a function or expression other than
 * {@code CURRENT_TIMESTAMP}/{@code NOW()} is not a literal and is not
 * handled here -- {@link MySqlDDLParserListenerImpl#resolveDefault} decides
 * what to do with it, since that decision depends on the DDL clause and the
 * DDL event's own timestamp.</p>
 */
final class DefaultLiteralTranslator {

    private DefaultLiteralTranslator() {
    }

    /** A ClickHouse column type that is numeric, so a bit-string/hex literal targeting it is a number, not bytes. */
    private static final Pattern NUMERIC_CH_TYPE = Pattern.compile("^(U?Int\\d+|Float\\d+|Decimal.*|Bool)$");

    /**
     * Rewrites a literal {@code DEFAULT} to ClickHouse syntax
     * (Spec 06.04 §3.2.1), or returns null when the default is not a literal.
     *
     * @param defaultValue    the parsed default.
     * @param columnType      the ClickHouse column type, which decides whether a
     *                        bit-string or hexadecimal literal is a number or bytes.
     * @param persistRawBytes the writer's {@code persist.raw.bytes}: bytes in a
     *                        String column are stored raw when true, as lowercase
     *                        hex text otherwise, and the back-fill must match.
     * @return the ClickHouse literal, or null.
     */
    static String literalDefault(MySqlParser.DefaultValueContext defaultValue, String columnType,
                                 boolean persistRawBytes) {
        if (defaultValue.getChildCount() == 1 && defaultValue.NULL_LITERAL() != null) {
            return "NULL";
        }
        MySqlParser.ConstantContext constant = defaultValue.constant();
        if (constant == null) {
            return parenthesizedLiteral(defaultValue, columnType, persistRawBytes);
        }
        MySqlParser.UnaryOperatorContext unary = defaultValue.unaryOperator();
        if (defaultValue.getChildCount() != (unary == null ? 1 : 2)) {
            return null;
        }
        Sign sign = new Sign();
        if (unary != null && !sign.apply(unary)) {
            return null; // !x, ~x, NOT x: expressions, not literals
        }
        return constantLiteral(constant, sign.text, columnType, persistRawBytes);
    }

    /**
     * Rewrites a {@code DEFAULT ( ... )} whose parentheses enclose nothing
     * but one literal (Spec 06.04 §3.2.1), or returns null when the
     * parentheses enclose any real expression -- a function call
     * ({@code (UUID())}), an arithmetic, comparison or logical expression
     * ({@code (1 + 2)}), a row value, a subquery or a variable.
     *
     * <p>The value of a parenthesised literal does not vary with time or with
     * the row, so {@code DEFAULT <literal>} is exactly what MySQL back-filled
     * the rows that pre-date the column with. Refusing it would stop a
     * replication stream over a value the replica can reproduce perfectly.</p>
     */
    private static String parenthesizedLiteral(MySqlParser.DefaultValueContext defaultValue, String columnType,
                                               boolean persistRawBytes) {
        // The CAST(...) alternative also carries brackets; it is an expression,
        // not a parenthesised literal, and stays with the caller's refusal.
        if (defaultValue.CAST() != null || defaultValue.LR_BRACKET() == null
                || defaultValue.RR_BRACKET() == null) {
            return null;
        }
        Sign sign = new Sign();
        MySqlParser.ConstantContext constant = soleConstant(defaultValue.expression(), sign);
        if (constant == null) {
            return null;
        }
        return constantLiteral(constant, sign.text, columnType, persistRawBytes);
    }

    /**
     * The one constant an expression denotes when it is only nested
     * parentheses and unary signs around a single literal, otherwise null.
     */
    private static MySqlParser.ConstantContext soleConstant(MySqlParser.ExpressionContext expression, Sign sign) {
        if (!(expression instanceof MySqlParser.PredicateExpressionContext)) {
            return null; // NOT x, a AND b, a IS NULL, ...
        }
        return soleConstant(((MySqlParser.PredicateExpressionContext) expression).predicate(), sign);
    }

    private static MySqlParser.ConstantContext soleConstant(MySqlParser.PredicateContext predicate, Sign sign) {
        if (!(predicate instanceof MySqlParser.ExpressionAtomPredicateContext)) {
            return null; // comparisons, IN, LIKE, BETWEEN, ...
        }
        return soleConstant(((MySqlParser.ExpressionAtomPredicateContext) predicate).expressionAtom(), sign);
    }

    private static MySqlParser.ConstantContext soleConstant(MySqlParser.ExpressionAtomContext atom, Sign sign) {
        if (atom instanceof MySqlParser.ConstantExpressionAtomContext) {
            return ((MySqlParser.ConstantExpressionAtomContext) atom).constant();
        }
        if (atom instanceof MySqlParser.UnaryExpressionAtomContext) {
            MySqlParser.UnaryExpressionAtomContext unary = (MySqlParser.UnaryExpressionAtomContext) atom;
            return sign.apply(unary.unaryOperator()) ? soleConstant(unary.expressionAtom(), sign) : null;
        }
        if (atom instanceof MySqlParser.NestedExpressionAtomContext) {
            MySqlParser.NestedExpressionAtomContext nested = (MySqlParser.NestedExpressionAtomContext) atom;
            return nested.expression().size() == 1 ? soleConstant(nested.expression(0), sign) : null;
        }
        return null; // function call, column reference, variable, subquery, ...
    }

    /** Rewrites one constant, with its sign resolved, to ClickHouse literal syntax; null when not representable. */
    private static String constantLiteral(MySqlParser.ConstantContext constant, String sign, String columnType,
                                          boolean persistRawBytes) {
        if (constant.nullLiteral != null) {
            return constant.NOT() == null && sign.isEmpty() ? "NULL" : null;
        }
        boolean numericTarget = columnType != null && NUMERIC_CH_TYPE.matcher(columnType).matches();
        if (constant.stringLiteral() != null) {
            return sign.isEmpty() ? clickHouseString(constant.stringLiteral()) : null;
        }
        if (constant.BIT_STRING() != null) {
            String bits = constant.BIT_STRING().getText().replaceAll("(?i)^b'|'$", "");
            BigInteger value = bits.isEmpty() ? BigInteger.ZERO : new BigInteger(bits, 2);
            if (numericTarget) {
                return sign + value;
            }
            int bytes = (bits.length() + 7) / 8;
            String hex = bytes == 0 ? "" : String.format("%0" + (2 * bytes) + "X", value);
            return bytesLiteral(hex, persistRawBytes);
        }
        if (constant.hexadecimalLiteral() != null) {
            String text = constant.hexadecimalLiteral().HEXADECIMAL_LITERAL().getText();
            String hex = text.regionMatches(true, 0, "0x", 0, 2)
                    ? text.substring(2) : text.replaceAll("(?i)^x'|'$", "");
            if (numericTarget) {
                return sign + (hex.isEmpty() ? BigInteger.ZERO : new BigInteger(hex, 16));
            }
            return bytesLiteral(hex.toUpperCase(), persistRawBytes);
        }
        // decimal, real and boolean literals are ClickHouse literals already.
        // `- -1` is not carried: "-" + "-1" would emit `--1`, a ClickHouse
        // comment, so the unrepresentable combination is refused instead.
        if (!sign.isEmpty() && constant.getText().startsWith("-")) {
            return null;
        }
        return sign + constant.getText();
    }

    /**
     * The at-most-one sign a literal can carry: {@code ""} or {@code "-"}.
     * A second unary operator ({@code -(-1)}, {@code !0}) is not carried --
     * the literal path refuses it rather than emit a token sequence that
     * ClickHouse reads as something else (a leading {@code --} is a comment).
     */
    private static final class Sign {

        private String text = "";
        private boolean seen;

        /** Applies one unary operator; false when it is not a sign or the literal already carries one. */
        boolean apply(MySqlParser.UnaryOperatorContext unaryOperator) {
            if (this.seen || (unaryOperator.MINUS() == null && unaryOperator.PLUS() == null)) {
                return false;
            }
            this.seen = true;
            this.text = unaryOperator.MINUS() != null ? "-" : "";
            return true;
        }
    }

    /**
     * The bytes a bit-string or hexadecimal literal denotes, in the
     * representation the writer stores for a BYTES value in a String column
     * (Spec 07.05): lowercase hex text, or the raw bytes under
     * {@code persist.raw.bytes=true}.
     */
    private static String bytesLiteral(String hexUpper, boolean persistRawBytes) {
        if (hexUpper.isEmpty()) {
            return "''";
        }
        return persistRawBytes ? "unhex('" + hexUpper + "')" : "'" + hexUpper.toLowerCase() + "'";
    }

    /**
     * A MySQL string literal as a ClickHouse single-quoted literal: a
     * double-quoted MySQL string (an identifier in ClickHouse) is re-quoted
     * with its {@code ""}/{@code \"} escapes unescaped and any {@code '}
     * escaped; the national prefix and a charset introducer are dropped;
     * adjacent literals are concatenated as MySQL does.
     */
    private static String clickHouseString(MySqlParser.StringLiteralContext literal) {
        StringBuilder body = new StringBuilder();
        if (literal.START_NATIONAL_STRING_LITERAL() != null) {
            String text = literal.START_NATIONAL_STRING_LITERAL().getText();
            body.append(stringBody(text.substring(text.indexOf('\'')))); // N'...'
        }
        for (org.antlr.v4.runtime.tree.TerminalNode part : literal.STRING_LITERAL()) {
            body.append(stringBody(part.getText()));
        }
        return "'" + body + "'";
    }

    /** The content of one quoted MySQL string token, escaped for a ClickHouse single-quoted literal. */
    private static String stringBody(String token) {
        if (token.length() < 2) {
            return token;
        }
        String inner = token.substring(1, token.length() - 1);
        if (token.charAt(0) == '"') {
            inner = inner.replace("\\\"", "\"").replace("\"\"", "\"");
            inner = inner.replace("\\'", "'").replace("'", "\\'");
        }
        return inner;
    }

    /** True for {@code CURRENT_TIMESTAMP[(n)]} / {@code NOW([n])} / {@code LOCALTIME[STAMP][(n)]} (with or without ON UPDATE). */
    static boolean isCurrentTimestampDefault(MySqlParser.DefaultValueContext defaultValue) {
        if (defaultValue.getChildCount() == 0 || !(defaultValue.getChild(0) instanceof MySqlParser.CurrentTimestampContext)) {
            return false;
        }
        MySqlParser.CurrentTimestampContext ts = (MySqlParser.CurrentTimestampContext) defaultValue.getChild(0);
        return ts.CURRENT_TIMESTAMP() != null || ts.NOW() != null || ts.LOCALTIME() != null
                || ts.LOCALTIMESTAMP() != null;
    }

    /** The first member of an {@code ENUM(...)} type as a ClickHouse literal, or null for any other type. */
    static String firstEnumMember(MySqlParser.DataTypeContext dataType) {
        if (!(dataType instanceof MySqlParser.CollectionDataTypeContext)) {
            return null;
        }
        MySqlParser.CollectionDataTypeContext collection = (MySqlParser.CollectionDataTypeContext) dataType;
        if (collection.ENUM() == null || collection.collectionOptions() == null
                || collection.collectionOptions().collectionOption().isEmpty()) {
            return null;
        }
        return "'" + stringBody(collection.collectionOptions().collectionOption(0).STRING_LITERAL().getText()) + "'";
    }
}

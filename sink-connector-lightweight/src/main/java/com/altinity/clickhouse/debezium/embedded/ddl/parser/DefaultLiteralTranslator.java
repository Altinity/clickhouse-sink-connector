package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import io.debezium.ddl.parser.mysql.generated.MySqlParser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Matcher;
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

    /** The declared dimensions of a ClickHouse {@code Decimal(p, s)}, or a bare {@code Decimal(p)}. */
    private static final Pattern DECIMAL_CH_TYPE = Pattern.compile("^Decimal\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)$");

    /** A ClickHouse binary floating-point type. */
    private static final Pattern FLOAT_CH_TYPE = Pattern.compile("^Float\\d+$");

    /**
     * The number a MySQL string-to-number conversion reads from the start of a
     * value for a fractional target: leading blanks, an optional sign, digits
     * with an optional single decimal point on either side, and an optional
     * exponent that only counts when a digit precedes it ({@code 'e3'}
     * converts to 0, {@code '1e3'} to 1000 -- both measured on 8.0.41).
     */
    private static final Pattern MYSQL_NUMBER_PREFIX = Pattern.compile(
            "^[ \\t]*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)");

    /**
     * The INTEGER part a MySQL string-to-integer conversion reads, which is a
     * smaller prefix than {@link #MYSQL_NUMBER_PREFIX}: it stops at the decimal
     * point and never reads an exponent, so {@code '1.9e3'} is {@code 1} and
     * {@code '.5e1'} is {@code 0} (measured on 8.0.41).
     */
    private static final Pattern MYSQL_INTEGER_PREFIX = Pattern.compile("^[ \\t]*([+-]?\\d+)");

    /** The largest finite {@code double}, the range limit of a ClickHouse {@code Float64}. */
    private static final BigDecimal DOUBLE_MAX = new BigDecimal(Double.MAX_VALUE);

    private static final BigInteger SIGNED_64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger SIGNED_64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger UNSIGNED_64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

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
            if (numericTarget) {
                // MySQL converts a string DEFAULT to the column's numeric type
                // when the DDL runs, so the value MySQL back-filled the
                // existing rows with is that number -- never the text.
                // ClickHouse accepts a quoted DEFAULT on a numeric column only
                // when the text parses cleanly ('' -> Code 32, 'abc' -> Code 6,
                // '12abc' -> Code 6, '1.5' on an integer -> Code 6, measured on
                // 24.8), so passing the text through turns a MySQL-legal DDL
                // into a statement the replica rejects and the stream dies on.
                return numericStringLiteral(mysqlStringValue(constant.stringLiteral()), sign, columnType);
            }
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
            // MySQL reads a `0x` value as a byte string and left-pads an odd
            // digit count to a whole byte: `0x1` IS the single byte 0x01, as
            // HEX(0x1) = '01' shows. The writer stores a bytes value as two hex
            // digits per byte, so the odd digit must be padded or the default
            // would describe a byte the source never held.
            if ((hex.length() & 1) == 1) {
                hex = "0" + hex;
            }
            return bytesLiteral(hex.toUpperCase(), persistRawBytes);
        }
        // `- -1` is not carried: "-" + "-1" would emit `--1`, a ClickHouse
        // comment, so the unrepresentable combination is refused instead.
        if (!sign.isEmpty() && constant.getText().startsWith("-")) {
            return null;
        }
        // decimal, real and boolean literals are ClickHouse literals already,
        // except a real literal with no integer part: MySQL's grammar writes
        // `.5` (REAL_LITERAL's `(DEC_DIGIT+)? '.' DEC_DIGIT+`) and ClickHouse
        // rejects a number that starts with a dot. The leading zero is the
        // only addition, and `.5`/`0.5` are the same value to both engines.
        String text = constant.getText();
        return sign + (text.startsWith(".") ? "0" + text : text);
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
     * The number MySQL stores when a quoted string is declared as the DEFAULT
     * of a numeric column, as a ClickHouse literal; null when the value cannot
     * be reproduced exactly.
     *
     * <p>MySQL converts the text with its own string-to-number rules, so the
     * number is what the rows that pre-date the column already hold. The rules
     * below are measured on MySQL 8.0.41 (spec §3.2.1) rather than assumed:</p>
     *
     * <ul>
     *   <li>Leading spaces and tabs are skipped; the digits end at the first
     *       character that is not part of the number, so {@code '12abc'} is
     *       {@code 12} and {@code '  7  '} is {@code 7}.</li>
     *   <li>Text with no digits is {@code 0}: {@code ''}, {@code 'abc'},
     *       {@code '.'}, {@code '-'} and {@code 'e3'} all convert to zero.</li>
     *   <li>An integer target reads only the integer part: {@code '1.5'} is
     *       {@code 1}, {@code '-1.5'} is {@code -1} and {@code '12e3'} is
     *       {@code 12} -- the conversion stops at the decimal point and never
     *       applies the exponent ({@code '1.9e3'} is {@code 1},
     *       {@code '.5e1'} is {@code 0}).</li>
     *   <li>An exponent IS applied for a {@code DOUBLE} or {@code DECIMAL}
     *       target: {@code '1e3'} is {@code 1000} on both, and even
     *       {@code '1.e3'} is {@code 1000}.</li>
     *   <li>A {@code DECIMAL(p, s)} target rounds half away from zero to
     *       {@code s} places: {@code '1.005'} is {@code 1.01} and
     *       {@code '2.675'} is {@code 2.68}.</li>
     *   <li>An unsigned target wraps: {@code CAST('-1' AS UNSIGNED)} is
     *       {@code 18446744073709551615}. That wrapped value is refused here
     *       rather than emitted: it is not the digits MySQL was given, and a
     *       narrower unsigned column would wrap it a second time.</li>
     * </ul>
     *
     * @param text       the MySQL string value, already unescaped.
     * @param sign       the {@code ""}/{@code "-"} sign written on the DEFAULT.
     * @param columnType the ClickHouse target type.
     * @return the numeric ClickHouse literal, or null when not representable.
     */
    private static String numericStringLiteral(String text, String sign, String columnType) {
        Matcher decimal = DECIMAL_CH_TYPE.matcher(columnType);
        if (decimal.matches()) {
            int scale = decimal.group(2) == null ? 0 : Integer.parseInt(decimal.group(2));
            return signedNumber(text, sign).setScale(scale, RoundingMode.HALF_UP).toPlainString();
        }
        if (FLOAT_CH_TYPE.matcher(columnType).matches()) {
            BigDecimal value = signedNumber(text, sign);
            // A magnitude a double cannot hold is out of range for the column,
            // so it is refused rather than emitted as a wrong number.
            return value.abs().compareTo(DOUBLE_MAX) <= 0 ? value.toPlainString() : null;
        }
        // Every integer type, and Bool: MySQL reads the integer part only, so
        // the conversion stops at the decimal point and an exponent is never
        // applied ('1.9e3' is 1). BigDecimal's DOWN is truncation toward zero.
        BigInteger integer = signedInteger(text, sign);
        // A value outside the 64-bit range is refused rather than emitted:
        // MySQL itself saturates it (CAST('9999999999999999999999' AS SIGNED)
        // is -1 on 8.0.41), so the digits are not the value the source stored,
        // and the caller's safe path is the loud/dropped one. A negative value
        // on an unsigned column is refused for the same reason: MySQL wraps it
        // to the type's own maximum, which is not the digits and not even the
        // same number on the two engines.
        boolean unsigned = columnType.startsWith("UInt");
        BigInteger low = unsigned ? BigInteger.ZERO : SIGNED_64_MIN;
        BigInteger high = unsigned ? UNSIGNED_64_MAX : SIGNED_64_MAX;
        if (integer.compareTo(low) < 0 || integer.compareTo(high) > 0) {
            return null;
        }
        return integer.toString();
    }

    /**
     * The number a MySQL string denotes for a {@code DOUBLE} or
     * {@code DECIMAL} target: leading spaces and tabs are skipped, an optional
     * sign, one decimal point on either side of the digits and an exponent are
     * read, and reading stops at the first character that cannot belong to the
     * number. Text with no number at all is zero, which is what MySQL stores
     * for {@code ''}, {@code 'abc'}, {@code '-'} and {@code '.'} (all measured
     * on 8.0.41).
     */
    private static BigDecimal leadingNumber(String text) {
        Matcher number = MYSQL_NUMBER_PREFIX.matcher(text);
        return number.find() ? new BigDecimal(number.group(1)) : BigDecimal.ZERO;
    }

    /**
     * The same number with the sign the DEFAULT wrote in front of the literal
     * applied, which is how MySQL combines {@code DEFAULT -'12'}.
     */
    private static BigDecimal signedNumber(String text, String sign) {
        BigDecimal value = leadingNumber(text);
        return sign.isEmpty() ? value : value.negate();
    }

    /**
     * The integer a MySQL string denotes for an integer target. MySQL reads the
     * INTEGER part of the text and stops there -- it does not read a decimal
     * point and it does not apply an exponent, so {@code '1.9e3'} is
     * {@code 1}, {@code '12e3'} is {@code 12} and {@code '.5e1'} is
     * {@code 0} (all measured on 8.0.41). A sign is part of the integer part;
     * leading spaces and tabs are skipped.
     */
    private static BigInteger signedInteger(String text, String sign) {
        Matcher number = MYSQL_INTEGER_PREFIX.matcher(text);
        BigInteger value = number.find() ? new BigInteger(number.group(1)) : BigInteger.ZERO;
        return sign.isEmpty() ? value : value.negate();
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
     * A MySQL string literal rendered for a ClickHouse String column: the
     * literal's own quoting and escapes are ClickHouse-compatible, so the
     * body is carried as written. Unlike {@link #mysqlStringValue}, this is
     * the REPRESENTATION, not the characters.
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

    /**
     * A MySQL string literal's VALUE -- the characters MySQL holds, with its
     * escapes resolved -- as opposed to {@link #clickHouseString}, which
     * renders the same literal for a ClickHouse String column.
     */
    private static String mysqlStringValue(MySqlParser.StringLiteralContext literal) {
        StringBuilder body = new StringBuilder();
        if (literal.START_NATIONAL_STRING_LITERAL() != null) {
            String text = literal.START_NATIONAL_STRING_LITERAL().getText();
            body.append(mysqlStringBody(text.substring(text.indexOf('\'')))); // N'...'
        }
        for (org.antlr.v4.runtime.tree.TerminalNode part : literal.STRING_LITERAL()) {
            body.append(mysqlStringBody(part.getText()));
        }
        return body.toString();
    }

    /** The characters one quoted MySQL string token denotes. */
    private static String mysqlStringBody(String token) {
        if (token.length() < 2) {
            return token;
        }
        char quote = token.charAt(0);
        String inner = token.substring(1, token.length() - 1);
        StringBuilder value = new StringBuilder(inner.length());
        for (int at = 0; at < inner.length(); at++) {
            char character = inner.charAt(at);
            if (character == '\\' && at + 1 < inner.length()) {
                value.append(unescape(inner.charAt(++at)));
            } else if (character == quote && at + 1 < inner.length() && inner.charAt(at + 1) == quote) {
                value.append(quote); // a doubled quote is one quote
                at++;
            } else {
                value.append(character);
            }
        }
        return value.toString();
    }

    /** MySQL's backslash escapes; an unrecognised one denotes the character itself. */
    private static char unescape(char escaped) {
        switch (escaped) {
            case '0': return '\0';
            case 'b': return '\b';
            case 'n': return '\n';
            case 'r': return '\r';
            case 't': return '\t';
            case 'Z': return 26;
            default:  return escaped; // \\ \' \" \% \_ and any other character
        }
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

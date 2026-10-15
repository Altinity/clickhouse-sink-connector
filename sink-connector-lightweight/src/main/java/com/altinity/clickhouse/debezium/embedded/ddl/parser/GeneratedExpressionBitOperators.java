package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a MySQL generation expression for a ClickHouse {@code DEFAULT},
 * translating MySQL's bit operators (Spec 06.06 section 3.4).
 *
 * <p>ClickHouse has no {@code & | ^ << >> ~} operators: a generated column
 * such as {@code (attrs & (1 << 2)) > 0} copied verbatim makes ClickHouse
 * refuse the whole CREATE TABLE / ALTER TABLE with a syntax error. Each
 * operator becomes the ClickHouse function over {@code toUInt64} operands --
 * MySQL evaluates bit operators on BIGINT UNSIGNED, and {@code toUInt64} wraps
 * a negative value the way MySQL's two's-complement conversion does:</p>
 * <ul>
 *   <li>{@code a & b} -> {@code bitAnd(toUInt64(a), toUInt64(b))}, likewise
 *       {@code |} -> {@code bitOr}, {@code ^} -> {@code bitXor},
 *       {@code ~a} -> {@code bitNot(toUInt64(a))};</li>
 *   <li>{@code a << b} -> {@code if(toUInt64(b) >= 64, bitAnd(toUInt64(a),
 *       toUInt64(0)), bitShiftLeft(toUInt64(a), toUInt64(b)))}, likewise
 *       {@code >>}: MySQL yields 0 for a count of 64 or more, ClickHouse takes
 *       the count modulo 64. The 0 is {@code bitAnd(a, 0)} so a NULL operand
 *       still yields NULL; a NULL count makes the condition NULL (false) and
 *       the shift NULL.</li>
 * </ul>
 * <p>A NULL operand yields NULL, as in MySQL (the functions propagate NULL).
 * An operation whose operands are all unsigned integer literals is folded with
 * MySQL's semantics ({@code 1 << 2} -> {@code 4}).</p>
 *
 * <p><b>Precedence.</b> The parser's grammar gives every bit operator the same
 * precedence, above arithmetic, so its tree is not MySQL's for a mixed chain
 * ({@code a | b & c}, {@code a << b + 1}). A chain of binary operators is
 * therefore flattened in source order and re-associated with MySQL's
 * precedence: {@code ^}, then {@code * / % DIV MOD}, then {@code + -}, then
 * {@code << >>}, then {@code &}, then {@code |}, each left-associative.
 * Unary operators bind tighter than all of them, as in MySQL and in the
 * grammar.</p>
 *
 * <p><b>Everything else is passed through unchanged</b>: a subtree with no bit
 * operator renders exactly as ANTLR {@code getText()} renders it today, so no
 * expression that translated before translates differently now. A chain the
 * translator does not understand (an unexpected operator mixed with bit
 * operators) is also passed through, with a WARN; ClickHouse then refuses the
 * statement loudly, as before.</p>
 */
final class GeneratedExpressionBitOperators {

    private static final Logger log = LogManager.getLogger(GeneratedExpressionBitOperators.class);

    private GeneratedExpressionBitOperators() {
    }

    /**
     * The expression text of {@code node}, with every MySQL bit operator in it
     * translated; identical to {@code node.getText()} when it has none.
     *
     * @param node a parse-tree node of a generation expression.
     * @return the ClickHouse expression text.
     */
    static String render(ParseTree node) {
        if (node instanceof TerminalNode) {
            return node.getText();
        }
        if (isBinaryAtom(node) && !isBinaryAtom(node.getParent())) {
            String translated = translateChain(node);
            if (translated != null) {
                return translated;
            }
        }
        if (isBitNot(node)) {
            String operand = render(node.getChild(1));
            Long literal = unsignedLiteral(operand);
            return literal != null ? Long.toUnsignedString(~literal) : "bitNot(toUInt64(" + operand + "))";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < node.getChildCount(); i++) {
            text.append(render(node.getChild(i)));
        }
        return text.toString();
    }

    /** A binary operator node of the expression-atom level: {@code left op right}. */
    private static boolean isBinaryAtom(ParseTree node) {
        return (node instanceof MySqlParser.BitExpressionAtomContext
                || node instanceof MySqlParser.MathExpressionAtomContext)
                && node.getChildCount() == 3;
    }

    private static boolean isBitNot(ParseTree node) {
        return node instanceof MySqlParser.UnaryExpressionAtomContext
                && node.getChildCount() == 2
                && "~".equals(node.getChild(0).getText());
    }

    /** Collects the chain's operands and operators in source order. */
    private static void flatten(ParseTree node, List<ParseTree> operands, List<String> operators) {
        if (isBinaryAtom(node)) {
            flatten(node.getChild(0), operands, operators);
            operators.add(node.getChild(1).getText());
            flatten(node.getChild(2), operands, operators);
        } else {
            operands.add(node);
        }
    }

    /**
     * Translates the binary chain rooted at {@code root}, or returns null when
     * it contains no bit operator (rendered unchanged by the caller) or an
     * operator this translation does not know (passed through, with a WARN).
     */
    private static String translateChain(ParseTree root) {
        List<ParseTree> operands = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        flatten(root, operands, operators);
        boolean hasBitOperator = false;
        for (String op : operators) {
            if (isBitOperator(op)) {
                hasBitOperator = true;
            }
        }
        if (!hasBitOperator) {
            return null;
        }
        for (String op : operators) {
            if (precedence(op) < 0) {
                log.warn("Generation expression {} mixes MySQL bit operators with operator '{}', which is "
                        + "not translated; copying it verbatim. ClickHouse will refuse it.", root.getText(), op);
                return null;
            }
        }
        List<String> rendered = new ArrayList<>();
        for (ParseTree operand : operands) {
            rendered.add(render(operand));
        }
        return climb(rendered, operators, new int[]{0}, 1);
    }

    /** Precedence climbing over operands[k..] with operators of precedence >= minPrecedence. */
    private static String climb(List<String> operands, List<String> operators, int[] k, int minPrecedence) {
        String left = operands.get(k[0]);
        while (k[0] < operators.size() && precedence(operators.get(k[0])) >= minPrecedence) {
            String op = operators.get(k[0]);
            k[0]++;
            String right = climb(operands, operators, k, precedence(op) + 1);
            left = apply(op, left, right);
        }
        return left;
    }

    private static boolean isBitOperator(String op) {
        return "&".equals(op) || "|".equals(op) || "^".equals(op) || "<<".equals(op) || ">>".equals(op);
    }

    /** MySQL operator precedence, higher binds tighter; -1 for an operator not translated. */
    private static int precedence(String op) {
        switch (op.toUpperCase()) {
            case "^":
                return 6;
            case "*":
            case "/":
            case "%":
            case "DIV":
            case "MOD":
                return 5;
            case "+":
            case "-":
                return 4;
            case "<<":
            case ">>":
                return 3;
            case "&":
                return 2;
            case "|":
                return 1;
            default:
                return -1;
        }
    }

    private static String apply(String op, String left, String right) {
        if (!isBitOperator(op)) {
            return "(" + left + " " + op + " " + right + ")";
        }
        Long l = unsignedLiteral(left);
        Long r = unsignedLiteral(right);
        if (l != null && r != null) {
            return Long.toUnsignedString(fold(op, l, r));
        }
        String a = "toUInt64(" + left + ")";
        String b = "toUInt64(" + right + ")";
        switch (op) {
            case "&":
                return "bitAnd(" + a + ", " + b + ")";
            case "|":
                return "bitOr(" + a + ", " + b + ")";
            case "^":
                return "bitXor(" + a + ", " + b + ")";
            case "<<":
                return "if(" + b + " >= 64, bitAnd(" + a + ", toUInt64(0)), bitShiftLeft(" + a + ", " + b + "))";
            default:
                return "if(" + b + " >= 64, bitAnd(" + a + ", toUInt64(0)), bitShiftRight(" + a + ", " + b + "))";
        }
    }

    /** MySQL's result for two unsigned 64-bit literals. */
    private static long fold(String op, long l, long r) {
        switch (op) {
            case "&":
                return l & r;
            case "|":
                return l | r;
            case "^":
                return l ^ r;
            case "<<":
                return Long.compareUnsigned(r, 64) >= 0 ? 0 : l << r;
            default:
                return Long.compareUnsigned(r, 64) >= 0 ? 0 : l >>> r;
        }
    }

    /**
     * The value of an unsigned decimal integer literal (optionally in
     * parentheses) that fits in 64 bits, or null for anything else.
     */
    private static Long unsignedLiteral(String text) {
        String t = text;
        while (t.length() >= 2 && t.charAt(0) == '(' && t.charAt(t.length() - 1) == ')') {
            t = t.substring(1, t.length() - 1);
        }
        if (t.isEmpty() || t.length() > 20) {
            return null;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) < '0' || t.charAt(i) > '9') {
                return null;
            }
        }
        try {
            return Long.parseUnsignedLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

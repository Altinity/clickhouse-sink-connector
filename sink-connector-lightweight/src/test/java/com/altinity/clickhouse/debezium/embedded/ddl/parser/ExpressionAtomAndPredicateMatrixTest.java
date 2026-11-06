package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exhaustive matrix over the two grammar rules that carry every expression a
 * DDL statement can hold: {@code expressionAtom} (16 labelled alternatives) and
 * {@code predicate} (10).
 *
 * <p>These are the rules behind {@code DEFAULT (...)},
 * {@code CHECK (...)}, generated columns and partition functions, so a shape the
 * grammar's own alternatives do not cover here is a statement that halts
 * replication for every table on the host when it arrives:
 * {@code ErrorListenerImpl.syntaxError} logs and throws, and the walker never
 * runs.</p>
 *
 * <h2>Why this layer exists</h2>
 *
 * <p>The earlier matrixes cover {@code dataType} (9), {@code columnConstraint}
 * (16), {@code tableOption} (35), {@code alterSpecification} (36) and
 * {@code alterPartitionSpecification} (15) -- the declaration surface. The
 * expressions inside those declarations were the measured remaining gap: a probe
 * over {@code MySqlParser.ruleNames} reports 44 labelled rules with 306
 * alternatives, and the expression rules are the largest unreached block.</p>
 *
 * <h2>What a row is, and why each row names its position</h2>
 *
 * <p>A row is one alternative plus one statement in a position where a MySQL
 * SERVER accepts that alternative. That second half is not decoration: a
 * statement the server rejects can never reach a binlog, so a row shapes the
 * grammar against input no producer can emit, and its "coverage" is fictional.
 * Two positions are therefore used:</p>
 *
 * <ul>
 *   <li>{@code CHECK (...)} inside {@code ALTER TABLE}: accepts a full boolean
 *       expression, so every predicate and most atoms are exercised there.</li>
 *   <li>{@code UPDATE ... WHERE ...} / {@code UPDATE ... SET ...}: the
 *       alternatives MySQL forbids inside a {@code CHECK} expression --
 *       user variables, variable assignment and subqueries -- are DML-only, and
 *       that is where they are legal. They still have to PARSE, because the
 *       connector sees DML-shaped DDL text in tool-generated batches.</li>
 * </ul>
 *
 * <h2>What the assertions prove</h2>
 *
 * <ol>
 *   <li><b>Every alternative has a row.</b> Reflected from the shipped parser,
 *       never from the repo's {@code .g4} copy, which is not on the runtime
 *       path.</li>
 *   <li><b>The enumerated set matches a fixed count.</b> A grammar upgrade that
 *       adds or removes an alternative shows up as a failing count, not as
 *       silent under-coverage.</li>
 *   <li><b>Each row enters the alternative it declares.</b> A row that parses
 *       through a different alternative widens apparent coverage without adding
 *       any.</li>
 *   <li><b>No row throws out of the translation path.</b> This is the production
 *       failure: one unparseable statement stops the connector for the whole
 *       host.</li>
 * </ol>
 */
public class ExpressionAtomAndPredicateMatrixTest {

    private static MySQLDDLParserService service;

    @BeforeAll
    public static void setUp() {
        service = new MySQLDDLParserService(
                new ClickHouseSinkConnectorConfig(new HashMap<>()), "db");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String sql) {
        StringBuffer output = new StringBuffer();
        service.setDdlEventTimestampMs(1700000000123L);
        service.parseSql(sql, "t", output);
        return output.toString();
    }

    /** The position a row is written in, and therefore which rule set it may reach. */
    private enum Position {
        /** Inside {@code CHECK (...)} in an {@code ALTER TABLE}. */
        CHECK,
        /** Inside {@code GENERATED ALWAYS AS (...)} in an {@code ALTER TABLE}. */
        GENERATED,
        /** Inside {@code UPDATE ... SET ...} / {@code UPDATE ... WHERE ...}. */
        DML
    }

    /** The rule a row covers. */
    private enum Surface {
        ATOM, PREDICATE, EXPRESSION
    }

    /** One matrix row: the alternative under test and one statement for it. */
    private static final class Row {
        final Surface surface;
        final String declared;
        final Position position;
        final String sql;
        /** The translator refuses this shape on purpose, loudly, with a remedy. */
        final boolean designedRefusal;
        /**
         * The alternative the SHIPPED grammar actually sends this shape to,
         * when it is not the one the rule text promises; null for an ordinary
         * row. Measured from the runtime parser's own parse tree, never
         * inferred from the repo's {@code .g4} copy, which is not on the
         * runtime path.
         */
        final String entersInstead;
        /** Top-level statements this text parses into under the shipped grammar. */
        final int statements;
        final String name;

        private Row(Surface surface, String declared, String entersInstead, int statements,
                    Position position, String sql, boolean designedRefusal) {
            this.surface = surface;
            this.declared = declared;
            this.entersInstead = entersInstead;
            this.statements = statements;
            this.position = position;
            this.sql = sql;
            this.designedRefusal = designedRefusal;
            this.name = declared + " [" + position + "] " + sql;
        }

        static Row atom(String alternative, Position position, String sql) {
            return new Row(Surface.ATOM, alternative, null, 1, position, sql, false);
        }

        static Row predicate(String alternative, Position position, String sql) {
            return new Row(Surface.PREDICATE, alternative, null, 1, position, sql, false);
        }

        static Row expression(String alternative, Position position, String sql) {
            return new Row(Surface.EXPRESSION, alternative, null, 1, position, sql, false);
        }

        /**
         * A shape that is valid MySQL and must keep parsing, but that the
         * shipped grammar routes somewhere other than the alternative the rule
         * text names.
         *
         * <p>Measured, not assumed: the row's comment carries the parse tree
         * that proves it. The row still counts as coverage of {@link #declared}
         * for the count assertion, so a grammar upgrade that ADDS a real
         * alternative is still caught; {@code eachRowEntersItsDeclaredAlternative}
         * then proves the shadow in both directions -- the statement must reach
         * {@code actual}, and must NOT reach {@code declared}. An upstream
         * grammar fix that makes the declared alternative reachable turns this
         * red, which is the signal to re-measure.</p>
         */
        Row shadowedBy(String actual, int statementCount) {
            return new Row(surface, declared, actual, statementCount, position, sql,
                    designedRefusal);
        }

        /**
         * A shape MySQL accepts but ClickHouse cannot be made to match for the
         * rows that already exist, so the translator refuses it and says why.
         * That refusal is the correct behaviour and is asserted as such; it is
         * NOT the fatal parse halt this class exists to prevent.
         */
        Row refusing() {
            return new Row(surface, declared, entersInstead, statements, position, sql, true);
        }

        @Override
        public String toString() {
            return name + (designedRefusal ? " [designed refusal]" : "");
        }
    }

    private static final String CHECK = "ALTER TABLE t ADD CONSTRAINT ck CHECK (";
    private static final String GENERATED =
            "ALTER TABLE t ADD COLUMN g int GENERATED ALWAYS AS (";

    // ------------------------------------------------------------------
    // expressionAtom rule: 16 alternatives.
    // ------------------------------------------------------------------

    /**
     * One row per {@code expressionAtom} alternative.
     *
     * <p>Alternatives MySQL forbids inside a {@code CHECK} expression -- user
     * variables, assignment and subqueries (<i>CREATE TABLE ... CHECK
     * constraints</i>, 8.0 manual) -- are written in {@code UPDATE} position,
     * where the server does accept them. Everything else is written where a DDL
     * statement actually meets it.</p>
     */
    private static List<Row> atomRows() {
        List<Row> rows = new ArrayList<>();

        // constant: a literal, in the position a literal default occupies.
        rows.add(Row.atom("constantExpressionAtom", Position.CHECK,
                "ALTER TABLE t ADD COLUMN c int DEFAULT (1)"));
        rows.add(Row.atom("constantExpressionAtom", Position.CHECK, CHECK + "TRUE)"));
        rows.add(Row.atom("constantExpressionAtom", Position.CHECK, CHECK + "NULL)"));

        // fullColumnName: a bare column, the simplest expression a CHECK can hold.
        rows.add(Row.atom("fullColumnNameExpressionAtom", Position.CHECK, CHECK + "a)"));
        rows.add(Row.atom("fullColumnNameExpressionAtom", Position.CHECK, CHECK + "t.a > 0)"));

        // functionCall: a non-deterministic-free function the server allows in CHECK.
        rows.add(Row.atom("functionCallExpressionAtom", Position.CHECK,
                CHECK + "json_valid(j))"));
        rows.add(Row.atom("functionCallExpressionAtom", Position.GENERATED,
                GENERATED + "length(c)) VIRTUAL"));

        // mysqlVariable: user variables are refused in CHECK (8.0 manual), DML-only.
        rows.add(Row.atom("mysqlVariableExpressionAtom", Position.DML,
                "UPDATE t SET a = @x"));

        // unaryOperator: unary minus on a column. `-1` is NOT this alternative
        // -- `constant` has its own `'-' decimalLiteral` production and the
        // grammar tries it first -- so the row must name a column operand.
        // `NOT` is also spelled in `unaryOperator`, but at expression level the
        // grammar tries `#notExpression` first, so a `NOT` operand never arrives
        // here; it is covered by the expression matrix below.
        rows.add(Row.atom("unaryExpressionAtom", Position.CHECK, CHECK + "-a > 0)"));
        rows.add(Row.atom("unaryExpressionAtom", Position.CHECK, CHECK + "(~a) > 0)"));
        rows.add(Row.atom("unaryExpressionAtom", Position.DML, "UPDATE t SET a = -b"));

        // BINARY: the cast operator spelling, distinct from the '&'/'|' family.
        rows.add(Row.atom("binaryExpressionAtom", Position.CHECK,
                CHECK + "BINARY c = 'x')"));

        // variableAssign: '@x := 1' is DML-only for the same reason.
        rows.add(Row.atom("variableAssignExpressionAtom", Position.DML,
                "UPDATE t SET a = (@x := 1)"));

        // nestedExpression: a parenthesised atom, which DDL producers emit freely.
        rows.add(Row.atom("nestedExpressionAtom", Position.CHECK, CHECK + "(a) > 0)"));
        // An expression DEFAULT on ADD COLUMN is accepted by MySQL (it back-fills
        // the existing rows with it) and refused by the translator on purpose:
        // ClickHouse cannot reproduce a per-row computed value for rows that
        // already exist, and creating the column without it would leave them
        // diverged, count-clean. The refusal carries the remedy, so it is
        // asserted as the designed outcome rather than a defect.
        rows.add(Row.atom("nestedExpressionAtom", Position.CHECK,
                "ALTER TABLE t ADD COLUMN c int DEFAULT ((1 + 2))").refusing());

        // row constructor: the grammar requires the literal ROW keyword --
        // bare `(a, b)` parses as nestedExpressionAtom instead.
        //
        // SHADOWED, measured on the runtime parser: `ROW` is in
        // `keywordsCanBeId`, so `ROW (a, b)` is also a legal `functionCall`
        // through `fullId`, and the grammar's adaptive prediction takes that
        // branch first. The tree for this statement is
        //   (expressionAtom (functionCall (fullId (uid (simpleId
        //       (keywordsCanBeId ROW)))) '(' functionArgs ...) ')')
        // -- a udf call, never (expressionAtom ROW '(' expression ',' ... ')').
        // Confirmed for this spelling, for `(ROW(a, b)) = (ROW(1, 2))`, for
        // `INSERT ... VALUES (ROW(1, 2))` and for the same expression inside a
        // CHECK. `nestedRowExpressionAtom` is therefore unreachable in the
        // shipped grammar; the statement still parses (which is what matters
        // for replication) and still reaches the row constructor semantically.
        rows.add(Row.atom("nestedRowExpressionAtom", Position.DML,
                "UPDATE t SET a = 1 WHERE ROW(a, b) = ROW(1, 2)")
                .shadowedBy("functionCallExpressionAtom", 1));

        // EXISTS: a subquery, so DML-only.
        rows.add(Row.atom("existsExpressionAtom", Position.DML,
                "UPDATE t SET a = 1 WHERE EXISTS (SELECT 1 FROM u)"));

        // scalar subquery: DML-only.
        rows.add(Row.atom("subqueryExpressionAtom", Position.DML,
                "UPDATE t SET a = (SELECT max(x) FROM u)"));

        // INTERVAL: legal in a CHECK expression. NOW() is not -- it is
        // non-deterministic, and a CHECK that calls it is rejected by the
        // server -- so the row uses a date literal, which is deterministic.
        rows.add(Row.atom("intervalExpressionAtom", Position.CHECK,
                CHECK + "d + INTERVAL 1 DAY > '2020-01-01')"));

        // bit operators: '&' and the '<<' / '>>' / '^' / '|' family. `~` is a
        // UNARY operator and reaches unaryExpressionAtom, not this alternative --
        // it is covered there.
        rows.add(Row.atom("bitExpressionAtom", Position.CHECK, CHECK + "(a & 1) > 0)"));
        rows.add(Row.atom("bitExpressionAtom", Position.CHECK, CHECK + "(a << 1) > 0)"));
        rows.add(Row.atom("bitExpressionAtom", Position.CHECK, CHECK + "(a >> 1) > 0)"));
        rows.add(Row.atom("bitExpressionAtom", Position.CHECK, CHECK + "(a ^ 1) > 0)"));
        rows.add(Row.atom("bitExpressionAtom", Position.CHECK, CHECK + "(a | 1) > 0)"));

        // arithmetic.
        rows.add(Row.atom("mathExpressionAtom", Position.CHECK, CHECK + "a + 1 > 0)"));
        rows.add(Row.atom("mathExpressionAtom", Position.GENERATED,
                GENERATED + "a * 2) VIRTUAL"));

        // JSON extraction operator.
        rows.add(Row.atom("jsonExpressionAtom", Position.CHECK,
                CHECK + "(j ->> '$.x') = '1')"));
        rows.add(Row.atom("jsonExpressionAtom", Position.CHECK,
                CHECK + "(j -> '$.x') = '1')"));

        // COLLATE inside an expression.
        rows.add(Row.atom("collateExpressionAtom", Position.CHECK,
                CHECK + "c COLLATE utf8mb4_bin = 'x')"));

        return rows;
    }

    // ------------------------------------------------------------------
    // predicate rule: 10 alternatives.
    // ------------------------------------------------------------------

    /**
     * One row per {@code predicate} alternative. All ten are legal inside a
     * {@code CHECK} expression except the subquery comparison, which the server
     * refuses there and which is written in {@code WHERE} position instead.
     */
    private static List<Row> predicateRows() {
        List<Row> rows = new ArrayList<>();

        // the bare-atom predicate: an expression with no operator at all.
        rows.add(Row.predicate("expressionAtomPredicate", Position.CHECK, CHECK + "a)"));

        // every comparison operator the manual lists, not just one.
        for (String op : new String[]{"=", "<>", "!=", "<", ">", "<=", ">=", "<=>"}) {
            rows.add(Row.predicate("binaryComparisonPredicate", Position.CHECK,
                    CHECK + "a " + op + " 1)"));
        }

        rows.add(Row.predicate("betweenPredicate", Position.CHECK,
                CHECK + "a BETWEEN 1 AND 10)"));
        rows.add(Row.predicate("betweenPredicate", Position.CHECK,
                CHECK + "a NOT BETWEEN 1 AND 10)"));

        rows.add(Row.predicate("soundsLikePredicate", Position.CHECK,
                CHECK + "c SOUNDS LIKE 'x')"));

        rows.add(Row.predicate("regexpPredicate", Position.CHECK,
                CHECK + "c REGEXP '^x')"));
        rows.add(Row.predicate("regexpPredicate", Position.CHECK,
                CHECK + "c NOT REGEXP '^x')"));
        rows.add(Row.predicate("regexpPredicate", Position.CHECK,
                CHECK + "c RLIKE '^x')"));

        rows.add(Row.predicate("inPredicate", Position.CHECK,
                CHECK + "a IN (1, 2, 3))"));
        rows.add(Row.predicate("inPredicate", Position.CHECK,
                CHECK + "a NOT IN (1, 2, 3))"));
        rows.add(Row.predicate("inPredicate", Position.DML,
                "UPDATE t SET a = 1 WHERE a IN (SELECT x FROM u)"));

        // IS NULL / IS NOT NULL. `IS TRUE` and `IS NOT FALSE` are NOT this
        // alternative: the grammar routes a boolean test to #isExpression in the
        // `expression` rule, which wraps a predicate rather than being one.
        rows.add(Row.predicate("isNullPredicate", Position.CHECK,
                CHECK + "b IS NULL)"));
        rows.add(Row.predicate("isNullPredicate", Position.CHECK,
                CHECK + "b IS NOT NULL)"));

        // A comparison whose right side is a subquery. The grammar requires a
        // quantifier (ALL/ANY/SOME) here; a bare `a > (SELECT ...)` is a
        // #binaryComparisonPredicate whose right operand is a
        // #subqueryExpressionAtom, which is why the first row does not name this
        // alternative. DML-only in MySQL either way.
        rows.add(Row.predicate("subqueryComparisonPredicate", Position.DML,
                "UPDATE t SET a = 1 WHERE a > ALL (SELECT x FROM u)"));
        // ANY and SOME are SHADOWED, measured on the runtime parser. Both are in
        // `keywordsCanBeId`, so the comparison absorbs the word as an identifier
        // and the parenthesised SELECT becomes a SECOND top-level statement:
        //   (binaryComparisonPredicate (expressionAtom (fullColumnName
        //       (uid (simpleId (keywordsCanBeId ANY))))) '=' ... )
        // followed by an unrelated statement starting at `SELECT`. `ALL` is not
        // in that keyword set, which is exactly why the row above reaches the
        // quantifier and these two do not. Measured statement counts under the
        // shipped grammar: `= ANY (...)` 2, `< SOME (...)` 2, `> ALL (...)` 1,
        // `IN (...)` 1. The split is confined to text the connector only has to
        // survive: a translated DDL statement that carries one of these shapes
        // (a VIEW body, a CTAS) emits nothing on this path either way -- checked
        // on the view family, where `= ANY` splits to 2 statements and the
        // translator emits the same empty output as the single-statement
        // `> ALL` and `IN` spellings.
        rows.add(Row.predicate("subqueryComparisonPredicate", Position.DML,
                "UPDATE t SET a = 1 WHERE a = ANY (SELECT x FROM u)")
                .shadowedBy("binaryComparisonPredicate", 2));
        rows.add(Row.predicate("subqueryComparisonPredicate", Position.DML,
                "UPDATE t SET a = 1 WHERE a < SOME (SELECT x FROM u)")
                .shadowedBy("binaryComparisonPredicate", 2));

        rows.add(Row.predicate("likePredicate", Position.CHECK,
                CHECK + "c LIKE 'x%')"));
        rows.add(Row.predicate("likePredicate", Position.CHECK,
                CHECK + "c NOT LIKE 'x%')"));
        rows.add(Row.predicate("likePredicate", Position.CHECK,
                CHECK + "c LIKE 'x!_%' ESCAPE '!')"));

        // MEMBER OF is an operator, so the CHECK page's general "operators are
        // permitted" covers it, but that page does not name it. It is written in
        // DML position instead, where the operator is unambiguously valid, so the
        // row never rests on an inference from the manual.
        rows.add(Row.predicate("jsonMemberOfPredicate", Position.DML,
                "UPDATE t SET a = 1 WHERE 1 MEMBER OF (j)"));

        return rows;
    }

    // ------------------------------------------------------------------
    // expression rule: 4 alternatives.
    // ------------------------------------------------------------------

    /**
     * One row per {@code expression} alternative.
     *
     * <p>This is the rule ABOVE {@code predicate}, and it is where the boolean
     * layer lives: {@code IS TRUE} / {@code IS NOT FALSE} / {@code IS UNKNOWN}
     * are {@code #isExpression} HERE, not a predicate, and {@code NOT} /
     * {@code AND} / {@code OR} / {@code XOR} are the other two. A statement
     * that uses one of them still reaches every predicate underneath, so
     * covering only {@code predicate} would leave the operators that wrap it
     * untested.</p>
     */
    private static List<Row> expressionRows() {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.expression("predicateExpression", Position.CHECK, CHECK + "a > 0)"));

        // the boolean test: IS [NOT] TRUE / FALSE / UNKNOWN. MySQL rejects
        // `IS UNKNOWN` as a spelling of IS NULL on a non-boolean operand, so
        // the EXPLICIT boolean forms are used here.
        rows.add(Row.expression("isExpression", Position.CHECK, CHECK + "a IS TRUE)"));
        rows.add(Row.expression("isExpression", Position.CHECK, CHECK + "a IS NOT FALSE)"));
        rows.add(Row.expression("isExpression", Position.CHECK, CHECK + "a IS UNKNOWN)"));

        // NOT: the unary boolean negation, which applies to a whole predicate.
        rows.add(Row.expression("notExpression", Position.CHECK, CHECK + "NOT (a > 0))"));
        rows.add(Row.expression("notExpression", Position.DML,
                "UPDATE t SET a = 1 WHERE !(a > 0)"));

        // the binary boolean connectives.
        rows.add(Row.expression("logicalExpression", Position.CHECK,
                CHECK + "a > 0 AND b > 0)"));
        rows.add(Row.expression("logicalExpression", Position.CHECK,
                CHECK + "a > 0 OR b > 0)"));
        rows.add(Row.expression("logicalExpression", Position.CHECK,
                CHECK + "a > 0 XOR b > 0)"));
        rows.add(Row.expression("logicalExpression", Position.DML,
                "UPDATE t SET a = 1 WHERE a > 0 && b > 0"));
        rows.add(Row.expression("logicalExpression", Position.DML,
                "UPDATE t SET a = 1 WHERE a > 0 || b > 0"));

        return rows;
    }

    // ------------------------------------------------------------------
    // reflection over the shipped parser.
    // ------------------------------------------------------------------

    private static Set<String> expressionAtomAlternatives() {
        return labelledAlternatives(MySqlParser.ExpressionAtomContext.class);
    }

    private static Set<String> predicateAlternatives() {
        return labelledAlternatives(MySqlParser.PredicateContext.class);
    }

    private static Set<String> expressionAlternatives() {
        return labelledAlternatives(MySqlParser.ExpressionContext.class);
    }

    /**
     * The {@code #label} spelling of every labelled alternative of {@code rule}.
     *
     * <p>Membership is decided by SUPERCLASS: a labelled alternative extends the
     * rule's own context class, while the base context extends
     * {@code ParserRuleContext}. Matching on the class name instead would pick up
     * same-suffixed classes from unrelated rules.</p>
     */
    private static Set<String> labelledAlternatives(Class<?> rule) {
        Set<String> names = new TreeSet<>();
        for (Class<?> nested : MySqlParser.class.getDeclaredClasses()) {
            if (nested.getSuperclass() != rule) {
                continue;
            }
            names.add(labelOf(nested));
        }
        return names;
    }

    /**
     * The {@code #label} of a context class: its simple name without the
     * {@code Context} suffix, first character lower-cased.
     */
    private static String labelOf(Class<?> contextClass) {
        String simple = contextClass.getSimpleName();
        String label = simple.substring(0, simple.length() - "Context".length());
        return Character.toLowerCase(label.charAt(0)) + label.substring(1);
    }

    /**
     * Walks the parse tree of one statement and returns every
     * {@code expression}, {@code expressionAtom} and {@code predicate}
     * alternative it entered.
     *
     * <p>The parser is built with a listener that THROWS on a syntax error. The
     * default ANTLR listener only prints and recovers, which turns an
     * unparseable row into a partial tree: the alternative it declares is then
     * simply absent, and the row reads as "entered a different alternative"
     * rather than "did not parse". That misdiagnosis is what this listener
     * prevents -- a structural probe must fail on its own failure mode.</p>
     */
    private static Set<String> alternativesEntered(String sql) {
        Set<String> entered = new TreeSet<>();
        MySqlParser parser = strictParser(sql);
        MySqlParser.RootContext root = parser.root();
        org.antlr.v4.runtime.tree.ParseTreeWalker.DEFAULT.walk(
                new org.antlr.v4.runtime.tree.ParseTreeListener() {
                    @Override
                    public void enterEveryRule(org.antlr.v4.runtime.ParserRuleContext ctx) {
                        if (isOneOfTheThreeSurfaces(ctx.getClass())) {
                            entered.add(labelOf(ctx.getClass()));
                        }
                    }

                    @Override
                    public void exitEveryRule(org.antlr.v4.runtime.ParserRuleContext ctx) {
                    }

                    @Override
                    public void visitTerminal(org.antlr.v4.runtime.tree.TerminalNode node) {
                    }

                    @Override
                    public void visitErrorNode(org.antlr.v4.runtime.tree.ErrorNode node) {
                    }
                }, root);
        return entered;
    }

    private static boolean isOneOfTheThreeSurfaces(Class<?> contextClass) {
        Class<?> superclass = contextClass.getSuperclass();
        return superclass == MySqlParser.ExpressionContext.class
                || superclass == MySqlParser.ExpressionAtomContext.class
                || superclass == MySqlParser.PredicateContext.class;
    }

    /**
     * The parser the runtime uses, with a listener that THROWS on a syntax
     * error instead of recovering.
     */
    private static MySqlParser strictParser(String sql) {
        MySqlParser parser = new MySqlParser(
                new org.antlr.v4.runtime.CommonTokenStream(
                        new io.debezium.ddl.parser.mysql.generated.MySqlLexer(
                                new io.debezium.antlr.CaseChangingCharStream(
                                        org.antlr.v4.runtime.CharStreams.fromString(sql), true))));
        parser.removeErrorListeners();
        parser.addErrorListener(new org.antlr.v4.runtime.BaseErrorListener() {
            @Override
            public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer,
                                    Object offendingSymbol, int line, int charPositionInLine,
                                    String msg, org.antlr.v4.runtime.RecognitionException e) {
                throw new IllegalStateException("line " + line + ":" + charPositionInLine
                        + " " + msg);
            }
        });
        return parser;
    }

    /**
     * How many {@code sqlStatement}s the text parses into.
     *
     * <p>A statement that silently becomes TWO is not the statement the source
     * wrote, so the count is asserted for every row: one, except the rows that
     * are measured to split.</p>
     */
    private static int statementCount(String sql) {
        MySqlParser.RootContext root = strictParser(sql).root();
        int n = 0;
        for (int i = 0; i < root.getChildCount(); i++) {
            org.antlr.v4.runtime.tree.ParseTree child = root.getChild(i);
            if (child instanceof MySqlParser.SqlStatementsContext) {
                for (int j = 0; j < child.getChildCount(); j++) {
                    if (child.getChild(j) instanceof MySqlParser.SqlStatementContext) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Properties.
    // ------------------------------------------------------------------

    /**
     * Every {@code expressionAtom} alternative the shipped parser defines has a
     * matrix row, and the matrix names no alternative the parser does not define.
     */
    @Test
    public void everyExpressionAtomAlternativeHasAMatrixRow() {
        Set<String> inGrammar = expressionAtomAlternatives();
        assertEquals(16, inGrammar.size(),
                "the shipped parser's expressionAtom rule has 16 labelled alternatives; the set is "
                        + inGrammar);
        assertAlternativesCovered(inGrammar, atomRows());
    }

    /**
     * Every {@code predicate} alternative the shipped parser defines has a matrix
     * row, and the matrix names no alternative the parser does not define.
     */
    @Test
    public void everyPredicateAlternativeHasAMatrixRow() {
        Set<String> inGrammar = predicateAlternatives();
        assertEquals(10, inGrammar.size(),
                "the shipped parser's predicate rule has 10 labelled alternatives; the set is "
                        + inGrammar);
        assertAlternativesCovered(inGrammar, predicateRows());
    }

    /**
     * Every {@code expression} alternative the shipped parser defines has a
     * matrix row. This is the rule above {@code predicate}: without it the
     * operators that WRAP a predicate -- {@code NOT}, {@code AND}, {@code OR},
     * {@code XOR} and the {@code IS TRUE} test -- have no row at all.
     */
    @Test
    public void everyExpressionAlternativeHasAMatrixRow() {
        Set<String> inGrammar = expressionAlternatives();
        assertEquals(4, inGrammar.size(),
                "the shipped parser's expression rule has 4 labelled alternatives; the set is "
                        + inGrammar);
        assertAlternativesCovered(inGrammar, expressionRows());
    }

    private static void assertAlternativesCovered(Set<String> inGrammar, List<Row> rows) {
        Set<String> covered = new TreeSet<>();
        for (Row row : rows) {
            covered.add(row.declared);
        }
        Set<String> missing = new TreeSet<>(inGrammar);
        missing.removeAll(covered);
        Set<String> extra = new TreeSet<>(covered);
        extra.removeAll(inGrammar);
        assertTrue(missing.isEmpty(), "alternatives with no matrix row: " + missing);
        assertTrue(extra.isEmpty(), "matrix rows naming no grammar alternative: " + extra);
    }

    /**
     * Each row must enter the alternative it claims. A row that parses through a
     * different alternative reports coverage it does not have, and the mismatch is
     * collected for every row rather than thrown at the first one, so one run
     * reports the whole set.
     */
    @Test
    public void eachRowEntersItsDeclaredAlternative() {
        List<String> failures = new ArrayList<>();
        for (Row row : allRows()) {
            checkEntered(row, failures);
        }
        assertTrue(failures.isEmpty(), failures.size() + " row(s) do not enter their declared "
                + "alternative:\n  " + String.join("\n  ", failures));
    }

    /**
     * One row against the shipped grammar, in both directions.
     *
     * <p>An ordinary row must enter the alternative it declares. A row that is
     * declared shadowed must reach {@code entersInstead} and must NOT reach the
     * alternative the rule text names -- so an upstream grammar change that
     * makes the declared alternative reachable fails here instead of quietly
     * leaving a statement in the "rows" list that measures nothing.</p>
     */
    private static void checkEntered(Row row, List<String> failures) {
        Set<String> entered;
        int statements;
        try {
            entered = alternativesEntered(row.sql);
            statements = statementCount(row.sql);
        } catch (RuntimeException e) {
            failures.add(row + " -> DID NOT PARSE: " + firstLine(e.getMessage()));
            return;
        }
        if (statements != row.statements) {
            failures.add(row + " -> parses into " + statements + " statement(s), not "
                    + row.statements);
        }
        if (row.entersInstead == null) {
            if (!entered.contains(row.declared)) {
                failures.add(row + " -> declared " + row.declared + ", entered " + entered);
            }
            return;
        }
        if (!entered.contains(row.entersInstead)) {
            failures.add(row + " -> declared shadowed by " + row.entersInstead
                    + " but did not enter it; entered " + entered);
        }
        if (entered.contains(row.declared)) {
            failures.add(row + " -> is declared SHADOWED by " + row.entersInstead
                    + " but the shipped grammar now reaches " + row.declared
                    + "; re-measure and drop the shadow declaration");
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "(no message)";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /**
     * No row throws out of the translation path, and the shapes that must be
     * refused are refused with a remedy.
     *
     * <p>Two outcomes are legitimate and they are not interchangeable:</p>
     * <ul>
     *   <li>the shape translates, or is parsed and dropped because ClickHouse has
     *       no equivalent -- no exception;</li>
     *   <li>the shape cannot be reproduced faithfully, so the translator raises
     *       {@code DDLReplicationException} carrying the remedy. That is the
     *       loud, recoverable outcome, and it is asserted for the rows marked as
     *       designed refusals.</li>
     * </ul>
     *
     * <p>What must NEVER happen is a raw parse failure escaping
     * {@code parseSql}: the parser's error listener throws
     * {@code RuntimeException("Error parsing DDL")}, and the connector then stops
     * replicating every table on the host.</p>
     */
    @Test
    public void noRowThrowsOutOfTheTranslationPath() {
        Map<String, String> fatal = new LinkedHashMap<>();
        List<String> missingRefusal = new ArrayList<>();
        List<String> wrongRefusal = new ArrayList<>();
        for (Row row : allRows()) {
            try {
                translate(row.sql);
                if (row.designedRefusal) {
                    missingRefusal.add(row.toString());
                }
            } catch (com.altinity.clickhouse.debezium.embedded.cdc.DDLReplicationException e) {
                if (!row.designedRefusal) {
                    wrongRefusal.add(row + " -> refused: " + firstLine(e.getMessage()));
                }
            } catch (RuntimeException e) {
                fatal.put(row.toString(), e.getClass().getSimpleName() + ": "
                        + firstLine(e.getMessage()));
            }
        }
        assertTrue(fatal.isEmpty(),
                fatal.size() + " statement(s) fail in the PARSER (each one halts "
                        + "replication for every table on the host): " + fatal);
        assertTrue(wrongRefusal.isEmpty(),
                wrongRefusal.size() + " statement(s) MySQL accepts are refused by the "
                        + "translator: " + wrongRefusal);
        assertTrue(missingRefusal.isEmpty(),
                missingRefusal.size() + " row(s) are declared a designed refusal but were "
                        + "translated without raising DDLReplicationException: " + missingRefusal);
    }

    /** All rows, atom first, then predicate, then expression. */
    private static List<Row> allRows() {
        List<Row> all = new ArrayList<>(atomRows());
        all.addAll(predicateRows());
        all.addAll(expressionRows());
        return all;
    }

    /**
     * The expression surface this matrix covers is also carried by the
     * real-world corpus, so the corpus-driven end-to-end tests keep exercising it
     * after this file changes. Coverage that lives in one test file only
     * disappears the moment that file is edited.
     */
    @Test
    public void theRealWorldCorpusCarriesTheSameExpressionSurface() {
        String corpus;
        try (java.io.InputStream in =
                     ExpressionAtomAndPredicateMatrixTest.class.getResourceAsStream(
                             "/ddl-realworld-corpus.txt")) {
            assertTrue(in != null, "the real-world corpus resource is missing");
            corpus = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .toUpperCase(java.util.Locale.ROOT);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read the real-world corpus", e);
        }
        Map<String, String> expectations = new LinkedHashMap<>();
        expectations.put("CHECK (", "a CHECK constraint");
        expectations.put("GENERATED ALWAYS AS (", "a generated column expression");
        expectations.put("DEFAULT (", "an expression DEFAULT");
        for (Map.Entry<String, String> expected : expectations.entrySet()) {
            assertTrue(corpus.contains(expected.getKey()),
                    "the real-world corpus no longer carries " + expected.getValue()
                            + " (" + expected.getKey() + "), so the end-to-end corpus test "
                            + "stopped exercising that surface");
        }
    }
}

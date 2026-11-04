package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the few MySQL spellings the bundled DDL grammar cannot parse into
 * an equivalent spelling it can, so that the grammar stops being the ceiling
 * on which source DDL replicates.
 *
 * <p>The MySQL grammar this connector ships
 * ({@code io.debezium.ddl.parser.mysql.generated}, from
 * {@code debezium-ddl-parser}) is a fixed compiled artifact: there is no
 * grammar file on the runtime path to extend (the {@code src/main/antlr4}
 * copy carries no {@code @header} package directive, so it generates into
 * package {@code mysql} and is never the parser the translator uses). The
 * accepted MySQL constructs below are therefore unreachable through it, and
 * every one of them aborts the whole statement:</p>
 *
 * <ul>
 *   <li><b>{@code PERSISTENT}</b> -- MySQL's documented synonym for
 *       {@code STORED} on a generated column. The shipped lexer has no
 *       {@code PERSISTENT} terminal at all, so
 *       {@code b INT AS (a+1) PERSISTENT} is a syntax error.
 *       {@code STORED} is a terminal of the grammar and declares exactly the
 *       same thing, so the rewrite is loss-free.</li>
 *   <li><b>{@code 0b<binary>}</b> -- MySQL 8's bit-value literal spelling
 *       ({@code COLUMN FLAGS INT DEFAULT 0b101}). Its lexer is
 *       {@code '0x' HEX_DIGIT+} for hex and {@code 'B' '\'' [01]+ '\''} for
 *       the quoted form, with no lowercase {@code 0b} rule at all, so the
 *       token splits into {@code 0} and an identifier and the statement is a
 *       syntax error. The quoted form {@code b'101'} is a terminal of the
 *       grammar and denotes the same bit value, so the rewrite is
 *       loss-free.</li>
 *   <li><b>{@code SRID <n>}</b> -- the shipped grammar accepts it only
 *       immediately after the spatial type ({@code spatialDataType} is
 *       {@code typeName SRID? decimalLiteral?}). MySQL accepts it anywhere in
 *       the column attribute list, so {@code g GEOMETRY NOT NULL SRID 4326} is
 *       valid MySQL and a syntax error here. Every spatial type maps to
 *       {@code String}/{@code Nullable(String)} in ClickHouse, so the SRID
 *       value never reaches the emitted DDL and relocating it is loss-free for
 *       the replicated schema.</li>
 *   <li><b>{@code NODEGROUP <n>}</b> -- the MySQL NDB cluster-group clause of
 *       a partition definition ({@code PARTITION p0 VALUES LESS THAN (10)
 *       NODEGROUP = 2}). The grammar's alternative is {@code NODEGROUP '='?
 *       nodegroup=uid}, and {@code uid} resolves only to an identifier
 *       ({@code simpleId}, a backticked identifier or a charset-quoted one),
 *       never to a number -- so every real clause, whose value is always an
 *       integer, is a syntax error at the value token. Quoting the number makes
 *       the same clause match {@code REVERSE_QUOTE_ID}. Partition options are
 *       dropped by the translator ({@code isNoOpSpecification}), so the value
 *       never reaches the emitted DDL and the rewrite is loss-free for the
 *       replicated schema.</li>
 * </ul>
 *
 * <p><b>Two further classes of gap are closed here, and neither is a synonym
 * rewrite.</b> The shipped grammar is older than the MySQL 8.0 surface, so
 * whole statement families it has never been taught reach the parser and abort
 * it. {@link #blankUntranslatableStatement} handles the families the
 * translator has no listener handler for at all: since no handler exists, no
 * ClickHouse DDL can ever be derived from such a statement, so parsing it is
 * not required at all -- only not being fatal is. {@link
 * #dropUnsupportedTableClauses} handles the opposite case, a clause the
 * grammar lacks sitting inside a statement that DOES change a replicated
 * table's schema: there the clause alone is removed, because dropping the
 * statement would silently lose the column beside it.</p>
 *
 * <p>Every rewrite is textual and touches only the tokens named above. Text
 * inside a string literal, a quoted identifier or a comment is never
 * rewritten, so {@code COMMENT 'see SRID 4326'} keeps its text. A statement
 * that needs no rewrite is returned by identity.</p>
 *
 * <p>This is a bridge for grammar gaps, not a general SQL-rewriting layer.
 * A construct that needs anything more than a token-level synonym or
 * relocation belongs in the translator, where the parse tree is available.</p>
 */
final class MySqlDdlNormalizer {

    /**
     * The spatial type terminals of the shipped grammar's
     * {@code SpatialDataTypeContext}, as one alternation. Longest first so that
     * {@code GEOMETRYCOLLECTION} is not matched as {@code GEOMETRY}.
     */
    private static final Pattern SPATIAL_TYPE_TOKEN = Pattern.compile(
            "(?<![\\w.])(GEOMETRYCOLLECTION|MULTILINESTRING|GEOMCOLLECTION|MULTIPOLYGON"
                    + "|MULTIPOINT|LINESTRING|GEOMETRY|POLYGON|POINT)(?![\\w])",
            Pattern.CASE_INSENSITIVE);

    /**
     * {@code PERSISTENT} as a whole word, not preceded by a dot or an
     * identifier character, so that {@code STATS_PERSISTENT} (an unrelated
     * table option) and a column literally named {@code PERSISTENT} are
     * untouched.
     */
    private static final Pattern PERSISTENT = Pattern.compile("(?<![\\w.])(?i:PERSISTENT)(?![\\w])");

    /** An {@code SRID <number>} clause in a column attribute list. */
    private static final Pattern SRID_CLAUSE = Pattern.compile("(?<![\\w.])(?i:SRID)\\s+(\\d+)(?![\\w])");

    /**
     * MySQL 8's unquoted bit-value literal {@code 0b<binary>}.
     *
     * <p>Lower-case {@code b} only, and at least one digit: MySQL 8.0.41
     * accepts {@code 0b1} and {@code 0b0}, and reads {@code 0B101} as an
     * identifier ("Unknown column") while a bare {@code 0b} is the identifier
     * {@code 0b}, so neither the upper-case spelling nor a digit-less one is
     * touched. The lookahead keeps a non-binary digit out of the match --
     * {@code 0b102} is an identifier in MySQL, not a literal that stops at
     * {@code 0b10} -- and the lookbehind keeps an identifier character or an
     * outer {@code x}/{@code b} prefix out, so {@code cb01} and {@code 0x0b10}
     * are never rewritten.</p>
     */
    private static final Pattern BIT_VALUE_LITERAL = Pattern.compile("(?<![\\w.$])0b([01]+)(?![\\w.$])");

    /**
     * The numeric value of a {@code NODEGROUP} partition option, with its
     * optional {@code =}.
     *
     * <p>The grammar reads this value as a {@code uid} -- an identifier -- so
     * the integer MySQL always writes has to arrive quoted. Only the digits are
     * matched; the {@code NODEGROUP} keyword and its spacing are left exactly as
     * written, and a value that is already quoted does not satisfy the
     * lookbehind, so a second pass changes nothing.</p>
     */
    private static final Pattern NODEGROUP_VALUE = Pattern.compile(
            "(?<![\\w.])NODEGROUP\\b\\s*=?\\s*(?<!['\"`])(\\d+)(?![\\w.])", Pattern.CASE_INSENSITIVE);

    /**
     * The row alias of {@code INSERT ... VALUES (...) AS new} together with the
     * column-alias list MySQL 8.0.19 allows after it
     * ({@code AS new(m, n)}).
     *
     * <p>The shipped grammar reads {@code AS? uid} but has no place for a
     * parenthesised list after that {@code uid}, so the documented
     * {@code VALUES (...) AS new(m, n) ON DUPLICATE KEY UPDATE} spelling is a
     * syntax error at the {@code (}. Only the list is matched; the
     * {@code AS new} that precedes it is left in place, because the grammar
     * accepts exactly that.</p>
     */
    private static final Pattern ROW_ALIAS_COLUMNS = Pattern.compile(
            "(?<![\\w.])(?i:AS)\\s+(?:`[^`]+`|[\\w$]+)\\s*\\(");

    /**
     * The whole row alias of an {@code INSERT ... SET} statement,
     * {@code AS new} and its optional column list included
     * ({@code SET a = 1 AS new(m)}).
     *
     * <p>MySQL 8.0.19 allows the row alias after the {@code SET} assignment
     * list as well as after {@code VALUES}, but the shipped grammar puts
     * {@code AS? uid} only where a {@code VALUES} list has just ended: on the
     * {@code SET} form {@code INSERT INTO t SET a = 1 AS new ON DUPLICATE KEY
     * UPDATE ...} fails at the {@code AS} with {@code mismatched input 'AS'
     * expecting {<EOF>, '-'}}. The trailing context is part of the pattern so
     * an {@code AS} inside an expression ({@code CAST(x AS SIGNED)}) can never
     * match: the clause is only the one that ends the statement or hands over to
     * {@code ON DUPLICATE KEY UPDATE}.</p>
     *
     * <p>Unlike the {@code VALUES} form, where only the column list has to go,
     * here the whole clause is dropped, because the grammar has no
     * {@code AS? uid} on this alternative at all. The names the clause
     * introduced stay ordinary identifiers in {@code ON DUPLICATE KEY UPDATE}
     * ({@code new.a} reads as a qualified column), and a DML statement produces
     * no ClickHouse DDL -- rows arrive as binlog row events -- so nothing that
     * reaches ClickHouse refers to them.</p>
     */
    private static final Pattern SET_FORM_ROW_ALIAS = Pattern.compile(
            "\\s+(?<![\\w.])(?i:AS)\\s+(?:`[^`]+`|[\\w$]+)(?:\\s*\\([^()]*\\))?"
                    + "(?=\\s*(?:(?i:ON)\\s+(?i:DUPLICATE)\\s+(?i:KEY)\\s+(?i:UPDATE)\\b|;?\\s*$))");

    /**
     * The first of the keywords that decide which {@code INSERT} form a
     * statement uses.
     *
     * <p>Only the earliest one counts: an identifier or a literal called
     * {@code set} deeper in the statement is not the {@code SET} form's
     * keyword, and {@code INSERT ... VALUES (...)} never has one.</p>
     */
    private static final Pattern INSERT_FORM_KEYWORD = Pattern.compile(
            "(?<![\\w.])(?i:SET|VALUES|SELECT|WITH|TABLE)\\b");

    /**
     * The {@code ROW} keyword of a row constructor
     * ({@code VALUES ROW(1, 2), ROW(3, 4)}), which MySQL 8.0.19 introduced.
     *
     * <p>The shipped grammar's values list is {@code VALUES} followed by
     * parenthesised value lists, with no {@code ROW} keyword at all, so the
     * documented spelling fails at {@code VALUES ROW}. Dropping the keyword
     * leaves the parenthesised list the grammar reads. {@code ROW} is a
     * reserved word in MySQL, so an unquoted occurrence here is always the
     * constructor; a backticked identifier or a literal is masked out.</p>
     */
    private static final Pattern ROW_CONSTRUCTOR_KEYWORD = Pattern.compile(
            "(?<![\\w.$])(?i:ROW)\\s*(?=\\()");

    /**
     * The leading words of every statement family that the shipped grammar
     * cannot parse in ANY form and that the translator has NO listener handler
     * for.
     *
     * <p>Both halves are load-bearing. {@code StatementSurfaceCoverageTest}
     * pins the handler set at eight statement kinds -- database and table DDL --
     * so no tablespace, resource group, spatial reference system, clone or
     * persisted-variable statement can ever produce ClickHouse DDL. There is
     * therefore nothing to translate and nothing to emit; the only requirement
     * is that an ordinary day-two administrative statement does not abort the
     * replication stream for every table on the host.</p>
     *
     * <p>Matched on whole words, in order, from the start of the statement, so
     * {@code ALTER TABLESPACE} is never confused with {@code ALTER TABLE} (the
     * word {@code TABLESPACE} does not equal {@code TABLE}) and
     * {@code SET PERSIST} with {@code SET PERSIST_ONLY}. A family whose
     * statements already parse is deliberately absent: blanking those would
     * cost parse coverage for nothing, which is the defect this whole class
     * exists to prevent. {@code ALTER TABLESPACE} and {@code ALTER USER} are
     * matched by FORM inside {@link #blankUntranslatableStatement} instead,
     * because each has forms the grammar does read: a tablespace
     * {@code ADD|DROP DATAFILE} statement and a single-factor
     * {@code IDENTIFIED BY} account statement both parse today.</p>
     */
    private static final String[][] UNTRANSLATABLE_STATEMENT_HEADS = {
            {"ALTER", "UNDO", "TABLESPACE"},
            {"CREATE", "UNDO", "TABLESPACE"},
            {"DROP", "UNDO", "TABLESPACE"},
            {"CREATE", "RESOURCE", "GROUP"},
            {"ALTER", "RESOURCE", "GROUP"},
            {"DROP", "RESOURCE", "GROUP"},
            {"SET", "RESOURCE", "GROUP"},
            {"CREATE", "SPATIAL", "REFERENCE", "SYSTEM"},
            {"DROP", "SPATIAL", "REFERENCE", "SYSTEM"},
            {"CLONE", "INSTANCE"},
            {"CLONE", "LOCAL"},
            {"SET", "PERSIST_ONLY"},
            {"SET", "PERSIST"},
            {"RESET", "PERSIST"},
    };

    /** {@code CREATE TABLE} / {@code CREATE TEMPORARY TABLE} statement heads. */
    private static final String[][] CREATE_TABLE_HEADS = {
            {"CREATE", "TABLE"},
            {"CREATE", "TEMPORARY", "TABLE"},
    };

    /**
     * {@code ALTER TABLE} statement heads, including the {@code ONLINE},
     * {@code OFFLINE} and {@code IGNORE} forms the grammar itself accepts.
     */
    private static final String[][] ALTER_TABLE_HEADS = {
            {"ALTER", "TABLE"},
            {"ALTER", "ONLINE", "TABLE"},
            {"ALTER", "OFFLINE", "TABLE"},
            {"ALTER", "IGNORE", "TABLE"},
    };

    /**
     * The statement kinds {@link MySqlDDLParserListenerImpl} has a handler for.
     *
     * <p>This mirrors that class's {@code enter*} overrides exactly -- create and
     * drop database/schema, the three create-table forms, alter table, drop
     * table, rename table(s) and truncate table -- and nothing else. It is the
     * definition of "this statement can produce ClickHouse DDL", which is what
     * decides whether a parse failure is recoverable: inside this set a failure
     * is silent divergence and must be loud, outside it no handler can run at
     * all and a failure costs nothing but the parse.</p>
     */
    private static final String[][] HANDLED_STATEMENT_HEADS = {
            {"CREATE", "DATABASE"},
            {"CREATE", "SCHEMA"},
            {"DROP", "DATABASE"},
            {"DROP", "SCHEMA"},
            {"CREATE", "TABLE"},
            {"CREATE", "TEMPORARY", "TABLE"},
            {"ALTER", "TABLE"},
            {"ALTER", "ONLINE", "TABLE"},
            {"ALTER", "OFFLINE", "TABLE"},
            {"ALTER", "IGNORE", "TABLE"},
            {"DROP", "TABLE"},
            {"DROP", "TEMPORARY", "TABLE"},
            {"RENAME", "TABLE"},
            {"RENAME", "TABLES"},
            {"TRUNCATE", "TABLE"},
            {"TRUNCATE"},
    };

    /**
     * The keyword that makes the clause bridge worth attempting at all. A
     * statement carrying none of these is returned before any mask is built.
     */
    private static final Pattern TABLE_CLAUSE_KEYWORD = Pattern.compile(
            "(?i)(?<![\\w])(?:SECONDARY_LOAD|SECONDARY_UNLOAD|SECONDARY_ENGINE|AUTOEXTEND_SIZE)"
                    + "(?![\\w])");

    /** A word, for the mask-aware {@code IDENTIFIED} count of {@code ALTER USER}. */
    private static final Pattern IDENTIFIED_WORD = Pattern.compile("(?i)(?<![\\w])IDENTIFIED(?![\\w])");

    /**
     * The {@code ALTER USER ... DISCARD OLD PASSWORD} form. The grammar's
     * {@code alterUserMysqlV56} alternative reads only a user list with no
     * trailing clause at all, so this form is a syntax error.
     */
    private static final Pattern DISCARD_OLD_PASSWORD =
            Pattern.compile("(?i)(?<![\\w])DISCARD\\s+OLD\\s+PASSWORD(?![\\w])");

    /**
     * One whole {@code ALTER TABLE} specification that carries no ClickHouse
     * effect, anchored so it can only match a clause in its entirety.
     *
     * <p>{@code SECONDARY_LOAD}/{@code SECONDARY_UNLOAD} start or stop loading
     * a table into a secondary engine, and {@code SECONDARY_ENGINE} names or
     * removes that engine; ClickHouse has no secondary engine, so neither
     * clause has anything to say about the replica. {@code AUTOEXTEND_SIZE}
     * sizes a tablespace's growth step, which is storage layout, not schema.
     * None of the three is a {@code tableOption} in the shipped grammar's
     * {@code alterSpecification}, so each one is a syntax error where MySQL
     * writes it.</p>
     *
     * <p>The anchored form is what keeps the bridge honest: a clause that
     * merely MENTIONS the word -- {@code ADD COLUMN SECONDARY_LOAD INT},
     * {@code MODIFY COLUMN c INT COMMENT 'SECONDARY_LOAD'} -- does not match,
     * because it does not start with the keyword.</p>
     */
    private static final Pattern DROPPABLE_TABLE_CLAUSE = Pattern.compile(
            "(?is)^(?:(?:SECONDARY_LOAD|SECONDARY_UNLOAD)"
                    + "|SECONDARY_ENGINE\\s*=\\s*\\S+"
                    + "|AUTOEXTEND_SIZE\\s*=?\\s*\\d+(?:\\.\\d+)?\\s*[KMG]?)$");

    /**
     * The {@code AUTOEXTEND_SIZE} table option of a {@code CREATE TABLE},
     * matched wherever it sits among the options after the table definition.
     *
     * <p>Same reasoning as the {@code ALTER} clause: MySQL 8.0 accepts
     * {@code CREATE TABLE t (a INT) AUTOEXTEND_SIZE = 4M}, and a table whose
     * body the parser rejects is a table that never gets created on the
     * replica. The word boundaries are both-sided so a column or an identifier
     * that happens to be called {@code autoextend_size} is never matched, and
     * the mask keeps a {@code COMMENT} that quotes the option out of reach.</p>
     */
    private static final Pattern AUTOEXTEND_SIZE_OPTION = Pattern.compile(
            "(?i)(?<![\\w])AUTOEXTEND_SIZE\\s*=?\\s*\\d+(?:\\.\\d+)?\\s*[KMG]?(?![\\w.])");

    private MySqlDdlNormalizer() {
    }

    /**
     * Rewrites {@code sql} so that the bundled grammar can parse it.
     *
     * @param sql the MySQL DDL statement as it arrived from the source binlog.
     * @return the statement with every supported gap bridged; the same instance
     *         when nothing needed rewriting.
     */
    static String normalize(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String kept = blankUntranslatableStatement(sql);
        if (kept.isEmpty()) {
            return kept;
        }
        return dropRowConstructorKeyword(dropSetFormRowAlias(dropRowAliasColumnList(
                quoteNodeGroupValue(relocateSrid(quoteBitValueLiterals(substitutePersistent(
                        dropUnsupportedTableClauses(kept))))))));
    }

    /**
     * Blanks a statement whose family the translator has no handler for at all.
     *
     * <p>This is the loss-free half of the grammar-gap problem, and the reason
     * it is loss-free is structural rather than a judgement about the statement:
     * {@link MySqlDDLParserListenerImpl} overrides {@code enter*} for eight
     * statement kinds only -- create/drop database, create/alter/drop/rename/
     * truncate table -- so a statement outside those kinds cannot reach any
     * handler, cannot produce parse-tree state the translator reads, and cannot
     * emit ClickHouse DDL however well it parses. Parsing it is therefore not a
     * requirement; only not being fatal is.</p>
     *
     * <p>A statement in one of the {@code HANDLED} families is never blanked,
     * because losing it would be silent divergence rather than a no-op:
     * {@code ALTER TABLE t FROBNICATE COLUMN c} must keep throwing.</p>
     *
     * @return the empty string when the statement's family has no handler, so
     *         the caller parses an empty stream and emits nothing; otherwise
     *         {@code sql} itself.
     */
    /**
     * Whether any parse of {@code sql}, however successful, could produce
     * ClickHouse DDL.
     *
     * <p>True exactly for the statement kinds {@link MySqlDDLParserListenerImpl}
     * carries an {@code enter*} handler for. Everything else -- a
     * {@code SELECT}, a stored program, a replication command, an
     * administration statement -- is walked by a listener with no callback for
     * it, so its parse tree is never read and nothing can be emitted from it
     * whatever it says.</p>
     *
     * <p>The caller uses this to decide whether a parse failure is recoverable:
     * inside the handled set a failure is silent divergence from a replicated
     * table's schema and must stay loud, outside it the failure costs only the
     * parse.</p>
     */
    static boolean handlesStatementKind(String sql) {
        return startsWithWords(leadingWords(sql, 3), HANDLED_STATEMENT_HEADS);
    }

    private static String blankUntranslatableStatement(String sql) {
        List<String> head = leadingWords(sql, 4);
        if (head.isEmpty()) {
            return sql;
        }
        for (String[] family : UNTRANSLATABLE_STATEMENT_HEADS) {
            if (startsWithWords(head, family)) {
                return "";
            }
        }
        // The remainder of the ALTER TABLESPACE family: the grammar's only
        // alternative for it is ADD|DROP DATAFILE ... ENGINE, which already
        // parses, so the word in that position decides which half this is.
        if (startsWithWords(head, "ALTER", "TABLESPACE") && head.size() > 3
                && !head.get(3).equals("ADD") && !head.get(3).equals("DROP")) {
            return "";
        }
        // Two account forms the grammar cannot read, while the ordinary forms
        // beside them must keep parsing: a second IDENTIFIED is MySQL's
        // multi-factor clause, and DISCARD OLD PASSWORD has no alternative at
        // all. The count is mask-aware, so a password or a comment that happens
        // to contain the word does not trip it.
        if (startsWithWords(head, "ALTER", "USER")) {
            BitSet quoted = quotedOrComment(sql);
            Matcher identified = IDENTIFIED_WORD.matcher(sql);
            int count = 0;
            while (identified.find()) {
                if (!quoted.get(identified.start())) {
                    count++;
                }
            }
            if (count > 1) {
                return "";
            }
            Matcher discard = DISCARD_OLD_PASSWORD.matcher(sql);
            while (discard.find()) {
                if (!quoted.get(discard.start())) {
                    return "";
                }
            }
        }
        return sql;
    }

    /**
     * Removes a table clause that carries no ClickHouse effect and that the
     * shipped grammar cannot read, leaving the rest of the statement intact.
     *
     * <p>This is the half where blanking would be WRONG. {@code ALTER TABLE t
     * ADD COLUMN c INT, SECONDARY_LOAD} changes a replicated table's schema, so
     * dropping the statement would silently lose the column; only the trailing
     * clause may go. The same rule applies to the {@code AUTOEXTEND_SIZE} table
     * option of {@code CREATE TABLE}, where dropping the statement would create
     * a table with no columns at all.</p>
     *
     * <p>Nothing is removed unless it is a clause in clause position. A column,
     * an identifier or a comment that merely uses one of these words as its own
     * name does not sit where a specification begins, so it is left exactly as
     * written and the statement fails loudly as before -- the property that
     * keeps {@code ADD COLUMN SECONDARY_LOAD INT} working and keeps a genuine
     * typo from being absorbed.</p>
     */
    private static String dropUnsupportedTableClauses(String sql) {
        List<String> head = leadingWords(sql, 3);
        if (head.isEmpty()) {
            return sql;
        }
        boolean alterTable = startsWithWords(head, ALTER_TABLE_HEADS);
        boolean createTable = startsWithWords(head, CREATE_TABLE_HEADS);
        if (!alterTable && !createTable) {
            return sql;
        }
        if (!TABLE_CLAUSE_KEYWORD.matcher(sql).find()) {
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        return createTable ? dropAutoextendSizeOption(sql, quoted)
                : dropDroppableAlterClauses(sql, quoted);
    }

    /**
     * Removes every {@code AUTOEXTEND_SIZE} option sitting among a
     * {@code CREATE TABLE}'s options, which is the region after the closing
     * parenthesis of the column definitions.
     *
     * <p>Anchoring on that parenthesis is what keeps a column definition that
     * uses the word out of reach, so the option can only be the real table
     * option and the table body is never touched.</p>
     */
    private static String dropAutoextendSizeOption(String sql, BitSet quoted) {
        int bodyClose = createTableBodyClose(sql, quoted);
        if (bodyClose < 0) {
            return sql;
        }
        Matcher matcher = AUTOEXTEND_SIZE_OPTION.matcher(sql);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (matcher.start() <= bodyClose || quoted.get(matcher.start())) {
                continue;
            }
            int start = matcher.start();
            while (start > bodyClose && Character.isWhitespace(sql.charAt(start - 1))) {
                start--;
            }
            if (out == null) {
                out = new StringBuilder(sql.length());
            }
            out.append(sql, last, start);
            last = matcher.end();
        }
        if (out == null) {
            return sql;
        }
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * The offset of the {@code )} that closes a {@code CREATE TABLE}'s column
     * definitions, or {@code -1} when the statement defines none
     * ({@code CREATE TABLE ... LIKE}, {@code ... AS SELECT}). The first
     * parenthesis outside a literal or comment is always the body: the table
     * name cannot contain one.
     */
    private static int createTableBodyClose(String sql, BitSet quoted) {
        for (int i = 0; i < sql.length(); i++) {
            if (quoted.get(i)) {
                continue;
            }
            if (sql.charAt(i) == '(') {
                return matchingParen(sql, i, quoted);
            }
        }
        return -1;
    }

    /**
     * Removes every no-effect specification from an {@code ALTER TABLE}, with
     * its separator, so the specifications beside it survive.
     *
     * <p>A clause only counts when it begins where a specification can begin:
     * immediately after the table reference, or immediately after a preceding
     * specification's comma. That single test is what separates {@code ALTER
     * TABLE t SECONDARY_UNLOAD} (a clause) from {@code ALTER TABLE t ADD COLUMN
     * SECONDARY_LOAD INT} (a column called that) without guessing from the
     * word.</p>
     *
     * <p>The separator that goes with the clause depends on where it sits. A
     * clause that is not first takes the comma in front of it, so {@code a, X, b}
     * becomes {@code a, b}. A clause that IS first takes the comma after it, so
     * {@code t X, b} becomes {@code t b} -- no leading comma, which the grammar
     * would not read.</p>
     */
    private static String dropDroppableAlterClauses(String sql, BitSet quoted) {
        int clauseStart = alterTableClauseStart(sql, quoted);
        if (clauseStart < 0) {
            return sql;
        }
        List<int[]> specifications = alterSpecifications(sql, quoted, clauseStart);
        List<int[]> cuts = new ArrayList<>();
        for (int i = 0; i < specifications.size(); i++) {
            int[] specification = specifications.get(i);
            String text = sql.substring(specification[0], specification[1]).trim();
            if (!DROPPABLE_TABLE_CLAUSE.matcher(text).matches()) {
                continue;
            }
            if (specification[2] >= 0) {
                // Not the first specification: the comma in front of it goes too.
                cuts.add(new int[]{specification[2], specification[1]});
            } else if (i + 1 < specifications.size()) {
                // First of several: it takes the comma after it instead, so no
                // leading comma is left behind for the grammar to choke on.
                cuts.add(new int[]{specification[0], specifications.get(i + 1)[2] + 1});
            } else {
                // The whole specification list: only the header is left, which
                // enterAlterTable already clears.
                cuts.add(new int[]{specification[0], sql.length()});
            }
        }
        if (cuts.isEmpty()) {
            return sql;
        }
        return removeRanges(sql, mergeOverlapping(cuts));
    }

    /**
     * The offset at which an {@code ALTER TABLE}'s specification list begins:
     * past {@code ALTER}, the optional {@code ONLINE}/{@code OFFLINE}/
     * {@code IGNORE}, {@code TABLE}, the table reference and an optional
     * {@code WAIT <n>}/{@code NOWAIT}.
     */
    private static int alterTableClauseStart(String sql, BitSet quoted) {
        int n = sql.length();
        int tableWordEnd = -1;
        int i = 0;
        while (i < n) {
            if (quoted.get(i) || !Character.isLetter(sql.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && (Character.isLetterOrDigit(sql.charAt(i))
                    || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                i++;
            }
            if (i - start == 5 && sql.regionMatches(true, start, "TABLE", 0, 5)) {
                tableWordEnd = i;
                break;
            }
        }
        if (tableWordEnd < 0) {
            return -1;
        }
        int j = tableWordEnd;
        while (j < n && Character.isWhitespace(sql.charAt(j))) {
            j++;
        }
        while (j < n && !Character.isWhitespace(sql.charAt(j))
                && sql.charAt(j) != ',' && sql.charAt(j) != '(' && sql.charAt(j) != ')') {
            j++;
        }
        while (j < n && Character.isWhitespace(sql.charAt(j))) {
            j++;
        }
        if (sql.regionMatches(true, j, "NOWAIT", 0, 6)) {
            return j + 6;
        }
        if (sql.regionMatches(true, j, "WAIT", 0, 4)) {
            j += 4;
            while (j < n && Character.isWhitespace(sql.charAt(j))) {
                j++;
            }
            while (j < n && Character.isDigit(sql.charAt(j))) {
                j++;
            }
            return j;
        }
        return j;
    }

    /**
     * The {@code ALTER TABLE} specification list split on its top-level commas,
     * never inside a literal, a comment or a parenthesis.
     *
     * <p>Each entry is {@code {start, end, precedingComma}} -- the specification's
     * extent and the offset of the comma that ends the specification before it,
     * or {@code -1} for the first. Carrying that offset is what lets the caller
     * remove a clause together with the separator on the correct side.</p>
     */
    private static List<int[]> alterSpecifications(String sql, BitSet quoted, int clauseStart) {
        List<int[]> specifications = new ArrayList<>();
        int start = clauseStart;
        int precedingComma = -1;
        int depth = 0;
        for (int i = clauseStart; i < sql.length(); i++) {
            if (quoted.get(i)) {
                continue;
            }
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            } else if (c == ',' && depth == 0) {
                specifications.add(new int[]{start, i, precedingComma});
                precedingComma = i;
                start = i + 1;
            }
        }
        specifications.add(new int[]{start, sql.length(), precedingComma});
        return specifications;
    }

    /** Sorts the ranges and merges any that touch, so their offsets stay valid. */
    private static List<int[]> mergeOverlapping(List<int[]> ranges) {
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] range : sorted) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && range[0] <= last[1]) {
                last[1] = Math.max(last[1], range[1]);
            } else {
                merged.add(new int[]{range[0], range[1]});
            }
        }
        return merged;
    }

    /** Deletes the merged ranges, right to left so no offset moves. */
    private static String removeRanges(String sql, List<int[]> ranges) {
        String result = sql;
        for (int i = ranges.size() - 1; i >= 0; i--) {
            int[] range = ranges.get(i);
            result = result.substring(0, range[0]) + result.substring(range[1]);
        }
        return result;
    }

    /**
     * The statement's leading words, upper-cased, skipping whitespace and
     * comments. Scanning stops at the first character that is not part of a
     * word, so a statement this class does not model yields a short or empty
     * list rather than a wrong family.
     */
    private static List<String> leadingWords(String sql, int maxWords) {
        List<String> words = new ArrayList<>(maxWords);
        int i = 0;
        int n = sql.length();
        while (i < n && words.size() < maxWords) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (Character.isLetter(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i))
                        || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                words.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                break;
            }
        }
        return words;
    }

    /** True when {@code words} starts with this exact run of words. */
    private static boolean startsWithWords(List<String> words, String... expected) {
        if (words.size() < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (!words.get(i).equals(expected[i])) {
                return false;
            }
        }
        return true;
    }

    /** True when {@code words} starts with any of these exact runs of words. */
    private static boolean startsWithWords(List<String> words, String[][] families) {
        for (String[] family : families) {
            if (startsWithWords(words, family)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the {@code ROW} keyword of every row constructor in an
     * {@code INSERT}/{@code REPLACE} values list.
     *
     * <p>Loss-free for replication for the same reason as the other DML
     * rewrites: the rows arrive as binlog row events, so no ClickHouse SQL is
     * derived from this text either way, and the parenthesised value lists that
     * remain carry every value the source wrote.</p>
     */
    private static String dropRowConstructorKeyword(String sql) {
        if (!startsRowConstructorStatement(sql)) {
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = ROW_CONSTRUCTOR_KEYWORD.matcher(sql);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(sql.length());
            }
            out.append(sql, last, matcher.start());
            last = matcher.end();
        }
        if (out == null) {
            return sql;
        }
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * Drops the row alias of an {@code INSERT ... SET} statement, which the
     * shipped grammar cannot read there.
     *
     * <p>Runs after {@link #dropRowAliasColumnList} has removed any column list,
     * so the only text it has to recognise is {@code AS new} handing over to
     * {@code ON DUPLICATE KEY UPDATE} or the end of the statement.</p>
     */
    private static String dropSetFormRowAlias(String sql) {
        if (!isSetFormInsert(sql)) {
            return sql;
        }
        return SET_FORM_ROW_ALIAS.matcher(sql).replaceAll("");
    }

    /**
     * True when {@code sql} is an {@code INSERT}/{@code REPLACE} statement whose
     * data comes from a {@code SET} assignment list.
     *
     * <p>The first form keyword after the leading DML word decides it. A quoted
     * identifier, a literal or a comment can contain the words too, so the scan
     * skips those spans with the same mask the other rewrites use.</p>
     */
    private static boolean isSetFormInsert(String sql) {
        if (!startsWithDmlKeyword(sql)) {
            return false;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = INSERT_FORM_KEYWORD.matcher(sql);
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            return sql.regionMatches(true, matcher.start(), "SET", 0, 3);
        }
        return false;
    }

    /**
     * Drops the column-alias list of an {@code INSERT ... VALUES (...) AS
     * new(m, n)} row alias, which the shipped grammar cannot read.
     *
     * <p>MySQL 8.0.19 documents three row-alias spellings; the shipped parser
     * accepts two of them ({@code AS new} after {@code VALUES}, and after
     * {@code SET}) and rejects the third, the one carrying column aliases. The
     * parenthesised list is the only part it cannot read: {@code AS? uid} is an
     * accessor of {@code insertStatement}, a second parenthesised list is not.
     * Removing the list leaves {@code AS new}, which parses.</p>
     *
     * <p>Loss-free for replication because a DML statement produces no
     * ClickHouse DDL at all -- rows arrive as binlog row events, not by
     * re-executing the source text -- so the aliases have no consumer on this
     * path. The aliased names inside {@code ON DUPLICATE KEY UPDATE} remain
     * ordinary identifiers, which is exactly what the grammar needs them to
     * be.</p>
     *
     * <p>Only an {@code INSERT}/{@code REPLACE} statement is touched. A DDL
     * statement has no row alias, so restricting the rewrite keeps
     * {@code CREATE TABLE ... AS (SELECT ...)} and every other {@code AS}
     * spelling out of reach.</p>
     */
    private static String dropRowAliasColumnList(String sql) {
        if (!startsWithDmlKeyword(sql)) {
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = ROW_ALIAS_COLUMNS.matcher(sql);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            int open = matcher.end() - 1;
            int close = matchingParen(sql, open, quoted);
            if (close < 0) {
                // Unbalanced: leave the text alone so the parser reports it.
                continue;
            }
            if (out == null) {
                out = new StringBuilder(sql.length());
            }
            out.append(sql, last, open);
            last = close + 1;
        }
        if (out == null) {
            return sql;
        }
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * True when {@code sql} is a statement whose text can carry a row
     * constructor: an {@code INSERT}/{@code REPLACE}, or a standalone
     * {@code VALUES} statement, which the grammar has an alternative for.
     */
    private static boolean startsRowConstructorStatement(String sql) {
        if (startsWithDmlKeyword(sql)) {
            return true;
        }
        int i = 0;
        int n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i + 6 <= n && sql.regionMatches(true, i, "VALUES", 0, 6);
    }

    /** True when the first quoted-free word of {@code sql} is INSERT or REPLACE. */
    private static boolean startsWithDmlKeyword(String sql) {
        int i = 0;
        int n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i + 6 <= n && sql.regionMatches(true, i, "INSERT", 0, 6)) {
            return true;
        }
        return i + 7 <= n && sql.regionMatches(true, i, "REPLACE", 0, 7);
    }

    /**
     * The offset of the {@code )} that closes the {@code (} at {@code open}, or
     * {@code -1} when the statement ends first.
     *
     * <p>Nesting is counted so an alias list whose names are themselves
     * parenthesised cannot end the scan early. A parenthesis inside a string
     * literal, quoted identifier or comment is data, so it is skipped.</p>
     */
    private static int matchingParen(String sql, int open, BitSet quoted) {
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            if (quoted.get(i)) {
                continue;
            }
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Rewrites {@code NODEGROUP = 2} to {@code NODEGROUP = `2`}, the only
     * spelling the grammar's {@code uid} can read.
     *
     * <p>Partition options are dropped by the translator, so what the value
     * says never reaches ClickHouse; quoting it only makes the statement
     * parseable. A statement carrying no {@code NODEGROUP} is returned by
     * identity.</p>
     */
    private static String quoteNodeGroupValue(String sql) {
        if (sql.indexOf("ODEGROUP") < 0) {
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = NODEGROUP_VALUE.matcher(sql);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(sql.length() + 8);
            }
            out.append(sql, last, matcher.start(1))
                    .append('`').append(matcher.group(1)).append('`');
            last = matcher.end(1);
        }
        if (out == null) {
            return sql;
        }
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * Rewrites {@code 0b101} to the quoted {@code b'101'}, which the grammar
     * already knows and MySQL reads as the same bit value.
     *
     * <p>Both spellings denote one bit string, and a bit value reaches the
     * translator either as bytes (the hex text the writer stores, padded to
     * whole bytes) or as the number the bits denote, so the quoting changes
     * nothing about what is replicated.</p>
     */
    private static String quoteBitValueLiterals(String sql) {
        if (sql.indexOf("0b") < 0) {
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = BIT_VALUE_LITERAL.matcher(sql);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(sql.length() + 8);
            }
            out.append(sql, last, matcher.start())
                    .append('b').append('\'').append(matcher.group(1)).append('\'');
            last = matcher.end();
        }
        if (out == null) {
            return sql;
        }
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * Replaces the {@code PERSISTENT} spelling with {@code STORED}, which the
     * grammar already knows.
     *
     * <p>The bridge is defensive, and this is what makes it loss-free rather
     * than a guess: both words mean the generated value is STORED, so
     * translating one to the other cannot change the replicated column, and
     * the connector emits the same ClickHouse {@code DEFAULT <expression>} for
     * either. Where the word never appears the pass changes nothing.</p>
     *
     * <p>It is not claimed here that MySQL documents the spelling. It does
     * not: the generated-column syntax in the MySQL 8.0 and 9.7 reference
     * manuals reads {@code [VIRTUAL | STORED]} only, and {@code PERSISTENT} is
     * absent from the MySQL 8.0 keyword list. The spelling in practice comes
     * from MariaDB, and from the copy of the grammar this repository carries
     * under {@code src/main/antlr4}, whose own comment reads "Same as STORED
     * for MySQL". Bridging it costs nothing when it is absent and keeps a
     * MariaDB-compatible statement from halting replication when it is
     * present.</p>
     */
    private static String substitutePersistent(String sql) {
        if (sql.indexOf("ERSISTENT") < 0) {
            // No PERSISTENT in any casing: skip both the mask and the regex.
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = PERSISTENT.matcher(sql);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(sql.length());
            }
            out.append(sql, last, matcher.start());
            String matched = matcher.group();
            out.append(matched.equals(matched.toUpperCase(Locale.ROOT)) ? "STORED" : "stored");
            last = matcher.end();
        }
        if (out == null) {
            return sql;
        }
        out.append(sql, last, sql.length());
        return out.toString();
    }

    /**
     * Moves every out-of-position {@code SRID <n>} clause to immediately after
     * its column's spatial type, which is the only position the grammar
     * accepts.
     *
     * <p>A clause is already in position when nothing but whitespace separates
     * it from the spatial type; those are left untouched, so a statement that
     * is already grammatical is returned unchanged. A clause with no spatial
     * type in its own column -- {@code INDEX(g) SRID 4326}, or any statement
     * this class does not model -- is also left exactly as written, so the
     * parser still reports it instead of the text being silently rearranged.</p>
     */
    private static String relocateSrid(String sql) {
        if (sql.indexOf("RID") < 0) {
            return sql;
        }
        BitSet quoted = quotedOrComment(sql);
        Matcher matcher = SRID_CLAUSE.matcher(sql);
        List<int[]> edits = new ArrayList<>();
        while (matcher.find()) {
            if (quoted.get(matcher.start())) {
                continue;
            }
            int typeEnd = spatialTypeEnd(sql, matcher.start());
            if (typeEnd < 0) {
                continue;
            }
            if (sql.substring(typeEnd, matcher.start()).isBlank()) {
                // Already immediately after the type: nothing to move.
                continue;
            }
            edits.add(new int[]{typeEnd, matcher.start(), matcher.end()});
        }
        if (edits.isEmpty()) {
            return sql;
        }
        // Right to left, so every offset stays valid: each edit only moves text
        // that sits to the right of the one before it.
        for (int i = edits.size() - 1; i >= 0; i--) {
            int[] edit = edits.get(i);
            int typeEnd = edit[0];
            int clauseStart = edit[1];
            int clauseEnd = edit[2];
            String clause = sql.substring(clauseStart, clauseEnd);
            StringBuilder out = new StringBuilder(sql.length());
            out.append(sql, 0, typeEnd)
                    .append(' ').append(clause)
                    .append(sql, typeEnd, clauseStart)
                    .append(sql, clauseEnd, sql.length());
            sql = out.toString();
        }
        return sql;
    }

    /**
     * The offset just past the spatial type that belongs to the column the
     * clause at {@code clauseStart} sits in, or {@code -1} when that column
     * declares no spatial type.
     *
     * <p>The column is the text from the nearest preceding column separator
     * ({@code ,} {@code (} {@code )}) up to the clause. Bounding the search
     * this way keeps an {@code SRID} in a later column from being attached to
     * an earlier column's spatial type.</p>
     */
    private static int spatialTypeEnd(String sql, int clauseStart) {
        int boundary = Math.max(Math.max(sql.lastIndexOf(',', clauseStart - 1),
                sql.lastIndexOf('(', clauseStart - 1)), sql.lastIndexOf(')', clauseStart - 1));
        Matcher type = SPATIAL_TYPE_TOKEN.matcher(sql);
        type.region(boundary + 1, clauseStart);
        return type.find() ? type.end() : -1;
    }

    /**
     * Marks every character offset that lies inside a string literal, a quoted
     * identifier or a comment, so no rewrite can alter text that is data
     * rather than statement structure.
     *
     * <p>Delimiters count as inside, which is safe: no keyword match starts on
     * a quote.</p>
     */
    private static BitSet quotedOrComment(String sql) {
        BitSet inside = new BitSet(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            int start = i;
            if (c == '\'' || c == '"' || c == '`') {
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\\' && c != '`') {
                        // Backslash escape: skip the escaped character.
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == c) {
                        if (i < n && sql.charAt(i) == c) {
                            // Doubled quote is an escaped quote, not the end.
                            i++;
                            continue;
                        }
                        break;
                    }
                }
            } else if (c == '#') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else {
                i++;
                continue;
            }
            inside.set(start, Math.min(i, n));
        }
        return inside;
    }
}

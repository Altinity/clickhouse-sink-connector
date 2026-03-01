package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import postgres.PostgreSQLLexer;
import postgres.PostgreSQLParser;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service responsible for parsing DDL (Data Definition Language) SQL statements
 * received from the Debezium Engine for PostgreSQL sources and translating them
 * into corresponding ClickHouse DDL statements.
 *
 * <p>This implementation uses the ANTLR4-generated {@link PostgreSQLLexer} and
 * {@link PostgreSQLParser} (from the grammar files under
 * {@code src/main/antlr4/postgres/}) to perform accurate, grammar-driven
 * translation via {@link PostgreSQLDDLParserListenerImpl}.
 *
 * <p>Supported DDL operations:
 * <ul>
 *   <li>CREATE TABLE</li>
 *   <li>ALTER TABLE ADD / DROP / ALTER COLUMN</li>
 *   <li>ALTER TABLE RENAME COLUMN (via renamestmt)</li>
 *   <li>DROP TABLE</li>
 *   <li>TRUNCATE TABLE</li>
 * </ul>
 */
public class PostgreSQLDDLParserService implements DDLParserService {

    private static final Logger log = LogManager.getLogger(PostgreSQLDDLParserService.class);

    /** The name of the database being processed. */
    private final String databaseName;

    /** The configuration for the ClickHouse Sink Connector. */
    private final ClickHouseSinkConnectorConfig config;

    /** The writer responsible for executing DDL queries on the database. */
    private final BaseDbWriter writer;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Constructs a PostgreSQLDDLParserService with the specified writer,
     * configuration, and database name.
     *
     * @param writer       the writer responsible for executing DDL queries.
     * @param config       the ClickHouse Sink Connector configuration.
     * @param databaseName the name of the destination database in ClickHouse.
     */
    public PostgreSQLDDLParserService(BaseDbWriter writer,
                                      ClickHouseSinkConnectorConfig config,
                                      String databaseName) {
        this.writer        = writer;
        this.config        = config;
        this.databaseName  = databaseName;
    }

    /**
     * Constructs a PostgreSQLDDLParserService with the specified configuration
     * and database name (no live writer).
     *
     * @param config       the ClickHouse Sink Connector configuration.
     * @param databaseName the name of the destination database in ClickHouse.
     */
    public PostgreSQLDDLParserService(ClickHouseSinkConnectorConfig config,
                                      String databaseName) {
        this(null, config, databaseName);
    }

    // -----------------------------------------------------------------------
    // DDLParserService interface
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>Parses the given PostgreSQL DDL statement using the ANTLR grammar and
     * appends the equivalent ClickHouse DDL to {@code parsedQuery}.
     */
    @Override
    public String parseSql(String sql, String tableName, StringBuffer parsedQuery) {
        if (sql == null || sql.trim().isEmpty()) return null;
        try {
            runAntlrPipeline(sql, tableName, parsedQuery);
        } catch (Exception e) {
            log.error("Error parsing PostgreSQL DDL: {}", sql, e);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Same as {@link #parseSql(String, String, StringBuffer)} but also sets
     * {@code isDropOrTruncate} when the statement is a DROP or TRUNCATE.
     */
    @Override
    public String parseSql(String sql, String tableName,
                           StringBuffer parsedQuery,
                           AtomicBoolean isDropOrTruncate) {
        if (sql == null || sql.trim().isEmpty()) return null;
        try {
            CommonTokenStream tokens = tokenise(sql);
            isDropOrTruncate.set(isDropOrTruncateStatement(tokens));

            // Re-tokenise for parsing (token stream is consumed by above call)
            runAntlrPipeline(sql, tableName, parsedQuery);
        } catch (Exception e) {
            log.error("Error parsing PostgreSQL DDL: {}", sql, e);
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // ANTLR pipeline
    // -----------------------------------------------------------------------

    /**
     * Runs the full ANTLR lexer → parser → listener pipeline for a single DDL
     * statement.
     *
     * @param sql         the raw PostgreSQL DDL string.
     * @param tableName   Debezium-supplied table name hint.
     * @param parsedQuery output buffer that receives the translated ClickHouse DDL.
     */
    private void runAntlrPipeline(String sql, String tableName, StringBuffer parsedQuery) {
        PostgreSQLLexer lexer     = new PostgreSQLLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens  = new CommonTokenStream(lexer);
        PostgreSQLParser parser   = new PostgreSQLParser(tokens);

        // Use a lenient (non-throwing) error listener so that valid DDL that
        // contains PG-specific constructs the grammar partially recovers from
        // still produces useful output rather than aborting entirely.
        LenientErrorListener errorListener = new LenientErrorListener();
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener);
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener);

        PostgreSQLDDLParserListenerImpl listener =
            new PostgreSQLDDLParserListenerImpl(writer, parsedQuery, tableName, databaseName, config);

        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        if (parsedQuery.length() > 0) {
            log.info("PostgreSQL DDL translated: [{}] -> [{}]", sql, parsedQuery);
        } else {
            log.warn("PostgreSQL DDL produced no output (unsupported or ignored): {}", sql);
        }
    }

    // -----------------------------------------------------------------------
    // Lenient error listener (logs but does not throw)
    // -----------------------------------------------------------------------

    /**
     * An ANTLR error listener that logs parse errors at WARN level without
     * throwing an exception.  This allows the listener to still produce partial
     * output even when the grammar encounters constructs it cannot fully parse.
     */
    private static final class LenientErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol,
                                int line, int charPositionInLine,
                                String msg,
                                RecognitionException e) {
            log.warn("PostgreSQL DDL parse warning at {}:{} – {}", line, charPositionInLine, msg);
        }
    }

    /**
     * Tokenises the given SQL string into a {@link CommonTokenStream} without
     * running the parser.  Used for lightweight keyword checks such as
     * {@link #isDropOrTruncateStatement(CommonTokenStream)}.
     *
     * @param sql the raw SQL string.
     * @return a filled (but not yet filtered) token stream.
     */
    private static CommonTokenStream tokenise(String sql) {
        PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(sql));
        lexer.removeErrorListeners();          // suppress noise for keyword check
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();                         // eagerly materialise all tokens
        return tokens;
    }

    /**
     * Returns {@code true} when the token stream contains a {@code DROP} or
     * {@code TRUNCATE} keyword, indicating that the statement will remove data
     * from ClickHouse.
     *
     * @param tokens a filled {@link CommonTokenStream}.
     * @return {@code true} if the statement is DROP or TRUNCATE.
     */
    public boolean isDropOrTruncateStatement(CommonTokenStream tokens) {
        List<Token> list = tokens.getTokens();
        return list.stream().anyMatch(t ->
            t.getType() == PostgreSQLLexer.DROP ||
            t.getType() == PostgreSQLLexer.TRUNCATE);
    }

    /**
     * Returns {@code true} when the token stream contains a {@code CREATE}
     * keyword.
     *
     * @param tokens a filled {@link CommonTokenStream}.
     * @return {@code true} if the statement is a CREATE statement.
     */
    public boolean isCreateStatement(CommonTokenStream tokens) {
        List<Token> list = tokens.getTokens();
        return list.stream().anyMatch(t -> t.getType() == PostgreSQLLexer.CREATE);
    }

    // -----------------------------------------------------------------------
    // Static type mapper (preserved for use by the listener and tests)
    // -----------------------------------------------------------------------

    /**
     * Maps a PostgreSQL column type string to the most appropriate ClickHouse
     * type.
     *
     * <p>Non-primary-key columns should be wrapped with {@code Nullable(…)} by
     * the caller (the listener handles this automatically).
     *
     * @param pgType the PostgreSQL type string (case-insensitive), e.g.
     *               {@code "INTEGER"}, {@code "TIMESTAMP WITH TIME ZONE"},
     *               {@code "NUMERIC(10,2)"}.
     * @return the corresponding ClickHouse type string.
     */
    public static String mapPostgresTypeToClickHouse(String pgType) {
        if (pgType == null) return "String";

        String upper = pgType.trim().toUpperCase();

        // --- Integer / Serial types ---
        if (upper.equals("BIGSERIAL") || upper.equals("SERIAL8")) return "Int64";
        if (upper.equals("SERIAL")    || upper.equals("SERIAL4")) return "Int64";
        if (upper.equals("SMALLSERIAL") || upper.equals("SERIAL2")) return "Int16";

        if (upper.equals("BIGINT")  || upper.equals("INT8")  || upper.equals("INT64")) return "Int64";
        if (upper.equals("INTEGER") || upper.equals("INT")   || upper.equals("INT4")
                || upper.equals("INT32")) return "Int32";
        if (upper.equals("SMALLINT") || upper.equals("INT2") || upper.equals("INT16")) return "Int16";

        // --- Boolean ---
        if (upper.equals("BOOLEAN") || upper.equals("BOOL")) return "UInt8";

        // --- Floating point ---
        if (upper.equals("REAL") || upper.equals("FLOAT4")) return "Float32";
        if (upper.equals("DOUBLE PRECISION") || upper.equals("FLOAT8")
                || upper.equals("FLOAT")) return "Float64";

        // --- Numeric / Decimal with precision and scale ---
        if (upper.startsWith("NUMERIC") || upper.startsWith("DECIMAL")) {
            Pattern precScale = Pattern.compile(
                "(?:NUMERIC|DECIMAL)\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)",
                Pattern.CASE_INSENSITIVE);
            Matcher pm = precScale.matcher(pgType.trim());
            if (pm.find()) return "Decimal(" + pm.group(1) + ", " + pm.group(2) + ")";

            Pattern precOnly = Pattern.compile(
                "(?:NUMERIC|DECIMAL)\\s*\\(\\s*(\\d+)\\s*\\)",
                Pattern.CASE_INSENSITIVE);
            Matcher po = precOnly.matcher(pgType.trim());
            if (po.find()) return "Decimal(" + po.group(1) + ", 0)";

            // NUMERIC without precision → wide Decimal default
            return "Decimal(38, 9)";
        }

        // --- Character types ---
        if (upper.startsWith("CHARACTER VARYING") || upper.startsWith("VARCHAR")) return "String";
        if (upper.startsWith("CHARACTER") || upper.startsWith("CHAR")) return "String";
        if (upper.equals("TEXT")   || upper.equals("CITEXT")) return "String";
        if (upper.equals("NAME")) return "String";

        // --- Date / Time types ---
        if (upper.equals("TIMESTAMP WITH TIME ZONE") || upper.equals("TIMESTAMPTZ")) {
            return "DateTime64(6, 'UTC')";
        }
        if (upper.equals("TIMESTAMP WITHOUT TIME ZONE") || upper.equals("TIMESTAMP")) {
            return "DateTime64(6)";
        }
        if (upper.startsWith("TIMESTAMP")) {
            if (upper.contains("WITH TIME ZONE")) return "DateTime64(6, 'UTC')";
            Pattern tsp = Pattern.compile("TIMESTAMP\\s*\\(\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE);
            Matcher tsm = tsp.matcher(pgType.trim());
            if (tsm.find()) return "DateTime64(" + tsm.group(1) + ")";
            return "DateTime64(6)";
        }
        if (upper.equals("DATE")) return "Date32";
        if (upper.equals("TIME") || upper.equals("TIME WITHOUT TIME ZONE")) return "String";
        if (upper.equals("TIME WITH TIME ZONE") || upper.equals("TIMETZ")) return "String";
        if (upper.equals("INTERVAL")) return "String";

        // --- UUID ---
        if (upper.equals("UUID")) return "UUID";

        // --- JSON ---
        if (upper.equals("JSON") || upper.equals("JSONB")) return "String";

        // --- Binary ---
        if (upper.equals("BYTEA")) return "String";

        // --- Network / other PG-specific ---
        if (upper.equals("INET") || upper.equals("CIDR")
                || upper.equals("MACADDR") || upper.equals("MACADDR8")) return "String";
        if (upper.equals("TSVECTOR") || upper.equals("TSQUERY")) return "String";
        if (upper.equals("XML")) return "String";
        if (upper.equals("MONEY")) return "Decimal(19, 4)";
        if (upper.equals("BIT") || upper.startsWith("BIT VARYING")
                || upper.startsWith("VARBIT")) return "String";
        if (upper.startsWith("OID")) return "UInt32";

        // --- Array types → String (stored as JSON) ---
        if (upper.endsWith("[]") || upper.startsWith("ARRAY")) return "String";

        // --- Unknown: safe fallback ---
        log.warn("Unknown PostgreSQL type '{}', falling back to String", pgType);
        return "String";
    }
}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.debezium.antlr.CaseChangingCharStream;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Service responsible for parsing DDL (Data Definition Language) SQL statements
 * received from the Debezium Engine using MySQL Antlr grammar.
 * <p>
 * This class provides functionality to parse MySQL DDL statements such as
 * CREATE, DROP, and TRUNCATE using Antlr-based parsing and map them to
 * corresponding operations that can be executed on ClickHouse.
 * </p>
 */
@Singleton
public class MySQLDDLParserService implements DDLParserService {

    /**
     * Logger instance for this service.
     */
    private static final Logger log = LogManager.getLogger(MySQLDDLParserService.class);

    /**
     * Fallback used only when the DDL cannot be lexed at all. Deliberately
     * broad: the caller uses the verdict to decide whether
     * disable.drop.truncate must suppress the statement, and a missed DROP is
     * data loss while a spurious one is a skipped DDL that is logged loudly.
     */
    private static final Pattern DROP_OR_TRUNCATE_TEXT =
            Pattern.compile("(?is)\\b(DROP|TRUNCATE)\\b");

    /**
     * The name of the database being processed.
     */
    private String databaseName;

    /**
     * The configuration for the ClickHouse Sink Connector.
     */
    private ClickHouseSinkConnectorConfig config;

    /**
     * The writer responsible for executing DDL queries on the database.
     */
    private BaseDbWriter writer;

    /**
     * Default constructor for MySQLDDLParserService.
     */
    @Inject
    public MySQLDDLParserService() {

    }

    /**
     * Constructs a MySQLDDLParserService with the specified configuration and database name.
     *
     * @param config the ClickHouse Sink Connector configuration.
     * @param databaseName the name of the database.
     */
    public MySQLDDLParserService(ClickHouseSinkConnectorConfig config, String databaseName) {
        this.config = config;
        this.databaseName = databaseName;
    }

    /**
     * Constructs a MySQLDDLParserService with the specified writer, configuration, and database name.
     *
     * @param writer the writer responsible for executing DDL queries.
     * @param config the ClickHouse Sink Connector configuration.
     * @param databaseName the name of the database.
     */
    public MySQLDDLParserService(BaseDbWriter writer, ClickHouseSinkConnectorConfig config, String databaseName) {
        this.writer = writer;
        this.config = config;
        this.databaseName = databaseName;
    }

    /**
     * Parses a given SQL statement and generates the corresponding ClickHouse query.
     *
     * @param sql the SQL statement to parse.
     * @param tableName the name of the table for which the query is generated.
     * @param parsedQuery a StringBuffer to hold the parsed query.
     * @return the corresponding ClickHouse query.
     */
    @Override
    public String parseSql(String sql, String tableName, StringBuffer parsedQuery) {

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        // Initialize error listener
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        // Initialize the listener to handle the parsing logic
        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(writer, parsedQuery, tableName, databaseName, config, sql);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        // The translation is accumulated into parsedQuery by the listener.
        // Return it so the documented String contract is honoured; existing
        // callers that read the StringBuffer are unaffected.
        return parsedQuery.toString();
    }

    /**
     * Parses a given SQL statement and checks whether it is a DROP or TRUNCATE statement.
     *
     * @param sql the SQL statement to parse.
     * @param tableName the name of the table for which the query is generated.
     * @param parsedQuery a StringBuffer to hold the parsed query.
     * @param isDropOrTruncate a flag indicating whether the statement is a DROP or TRUNCATE.
     * @return the corresponding ClickHouse query.
     */
    @Override
    public String parseSql(String sql, String tableName,  StringBuffer parsedQuery, AtomicBoolean isDropOrTruncate) {

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        // Initialize error listener
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        // Initialize the listener to handle the parsing logic
        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(writer, parsedQuery, tableName, databaseName, this.config, sql);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        // Set the drop or truncate flag
        isDropOrTruncate.set(isDropOrTruncateStatement(tokens));

        // See the three-argument overload: return the accumulated translation
        // rather than an always-null local.
        return parsedQuery.toString();
    }

    /**
     * Whether a raw DDL statement is a DROP or TRUNCATE, determined from the
     * DDL text alone.
     *
     * <p>Lexes only: no parse tree is built, no listener runs and no database
     * is touched, so the verdict is available BEFORE the statement is
     * translated. That is the whole point of it. The disable.drop.truncate
     * guard used to read a flag whose only writer is
     * {@link #parseSql(String, String, StringBuffer, AtomicBoolean)}, and
     * parseSql runs after the guard, so the flag was false on every
     * evaluation (issue #1287).</p>
     *
     * <p>The classification is the same one
     * {@link #isDropOrTruncateStatement(CommonTokenStream)} has always made:
     * any DROP or TRUNCATE keyword token. It is intentionally not narrowed to
     * DROP TABLE / TRUNCATE TABLE here -- narrowing it would let statements
     * through that the setting has always claimed to cover, and the failure
     * direction of this control is data loss.</p>
     *
     * @param sql the raw DDL statement; may be null.
     * @return true if the statement carries a DROP or TRUNCATE keyword.
     */
    public static boolean isDropOrTruncate(String sql) {
        if (sql == null || sql.isEmpty()) {
            return false;
        }
        try {
            MySqlLexer lexer = new MySqlLexer(
                    new CaseChangingCharStream(CharStreams.fromString(sql), true));
            // No error listener: a malformed statement must still get a
            // verdict here rather than throwing out of the ignore-rule check.
            lexer.removeErrorListeners();
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            // getTokens() only returns what has been fetched; nothing has
            // consumed this stream, so it has to be filled explicitly.
            tokens.fill();
            return containsDropOrTruncate(tokens.getTokens());
        } catch (Exception e) {
            log.warn("Could not lex DDL to classify it as DROP/TRUNCATE, falling back "
                    + "to a textual match: {}", sql, e);
            return DROP_OR_TRUNCATE_TEXT.matcher(sql).find();
        }
    }

    /**
     * Checks if the given DDL statement is a DROP or TRUNCATE statement.
     *
     * @param tokens the list of tokens generated by the lexer.
     * @return true if the statement is DROP or TRUNCATE, false otherwise.
     */
    public boolean isDropOrTruncateStatement(CommonTokenStream tokens) {
        return containsDropOrTruncate(tokens.getTokens());
    }

    /**
     * The single definition of "this statement is a DROP or a TRUNCATE",
     * shared by the token-stream and the raw-SQL entry points so the two can
     * never disagree.
     *
     * @param tokensList the lexed tokens.
     * @return true if any token is a DROP or TRUNCATE keyword.
     */
    private static boolean containsDropOrTruncate(List<Token> tokensList) {
        return tokensList.stream().anyMatch(
                x -> x.getType() == MySqlParser.DROP || x.getType() == MySqlParser.TRUNCATE);
    }

    /**
     * Checks if the given DDL statement is a CREATE TABLE or CREATE DATABASE statement.
     *
     * @param tokens the list of tokens generated by the lexer.
     * @return true if the statement is CREATE, false otherwise.
     */
    public boolean isCreateStatement(CommonTokenStream tokens) {
        boolean result = false;
        List<Token> tokensList = tokens.getTokens();

        if (tokensList.stream().anyMatch(x -> x.getType() == MySqlParser.CREATE)) {
            result = true;
        }

        return result;
    }
}

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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * Optional override of how the translator learns the existing ClickHouse
     * schema of an ALTER's target table (nullability, sorting key). Null means
     * "read it through DBMetadata on the writer's connection"; tests inject a
     * fixed answer here (Spec 06.03 §3.4).
     */
    private TargetSchemaLookup targetSchemaLookup;

    /**
     * Statement time ({@code source.ts_ms}) of the DDL event being parsed, in
     * epoch milliseconds; 0 when the caller did not supply one (Spec 06.04
     * §3.2.2).
     */
    private long ddlEventTimestampMs;

    /**
     * Default constructor for MySQLDDLParserService.
     */
    @Inject
    public MySQLDDLParserService() {

    }

    /**
     * Overrides the target-schema lookup used by every listener this service
     * creates. Intended for tests; production leaves it unset and reads the
     * schema from ClickHouse.
     *
     * @param targetSchemaLookup the lookup, or null to restore the default.
     */
    public void setTargetSchemaLookup(TargetSchemaLookup targetSchemaLookup) {
        this.targetSchemaLookup = targetSchemaLookup;
    }

    @Override
    public void setDdlEventTimestampMs(long ddlEventTimestampMs) {
        this.ddlEventTimestampMs = ddlEventTimestampMs;
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

        String clickHouseResult = null;

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        // Initialize error listener
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        // Initialize the listener to handle the parsing logic
        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(writer, parsedQuery, tableName, databaseName, config, sql);
        listener.setTargetSchemaLookup(targetSchemaLookup);
        listener.setDdlEventTimestampMs(ddlEventTimestampMs);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        return clickHouseResult;
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
        String clickHouseResult = null;

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        // Initialize error listener
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        // Initialize the listener to handle the parsing logic
        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(writer, parsedQuery, tableName, databaseName, this.config, sql);
        listener.setTargetSchemaLookup(targetSchemaLookup);
        listener.setDdlEventTimestampMs(ddlEventTimestampMs);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        // Statement KIND, decided by the parse tree: DROP TABLE / TRUNCATE
        // TABLE / DROP DATABASE. The earlier token scan flagged any DROP
        // token, so ALTER TABLE ... DROP COLUMN and DROP INDEX counted too
        // (spec 06.08 section 3.3).
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        isDropOrTruncate.set(listener.isDropOrTruncateStatement());

        return clickHouseResult;
    }

    /**
     * Checks whether the statement is, at statement level, a {@code DROP
     // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
     * TABLE}, {@code TRUNCATE [TABLE]} or {@code DROP DATABASE}.
     *
     * <p>Decided by the first significant tokens, never by the presence of a
     * {@code DROP} token anywhere: {@code ALTER TABLE t DROP COLUMN c},
     * {@code DROP INDEX i ON t} and {@code ALTER COLUMN c DROP DEFAULT} are
     * not data-destroying statements and must not be caught by
     // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
     * {@code disable.drop.truncate}.</p>
     *
     * @param tokens the list of tokens generated by the lexer.
     * @return true if the statement drops or truncates a table or database.
     */
    public boolean isDropOrTruncateStatement(CommonTokenStream tokens) {
        List<Token> significant = new java.util.ArrayList<>();
        for (Token t : tokens.getTokens()) {
            if (t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF) {
                significant.add(t);
            }
            if (significant.size() >= 2) {
                break;
            }
        }
        if (significant.isEmpty()) {
            return false;
        }
        int first = significant.get(0).getType();
        // DESTRUCTIVE: statement text is only parsed/classified/logged here; nothing is executed against any database.
        if (first == MySqlParser.TRUNCATE) {
            return true;
        }
        if (first == MySqlParser.DROP && significant.size() > 1) {
            int second = significant.get(1).getType();
            return second == MySqlParser.TABLE || second == MySqlParser.TEMPORARY
                    || second == MySqlParser.DATABASE || second == MySqlParser.SCHEMA;
        }
        return false;
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

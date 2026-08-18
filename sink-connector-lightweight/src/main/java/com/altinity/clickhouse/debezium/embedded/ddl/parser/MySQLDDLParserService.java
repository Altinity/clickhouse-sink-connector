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
     * Logger for the MySQLDDLParserService class.
     */
    private static final Logger log = LogManager.getLogger(MySQLDDLParserService.class);

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
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

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
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        // Set the drop or truncate flag
        isDropOrTruncate.set(isDropOrTruncateStatement(tokens));

        return parsedQuery.toString();
    }

    /**
     * Checks if the given DDL statement is a DROP or TRUNCATE statement.
     *
     * @param tokens the list of tokens generated by the lexer.
     * @return true if the statement is DROP or TRUNCATE, false otherwise.
     */
    /**
     * Extracts the table name a DDL statement operates on.
     *
     * <p>Used to key DDL schema-cache invalidation. Debezium's SchemaChangeKey does
     * not carry a table name, so it must be recovered from the DDL text itself.</p>
     *
     * <p>Returns the bare table name (schema qualifier and quoting removed), or null
     * when the statement has no single table subject (e.g. CREATE/DROP DATABASE) or
     * cannot be tokenised.</p>
     *
     * @param sql the DDL statement.
     * @return the table name, or null if it cannot be determined.
     */
    public static String extractTableName(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return null;
        }
        try {
            MySqlLexer lexer = new MySqlLexer(
                    new CaseChangingCharStream(CharStreams.fromString(sql), true));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            tokens.fill();

            List<Token> tokensList = tokens.getTokens();
            boolean sawTable = false;
            for (Token token : tokensList) {
                if (token.getChannel() != Token.DEFAULT_CHANNEL
                        || token.getType() == Token.EOF) {
                    continue;
                }
                int type = token.getType();
                if (type == MySqlParser.TABLE) {
                    sawTable = true;
                    continue;
                }
                if (!sawTable) {
                    continue;
                }
                // Skip the optional qualifiers that may follow TABLE.
                if (type == MySqlParser.IF || type == MySqlParser.EXISTS
                        || type == MySqlParser.NOT) {
                    continue;
                }
                // The identifier may be schema-qualified. Two lexer shapes occur:
                //   `db`.`tbl`  -> ID(`db`), DOT_ID(.`tbl`)  or three tokens
                //   db.tbl      -> ID(db),   DOT_ID(.tbl)
                // Keep consuming trailing dotted components and return the LAST
                // one. Returning the first would yield the DATABASE name and
                // invalidate the wrong cache key, leaving the real DbWriter stale.
                String identifier = token.getText();
                int index = tokensList.indexOf(token);
                while (true) {
                    Token next = nextDefaultChannelToken(tokensList, index);
                    if (next == null) {
                        break;
                    }
                    String nextText = next.getText();
                    if (nextText != null && nextText.startsWith(".")
                            && nextText.length() > 1) {
                        // Single DOT_ID token, e.g. ".orders".
                        identifier = nextText.substring(1);
                        index = tokensList.indexOf(next);
                        continue;
                    }
                    if (".".equals(nextText)) {
                        // Separate dot token; the component follows it.
                        Token after = nextDefaultChannelToken(
                                tokensList, tokensList.indexOf(next));
                        if (after == null) {
                            break;
                        }
                        identifier = after.getText();
                        index = tokensList.indexOf(after);
                        continue;
                    }
                    break;
                }
                return normalizeTableName(identifier);
            }
        } catch (Exception e) {
            log.debug("Unable to extract table name from DDL: {}", sql, e);
        }
        return null;
    }

    /**
     * Returns the next token on the default channel after the given index, or null.
     *
     * @param tokensList the full token list.
     * @param fromIndex  the index to search after.
     * @return the next default-channel token, or null if there is none.
     */
    private static Token nextDefaultChannelToken(List<Token> tokensList, int fromIndex) {
        for (int i = fromIndex + 1; i < tokensList.size(); i++) {
            Token candidate = tokensList.get(i);
            if (candidate.getChannel() != Token.DEFAULT_CHANNEL) {
                continue;
            }
            if (candidate.getType() == Token.EOF) {
                return null;
            }
            return candidate;
        }
        return null;
    }

    /**
     * Strips backtick/quote characters and any database qualifier from an identifier.
     *
     * @param rawIdentifier the raw identifier token text.
     * @return the bare table name, or null if empty.
     */
    private static String normalizeTableName(String rawIdentifier) {
        if (rawIdentifier == null) {
            return null;
        }
        String name = rawIdentifier.replace("`", "").replace("\"", "").trim();
        int lastDot = name.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < name.length() - 1) {
            name = name.substring(lastDot + 1);
        }
        return name.isEmpty() ? null : name;
    }

    public boolean isDropOrTruncateStatement(CommonTokenStream tokens) {

        List<Token> tokensList = tokens.getTokens();

        // Only the LEADING keyword decides whether this statement destroys data.
        // Scanning the whole token stream for any DROP/TRUNCATE token also matched
        // "ALTER TABLE t DROP COLUMN c" and "CREATE TABLE ... DROP ..." in identifiers
        // or comments, which would make DISABLE_DROP_TRUNCATE=true block legitimate,
        // non-destructive schema evolution.
        for (Token token : tokensList) {
            if (token.getChannel() != Token.DEFAULT_CHANNEL) {
                // Skip whitespace and comments.
                continue;
            }
            if (token.getType() == Token.EOF) {
                break;
            }
            return token.getType() == MySqlParser.DROP
                    || token.getType() == MySqlParser.TRUNCATE;
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

package com.altinity.clickhouse.debezium.embedded.ddl.parser;


import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
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
 * Use MySQL Antlr grammer, this class
 * is responsible for parsing DDL received
 * from Debezium Engine.
 */
@Singleton
public class MySQLDDLParserService implements DDLParserService {

    private String databaseName;

    private ClickHouseSinkConnectorConfig config;

    @Inject
    public MySQLDDLParserService() {

    }
    public MySQLDDLParserService(ClickHouseSinkConnectorConfig config, String databaseName) {
        this.config = config;
        this.databaseName = databaseName;
    }

    @Override
    public String parseSql(String sql, String tableName, StringBuffer parsedQuery) {

        String clickHouseResult = null;

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        //parser.root();
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(parsedQuery, tableName, databaseName, config);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        return clickHouseResult;
    }

    @Override
    public String parseSql(String sql, String tableName,  StringBuffer parsedQuery, AtomicBoolean isDropOrTruncate) {
        String clickHouseResult = null;

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        //parser.root();
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(parsedQuery, tableName, databaseName, this.config);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());


        isDropOrTruncate.set(isDropOrTruncateStatement(tokens));

        return clickHouseResult;
    }

    /**
     * Function to check if the DDL is a DROP or TRUNCATE.
     * @param tokens Antlr tokens
     * @return true if its drop or truncate, false otherwise.
     */
    public boolean isDropOrTruncateStatement(CommonTokenStream tokens) {

        boolean result = false;
        List<Token> tokensList = tokens.getTokens();

        if(tokensList.stream().anyMatch(x -> x.getType() == MySqlParser.DROP || x.getType() == MySqlParser.TRUNCATE)) {
            result = true;
        }

        return result;
    }

    /**
     * Function to check if the DDL query is a CREATE TABLE or DATABASE query.
     * @param tokens
     * @return
     */
    public boolean isCreateStatement(CommonTokenStream tokens) {
        boolean result = false;
        List<Token> tokensList = tokens.getTokens();

        if(tokensList.stream().anyMatch(x -> x.getType() == MySqlParser.CREATE)) {
            result = true;
        }

        return result;
    }
}

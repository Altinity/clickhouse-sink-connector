package com.altinity.clickhouse.debezium.embedded.ddl.parser;


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

        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(parsedQuery, tableName);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listener, parser.root());

        return clickHouseResult;
    }

    @Override
    public String parseSql(String sql, String tableName, StringBuffer parsedQuery, AtomicBoolean isDropOrTruncate) {
        String clickHouseResult = null;

        MySqlLexer lexer = new MySqlLexer(new CaseChangingCharStream(CharStreams.fromString(sql), true));

        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySqlParser parser = new MySqlParser(tokens);
        //parser.root();
        ErrorListenerImpl errorListener = new ErrorListenerImpl();
        parser.addErrorListener(errorListener);
        lexer.addErrorListener(errorListener);

        MySqlDDLParserListenerImpl listener = new MySqlDDLParserListenerImpl(parsedQuery, tableName);
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
}

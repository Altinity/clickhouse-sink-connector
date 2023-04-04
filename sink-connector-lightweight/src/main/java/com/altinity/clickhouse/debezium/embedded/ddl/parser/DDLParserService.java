package com.altinity.clickhouse.debezium.embedded.ddl.parser;

public interface DDLParserService {
    String parseSql(String sql, String tableName, StringBuffer parsedQuery);
}

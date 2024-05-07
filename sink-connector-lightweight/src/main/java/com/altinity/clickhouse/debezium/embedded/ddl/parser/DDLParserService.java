package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.concurrent.atomic.AtomicBoolean;

public interface DDLParserService {
    String parseSql(String sql, String tableName,  StringBuffer parsedQuery);

    String parseSql(String sql, String tableName,StringBuffer parsedQuery, AtomicBoolean isDropOrTruncate);

}

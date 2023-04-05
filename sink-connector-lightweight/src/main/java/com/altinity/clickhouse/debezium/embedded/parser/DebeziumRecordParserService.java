package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.source.SourceRecord;

public interface DebeziumRecordParserService {
    ClickHouseStruct parse(SourceRecord record);
}

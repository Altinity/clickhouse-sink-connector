package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;

public interface DebeziumRecordParserService {
    ClickHouseStruct parse(SourceRecord record, DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer);
}

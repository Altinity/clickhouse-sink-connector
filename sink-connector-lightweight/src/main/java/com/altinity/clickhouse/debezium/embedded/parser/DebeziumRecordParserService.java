package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Service interface for parsing Debezium records.
 *
 * <p>
 * This interface defines a method for parsing a change event record into a
 * ClickHouseStruct object.
 * </p>
 */
public interface DebeziumRecordParserService {

    /**
     * Parses the given Debezium change event record.
     *
     * <p>
     * This method processes the provided change event record and returns its
     * representation as a ClickHouseStruct. The committer can be used to commit
     * the record after processing if required.
     * </p>
     *
     * @param record the Debezium change event containing a source record.
     * @param committer the record committer for the change event.
     * @param lastRecordInBatch true if this record is the last in the batch.
     * @return a ClickHouseStruct representing the parsed record.
     */
    ClickHouseStruct parse(
            ChangeEvent<SourceRecord, SourceRecord> record,
            DebeziumEngine.RecordCommitter<
                    ChangeEvent<SourceRecord, SourceRecord>> committer,
            boolean lastRecordInBatch);
}

package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.metadata.SchemaPair;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.isNull;

public class BufferedRecords {

    private Schema keySchema;
    private Schema valueSchema;

    public List<SinkRecord> add(SinkRecord record) {
        //recordValidator.validate(record);
        final List<SinkRecord> flushed = new ArrayList<>();

        boolean schemaChanged = false;

        // Find out key schema status
        if (!Objects.equals(keySchema, record.keySchema())) {
            keySchema = record.keySchema();
            schemaChanged = true;
        }

        // Find out key schema status
        if (isNull(record.valueSchema())) {
            // For deletes, value and optionally value schema come in as null.
            // We don't want to treat this as a schema change if key schemas is the same
            // otherwise we flush unnecessarily.
//            if (config.deleteEnabled) {
//                deletesInBatch = true;
//            }
        } else if (Objects.equals(valueSchema, record.valueSchema())) {
            //if (config.deleteEnabled && deletesInBatch) {
            // flush so an insert after a delete of same record isn't lost
            //  flushed.addAll(flush());
            //}
        } else {
            // Value schema is available and has changed. This is a real schema change.
            valueSchema = record.valueSchema();
            schemaChanged = true;
        }

        if (schemaChanged) {
            // Each batch needs to have the same schemas, so get the buffered records out
            //flushed.addAll(flush());

            // re-initialize everything that depends on the record schema
            final SchemaPair schemaPair = new SchemaPair(
                    record.keySchema(),
                    record.valueSchema()
            );

            return flushed;
        }

        return flushed;
    }
}

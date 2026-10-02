package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared fixtures for the offset-FIFO tests: a committer that records the
 * exact order of {@code markProcessed} calls, and records wired to it.
 */
final class OffsetTestSupport {

    private OffsetTestSupport() {
    }

    /** Records what the engine's committer was asked to do, in order. */
    static final class RecordingCommitter
            implements DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> {

        final List<ChangeEvent<SourceRecord, SourceRecord>> processed = new ArrayList<>();
        int batchesFinished = 0;
        /** Number of markProcessed calls at the time of each markBatchFinished. */
        final List<Integer> finishedAfter = new ArrayList<>();

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record) {
            processed.add(record);
        }

        @Override
        public void markBatchFinished() {
            batchesFinished++;
            finishedAfter.add(processed.size());
        }

        @Override
        public void markProcessed(ChangeEvent<SourceRecord, SourceRecord> record,
                                  DebeziumEngine.Offsets sourceOffsets) {
            processed.add(record);
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return (key, value) -> { };
        }
    }

    /** A distinct, identifiable change event. */
    static ChangeEvent<SourceRecord, SourceRecord> event(String name) {
        return new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return null;
            }

            @Override
            public String destination() {
                return name;
            }

            @Override
            public Integer partition() {
                return null;
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    /**
     * A record that {@code acknowledgeRecords} will stage with the committer.
     *
     * @param name      identifies the record in the committer's log
     *                  (also used as the topic: {@code srv.db.<name>}).
     * @param tsMs      envelope timestamp, the value the deleted overlap rule compared.
     * @param committer the committer to stage with.
     */
    static ClickHouseStruct record(String name, long tsMs, RecordingCommitter committer) {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTopic("srv.db." + name);
        s.setDebezium_ts_ms(tsMs);
        s.setCommitter(committer);
        s.setSourceRecord(event(name));
        return s;
    }

    /** A batch whose last record carries the terminal marker, as every handed-off unit does. */
    static List<ClickHouseStruct> unit(RecordingCommitter committer, long tsMs, String... names) {
        List<ClickHouseStruct> b = new ArrayList<>();
        for (String n : names) {
            b.add(record(n, tsMs, committer));
        }
        b.get(b.size() - 1).setLastRecordInBatch(true);
        return b;
    }

    /** Names of the records staged with the committer, in order. */
    static List<String> processedNames(RecordingCommitter committer) {
        List<String> names = new ArrayList<>();
        for (ChangeEvent<SourceRecord, SourceRecord> e : committer.processed) {
            names.add(e.destination());
        }
        return names;
    }

    /** Clears the static FIFO state so each test starts with nothing outstanding. */
    static void resetFifo() {
        DebeziumOffsetManagement.outstandingSequences.clear();
        DebeziumOffsetManagement.groupToUnit.clear();
        DebeziumOffsetManagement.completedUnits.clear();
    }
}

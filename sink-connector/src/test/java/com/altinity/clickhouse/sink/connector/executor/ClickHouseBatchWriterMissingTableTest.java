package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Debezium batch carrying records for a table that does not exist in
 * ClickHouse must not be discarded quietly.
 *
 * <p>With {@code auto.create.tables=false} and the target table missing,
 * {@code DbWriter} cannot read the table metadata, so
 * {@code processRecordsByTopic} exhausts its retries and returns {@code false}
 * (the {@code wasTableMetaDataRetrieved()} block in ClickHouseBatchWriter).
 * The per-topic loop in {@code persistRecords} used to {@code break} on that
 * and let the method return normally, and the outer {@code catch} swallowed
 * anything thrown. The caller,
 * {@code DebeziumChangeEventCapture.appendToRecords}, has no return value to
 * inspect and no exception to catch, so {@code handleBatch} completed as if
 * the batch had been written.</p>
 *
 * <p>What that costs: the acknowledgement block is skipped, so THIS batch is
 * never committed -- but the engine moves straight on to the next batch, whose
 * {@code markBatchFinished()} commits a HIGHER offset. The unwritten records
 * are then behind the committed offset and are never replayed. Records for
 * every topic ordered after the failing one in the batch are dropped the same
 * way, having never been attempted at all.</p>
 *
 * <p>This is specific to the single-threaded writer path
 * ({@code single.threaded=true}, which is what the lightweight connector uses
 * when configured that way). {@code ClickHouseBatchRunnable} contains the same
 * {@code break} but survives it: it only clears {@code currentBatch} when the
 * result is true, so it re-polls and retries the same batch. This writer is
 * invoked once per batch and has no retry loop, so failing loudly is the only
 * way for it not to lose data.</p>
 *
 * <p>The reproduction points the writer at a ClickHouse that cannot be
 * reached, which drives {@code wasTableMetaDataRetrieved()} to false through
 * the same state a missing table produces -- an empty column map and a null
 * engine -- without needing a live server. The assertions are about the
 * OUTCOME the issue reports: no exception reaches the caller, and not one
 * record is acknowledged, so nothing distinguishes a dropped batch from a
 * written one.</p>
 *
 * <p>See issue #1285.</p>
 */
public class ClickHouseBatchWriterMissingTableTest {

    /** A port nothing listens on, so metadata retrieval always fails. */
    private static final int UNREACHABLE_PORT = 1;

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("name", Schema.STRING_SCHEMA)
            .build();

    /** DBMetadata declared default, restored so this test leaks no state. */
    private static final int DEFAULT_MAX_RETRIES = 10;

    @BeforeEach
    public void setUp() {
        // The metadata helpers retry MAX_RETRIES times per query against a
        // server that is not there. One attempt reaches the same "still no
        // metadata" verdict and keeps the test quick.
        DBMetadata.setMaxRetries(1);
        HikariDbSource.close();
    }

    @AfterEach
    public void tearDown() {
        DBMetadata.setMaxRetries(DEFAULT_MAX_RETRIES);
        HikariDbSource.close();
    }

    /**
     * Counts what the connector told Debezium about the batch. Both counts
     * staying at zero while persistRecords returns normally IS the data loss:
     * the engine is then free to advance past records never written.
     */
    private static final class CountingCommitter
            implements DebeziumEngine.RecordCommitter {

        private final AtomicInteger processed = new AtomicInteger();
        private final AtomicInteger batchesFinished = new AtomicInteger();

        @Override
        public void markProcessed(Object record) {
            this.processed.incrementAndGet();
        }

        @Override
        public void markBatchFinished() {
            this.batchesFinished.incrementAndGet();
        }

        @Override
        public void markProcessed(Object record, DebeziumEngine.Offsets offsets) {
            this.processed.incrementAndGet();
        }

        @Override
        public DebeziumEngine.Offsets buildOffsets() {
            return null;
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", String.valueOf(UNREACHABLE_PORT));
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        // The scenario in the issue: the user manages their own schemas, so a
        // table missing from ClickHouse is never created for them.
        props.put("auto.create.tables", "false");
        props.put("single.threaded", "true");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "1");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct record(String topic, long offset,
                                           CountingCommitter committer,
                                           boolean lastInBatch) {
        Struct row = new Struct(ROW_SCHEMA)
                .put("id", (int) offset)
                .put("name", "row" + offset);
        ClickHouseStruct chStruct = new ClickHouseStruct(offset, topic, null, 0,
                System.currentTimeMillis(), null, row, null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        chStruct.setDatabase("testdb");
        chStruct.setCommitter(committer);
        chStruct.setSourceRecord((ChangeEvent<SourceRecord, SourceRecord>) null);
        chStruct.setLastRecordInBatch(lastInBatch);
        return chStruct;
    }

    /**
     * The regression. A batch whose table is missing from ClickHouse must
     * raise {@link ClickHouseBatchWriter.BatchPersistenceException} out of
     * persistRecords. Before the fix persistRecords returned normally, which
     * let the engine treat the batch as handled and commit past it.
     */
    @Test
    public void missingTableFailsTheBatchInsteadOfDroppingIt() {
        ClickHouseBatchWriter writer =
                new ClickHouseBatchWriter(config(), new HashMap<>());
        CountingCommitter committer = new CountingCommitter();

        List<ClickHouseStruct> batch = new ArrayList<>();
        batch.add(record("SERVER5432.testdb.orders", 1L, committer, false));
        batch.add(record("SERVER5432.testdb.orders", 2L, committer, false));
        // A second table, ordered after the failing one: today these records
        // are never even attempted.
        batch.add(record("SERVER5432.testdb.customers", 3L, committer, true));

        RuntimeException thrown = null;
        try {
            writer.persistRecords(batch);
        } catch (RuntimeException e) {
            thrown = e;
        }

        // Not one record was acknowledged -- nothing reached ClickHouse.
        Assert.assertEquals("no record may be acknowledged when the batch was not written",
                0, committer.processed.get());
        Assert.assertEquals("the batch must not be marked finished when it was not written",
                0, committer.batchesFinished.get());

        // ... and because nothing was acknowledged, the ONLY thing that can
        // stop the engine from committing a later, higher offset over these
        // records is a failure reaching the caller.
        Assert.assertNotNull("persistRecords returned normally after failing to write the batch: "
                        + "the caller cannot tell the batch was dropped, so the offset advances "
                        + "past records that never reached ClickHouse (issue #1285)",
                thrown);
        Assert.assertTrue("the failure must be reported as a batch persistence failure, got: "
                        + thrown.getClass().getName(),
                thrown instanceof ClickHouseBatchWriter.BatchPersistenceException);
        Assert.assertTrue("the failure must name the topic it could not persist, got: "
                        + thrown.getMessage(),
                thrown.getMessage() != null
                        && thrown.getMessage().contains("SERVER5432.testdb."));
    }

    /**
     * An empty batch has nothing to fail on and must stay silent -- the fix
     * must not turn a no-op into a connector restart.
     */
    @Test
    public void emptyBatchIsNotAFailure() {
        ClickHouseBatchWriter writer =
                new ClickHouseBatchWriter(config(), new HashMap<>());

        writer.persistRecords(new ArrayList<>());
    }
}

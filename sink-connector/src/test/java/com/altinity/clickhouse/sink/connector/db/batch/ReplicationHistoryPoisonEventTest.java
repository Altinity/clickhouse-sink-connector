package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Poison events of the SCD2 write protocol (Spec 12.03 section 7, FM-12.03-5).
 *
 * <p>The history statements refuse a record deterministically -- a version that
 * cannot be encoded, a record without a primary key -- with an
 * {@link IllegalStateException}. {@code PreparedStatementExecutor} wraps it in a
 * {@link RuntimeException} and {@code ClickHouseBatchRunnable.run()} classifies it
 * with {@link ClickHouseErrorClassifier}: FATAL stops the worker and the engine
 * loudly, anything else retries the SAME batch forever with backoff
 * ({@code RetryBackoff} has no attempt cap). A refusal that can never succeed
 * must be FATAL.</p>
 */
public class ReplicationHistoryPoisonEventTest {

    /** The wrapping PreparedStatementExecutor applies before the batch runnable sees the failure. */
    private static RuntimeException asTheBatchRunnableSeesIt(Exception refusal) {
        return new RuntimeException(refusal);
    }

    private static ClickHouseStruct recordBeforeTheSnowflakeEpoch() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("embeddedconnector.shop.orders");
        record.setTs_ms(SnowFlakeId.SNOWFLAKE_EPOCH);
        record.setGtid(5L);
        record.calculateVersion(true);
        return record;
    }

    private static ClickHouseStruct updateWithoutPrimaryKey() {
        Schema schema = SchemaBuilder.struct().field("name", Schema.STRING_SCHEMA).build();
        Struct after = new Struct(schema).put("name", "x");
        ClickHouseStruct record = new ClickHouseStruct(0L, "embeddedconnector.shop.nokey", null, 0,
                System.currentTimeMillis(), null, after, null, ClickHouseConverter.CDC_OPERATION.UPDATE);
        record.setTs_ms(1790431200123L);
        record.setTsSec(1790431200L);
        record.setVersion(1790431200500000001L);
        return record;
    }

    /** The refusal itself is loud and names the cause (pins the current, correct part). */
    @Test
    public void unencodableHistoryVersionIsRefusedLoudly() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ReplicationHistoryHandler.historyVersion(recordBeforeTheSnowflakeEpoch()));
        assertEquals(true, refused.getMessage().contains("snowflake epoch"), refused.getMessage());
    }

    @Test
    @Disabled("DEFECT FM-12.03-5: a deterministic history refusal (IllegalStateException, no ClickHouse error "
            + "code) is classified UNKNOWN and the batch is retried forever instead of stopping the worker")
    public void unencodableHistoryVersionStopsTheWorker() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ReplicationHistoryHandler.historyVersion(recordBeforeTheSnowflakeEpoch()));
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(asTheBatchRunnableSeesIt(refused)));
    }

    @Test
    @Disabled("DEFECT FM-12.03-5: an UPDATE without a primary key in history mode is refused with "
            + "IllegalStateException, classified UNKNOWN and retried forever instead of stopping the worker")
    public void updateWithoutPrimaryKeyStopsTheWorker() {
        ReplicationHistoryHandler handler = new ReplicationHistoryHandler(new QueryFormatter(), null);
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> handler.buildUpdateQueryParams(updateWithoutPrimaryKey()));
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(asTheBatchRunnableSeesIt(refused)));
    }
}

package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Spec 08.01 section 6 / spec 08.05 section 6: a {@code DbWriter} whose
 * initialisation did not complete must not be served from the per-worker cache
 * for the rest of the process.
 *
 * <p>The {@code DbWriter} constructor runs four steps -- read the column map,
 * ensure the databases, resolve the engine (auto-creating the table when
 * enabled), resolve the engine columns and apply the
 * {@code requireReplacingMergeTreeColumns} guard -- inside one
 * {@code catch (Exception)} that only logs {@code "***** DBWriter error
 * initializing ****"}. The half-built writer is then cached under the table's
 * current invalidation version, and {@code getDbWriterForTable} returns it on
 * every later batch until a DDL moves that version. The retry path
 * ({@code processBatchRecords}) re-reads only the column map, engine and
 * sorting key ({@code updateColumnNameToDataTypeMap}); it never re-runs the
 * auto-create, the engine-column resolution or the guard. A transient
 * ClickHouse failure during the first build, or a target table that an
 * operator creates after the first batch arrived, therefore leaves the writer
 * without a version and delete column for the life of the process.</p>
 *
 * <p>The reproduction points the worker at a ClickHouse nobody listens on, the
 * same device as {@code ClickHouseBatchWriterMissingTableTest}.</p>
 */
public class DbWriterPartialInitCacheTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    @BeforeEach
    public void setUp() {
        DBMetadata.setMaxRetries(1);
        HikariDbSource.close();
        CacheInvalidationManager.getInstance().clearAll();
    }

    @AfterEach
    public void tearDown() {
        DBMetadata.setMaxRetries(10);
        HikariDbSource.close();
        CacheInvalidationManager.getInstance().clearAll();
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "1");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        props.put("auto.create.tables", "true");
        props.put("connection.pool.disable", "true");
        // No 5 s x n sleeps in BaseDbWriter.createDestinationDatabase.
        props.put("errors.max.retries", "0");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct record() {
        ClickHouseStruct chStruct = new ClickHouseStruct(1L, "SERVER5432.testdb.orders", null, 0,
                System.currentTimeMillis(), null, new Struct(ROW).put("id", 1), null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        chStruct.setDatabase("testdb");
        return chStruct;
    }

    @Test
    @Disabled("DEFECT FM-08.01-5: a DbWriter whose constructor failed part-way is cached and served until a DDL "
            + "bumps the table version; auto-create, engine-column resolution and the ReplacingMergeTree guard are "
            + "never retried, so a transient failure during the first build stalls the table (or writes it with "
            + "unresolved engine columns) until the process restarts")
    @DisplayName("a writer whose initialisation failed is rebuilt on the next lookup, not served from the cache")
    public void partiallyInitialisedWriterIsRebuilt() {
        ClickHouseBatchRunnable runnable =
                new ClickHouseBatchRunnable(new LinkedBlockingQueue<>(), config(), new HashMap<>());

        DbWriter first = runnable.getDbWriterForTable("SERVER5432.testdb.orders", "orders", "testdb",
                record(), null);
        assertFalse(first.wasTableMetaDataRetrieved(),
                "precondition: ClickHouse is unreachable, so the first build cannot complete");

        DbWriter second = runnable.getDbWriterForTable("SERVER5432.testdb.orders", "orders", "testdb",
                record(), null);
        assertNotSame(first, second, "the half-built writer was served from the cache: its auto-create, "
                + "engine-column resolution and requireReplacingMergeTreeColumns guard will never run again "
                + "in this process, whatever ClickHouse does next");
    }
}

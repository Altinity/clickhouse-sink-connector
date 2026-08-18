package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the lazy system-database connection.
 *
 * <p>Both executors used to open a ClickHouse system connection in their
 * constructor. That was tolerable while BaseDbWriter.createConnection returned
 * null on failure, but once it was changed to throw, merely CONSTRUCTING either
 * class required a reachable ClickHouse — including for pure string operations
 * such as {@code getTableFromTopic}, which touch no database at all.</p>
 *
 * <p>These tests construct both executors against a deliberately unreachable
 * host and exercise a database-free method. They fail if the eager connection
 * is ever reintroduced.</p>
 */
public class LazySystemConnectionTest {

    private static ClickHouseSinkConnectorConfig unreachableConfig() {
        Map<String, String> props = new HashMap<>();
        // Port 1 is reserved and never listening, so any connection attempt
        // fails fast rather than hanging on a real host.
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "1");
        props.put("clickhouse.server.user", "nobody");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "system");
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("ClickHouseBatchRunnable constructs without a reachable ClickHouse")
    public void batchRunnableDoesNotConnectEagerly() {
        LinkedBlockingQueue<List<ClickHouseStruct>> records =
                new LinkedBlockingQueue<>();
        ClickHouseBatchRunnable runnable = assertDoesNotThrow(
                () -> new ClickHouseBatchRunnable(
                        records, unreachableConfig(), new HashMap<>()),
                "Constructing the runnable must not require a live ClickHouse");

        // A pure string operation must work with no database whatsoever.
        assertEquals("customers",
                runnable.getTableFromTopic("SERVER5432.test.customers"));
    }

    @Test
    @DisplayName("ClickHouseBatchWriter constructs without a reachable ClickHouse")
    public void batchWriterDoesNotConnectEagerly() {
        ClickHouseBatchWriter writer = assertDoesNotThrow(
                () -> new ClickHouseBatchWriter(
                        unreachableConfig(), new HashMap<>()),
                "Constructing the writer must not require a live ClickHouse");

        assertEquals("customers",
                writer.getTableFromTopic("SERVER5432.test.customers"));
    }
}

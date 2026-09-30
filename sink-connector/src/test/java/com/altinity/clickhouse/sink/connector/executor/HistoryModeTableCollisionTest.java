package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Routing failure mode of the history modes (Spec 12.01 section 7, FM-12.01-3).
 *
 * <p>With {@code replication.history.enable=true} every data table is routed to
 * the single history database and keeps its bare source table name
 * ({@code resolveDatabaseName} returns {@code replication.history.database.name}
 * before any prefix or override map). Two captured source databases that hold a
 * table of the same name -- sharded schemas {@code shop1.orders},
 * {@code shop2.orders} -- therefore resolve to ONE target table.</p>
 */
public class HistoryModeTableCollisionTest {

    private static ClickHouseBatchWriter historyWriter() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "8123");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "default");
        props.put("replication.history.enable", "true");
        props.put("replication.history.database.name", "binlog_history");
        return new ClickHouseBatchWriter(new ClickHouseSinkConnectorConfig(props), new HashMap<>());
    }

    private static String target(ClickHouseBatchWriter writer, String database, String table) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setDatabase(database);
        String topic = "server." + database + "." + table;
        return writer.resolveDatabaseName(topic, record) + "." + writer.getTableFromTopic(topic);
    }

    /** Pins today's routing: both source tables land in binlog_history.orders. */
    @Test
    public void everySourceDatabaseIsRoutedToTheOneHistoryDatabase() {
        ClickHouseBatchWriter writer = historyWriter();
        assertEquals("binlog_history.orders", target(writer, "shop1", "orders"));
        assertEquals("binlog_history.orders", target(writer, "shop2", "orders"));
    }

    @Test
    @Disabled("DEFECT FM-12.01-3: in history mode two source databases with a same-named table write into one "
            + "SCD2 table; rows and open-row keys of different source tables mix silently")
    public void sameNamedTablesOfTwoSourceDatabasesGetDistinctTargets() {
        ClickHouseBatchWriter writer = historyWriter();
        assertNotEquals(target(writer, "shop1", "orders"), target(writer, "shop2", "orders"));
    }
}

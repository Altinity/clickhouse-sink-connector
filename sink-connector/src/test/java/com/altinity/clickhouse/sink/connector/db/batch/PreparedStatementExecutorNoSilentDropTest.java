package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.01 section 3.3: the executor never answers {@code false} for a batch
 * that grouped into nothing. {@code false} made the worker keep the batch and
 * retry it on every tick, forever, for a batch that could never produce a
 * statement.
 */
public class PreparedStatementExecutorNoSilentDropTest {

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("An empty query map is refused loudly, never reported as 'not written'")
    public void emptyQueryMapIsRefusedNotRetried() {
        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> empty = new HashMap<>();
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        RecordingJdbc jdbc = new RecordingJdbc();
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                null, false, null, null, "db", ZoneId.of("UTC"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                executor.addToPreparedStatementBatch("topic", empty, new BlockMetaData(), config(),
                        jdbc.connection(), "orders", columns, DBMetadata.TABLE_ENGINE.MERGE_TREE),
                "a batch grouped into nothing is a defect to surface, not a transient to retry");
        assertTrue(e.getMessage().contains("orders"), e.getMessage());
        assertTrue(jdbc.events.isEmpty(), "nothing may be sent: " + jdbc.events);
    }
}

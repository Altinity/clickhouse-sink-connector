package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.BlockMetaData;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 05.02 section 6, FM-05.02-3 (and spec 03.03 section 6, FM-03.03-1):
 * the connector's own refusals are deterministic, but they carry no
 * {@code Code: NNN}, so {@code ClickHouseErrorClassifier} files them under
 * UNKNOWN and {@code ClickHouseBatchRunnable.run} retries the same batch
 * forever -- although their messages say the batch "cannot be replicated" or
 * is failed "instead of retrying it forever". Each refusal is produced by the
 * real code path and wrapped exactly as {@code executePreparedStatement}
 * wraps it ({@code new RuntimeException(e)}).
 */
public class ConnectorRefusalClassificationTest {

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    @Test
    @Disabled("DEFECT FM-05.02-3: connector refusals (IllegalStateException without an error code) classify as "
            + "UNKNOWN and are retried forever instead of stopping the worker loudly")
    @DisplayName("A tombstone refused for a table without a delete column is FATAL, not retried forever")
    public void aRefusedTombstoneIsFatal() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("id", "Int32");
        columns.put("_version", "UInt64");
        PreparedStatementFieldMapper mapper = new PreparedStatementFieldMapper(
                "is_deleted", true, null, "_version", "db", ZoneId.of("UTC"));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () ->
                mapper.requireDeleteColumn(config(), columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "t",
                        "The tombstone of an UPDATE that moves the row to another sorting key"));

        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(new RuntimeException(refusal)),
                "the table, the column and the remediation are unchanged on every attempt: " + refusal.getMessage());
    }

    @Test
    @Disabled("DEFECT FM-05.02-3: connector refusals (IllegalStateException without an error code) classify as "
            + "UNKNOWN and are retried forever instead of stopping the worker loudly")
    @DisplayName("A batch that grouped into no statement is FATAL, as its own message promises")
    public void aBatchGroupedIntoNothingIsFatal() {
        PreparedStatementExecutor executor = new PreparedStatementExecutor(
                "is_deleted", true, null, "_version", "db", ZoneId.of("UTC"));
        IllegalStateException refusal = assertThrows(IllegalStateException.class, () ->
                executor.addToPreparedStatementBatch("topic", new ArrayList<>(), new BlockMetaData(), config(),
                        null, "t", new HashMap<>(), DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE));

        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(refusal),
                "'Failing loudly instead of retrying it forever' must not be retried forever: " + refusal.getMessage());
    }
}

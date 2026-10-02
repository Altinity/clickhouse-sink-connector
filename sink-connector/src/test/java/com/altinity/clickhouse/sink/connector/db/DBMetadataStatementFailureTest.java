package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.db.batch.RecordingJdbc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 04.05 section 3 step 2: a statement ClickHouse keeps refusing is a
 * failure of the batch, never a silent no-op.
 *
 * <p>{@code truncateTable} retried {@code MAX_RETRIES} times and then returned
 * normally, so the caller treated the replicated TRUNCATE as applied: the
 * batch continued, reported success and was acknowledged while the
 * pre-truncate rows survived in ClickHouse. {@code getPreparedStatement}
 * returned {@code null} after its retries, which the caller could only
 * dereference into a {@code NullPointerException} far from the cause.</p>
 */
public class DBMetadataStatementFailureTest {

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    @Test
    @DisplayName("truncateTable rethrows after every retry failed instead of returning as if applied")
    public void truncateFailureIsRethrownAfterRetries() {
        RecordingJdbc jdbc = new RecordingJdbc();
        // DESTRUCTIVE: a marker string only -- makes the recording connection
        // REFUSE every TRUNCATE it is asked to prepare; nothing is executed.
        jdbc.failPrepareContaining = "TRUNCATE TABLE";

        // DESTRUCTIVE: exercises the refusal path only -- the recording
        // connection throws on prepare, so no table is ever truncated.
        SQLException e = assertThrows(SQLException.class,
                () -> new DBMetadata(config()).truncateTable(jdbc.connection(), "db1", "orders"),
                "a TRUNCATE ClickHouse keeps refusing must fail the batch, not be reported as applied");

        assertEquals(DBMetadata.MAX_RETRIES, jdbc.prepareFailures, "every retry must have been attempted");
        assertNotNull(e.getCause(), "the last refusal is the cause");
        assertTrue(e.getMessage().contains("`db1`.`orders`"), e.getMessage());
        assertTrue(e.getMessage().contains("NOT applied"), e.getMessage());
        assertTrue(jdbc.ofKind(RecordingJdbc.EXECUTE).isEmpty(), "nothing was executed: " + jdbc.events);
    }

    @Test
    @DisplayName("getPreparedStatement throws after every retry failed instead of returning null")
    public void preparedStatementFailureIsRethrownNotNull() {
        RecordingJdbc jdbc = new RecordingJdbc();
        jdbc.failPrepareContaining = "INSERT INTO";

        SQLException e = assertThrows(SQLException.class,
                () -> new DBMetadata(config()).getPreparedStatement(jdbc.connection(),
                        "INSERT INTO `orders`(`id`) VALUES (?)"),
                "a null statement is a NullPointerException waiting far from the cause");

        assertEquals(DBMetadata.MAX_RETRIES, jdbc.prepareFailures);
        assertNotNull(e.getCause());
        assertTrue(e.getMessage().contains("INSERT INTO `orders`"), e.getMessage());
    }
}

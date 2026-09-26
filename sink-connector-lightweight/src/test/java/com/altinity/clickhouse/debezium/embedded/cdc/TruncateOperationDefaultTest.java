package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.Assert.assertEquals;

/** From Debezium 3.3.0 a TRUNCATE no longer reaches the DDL path, so the default skip ends truncate replication. */
public class TruncateOperationDefaultTest {

    @Test
    @DisplayName("Truncate operations are not skipped unless the user says so")
    public void truncateIsNotSkippedByDefault() {
        Properties props = new Properties();

        DebeziumChangeEventCapture.keepTruncateOperations(props);

        assertEquals("truncate must keep flowing, otherwise TRUNCATE TABLE stops replicating",
                "none", props.getProperty("skipped.operations"));
    }

    @Test
    @DisplayName("An explicit skipped.operations from the user is left alone")
    public void userValueWins() {
        Properties props = new Properties();
        props.setProperty("skipped.operations", "d");

        DebeziumChangeEventCapture.keepTruncateOperations(props);

        assertEquals("d", props.getProperty("skipped.operations"));
    }
}

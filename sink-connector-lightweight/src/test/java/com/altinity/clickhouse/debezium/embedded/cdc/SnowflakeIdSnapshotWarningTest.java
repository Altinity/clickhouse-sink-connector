package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * {@code snowflake.id=false} versions GTID-bearing rows with the raw transaction
 * number (order 1e6-1e10) while snapshot rows take the sequence path (order
 * 1.7e18), so after a data snapshot every streamed change of a snapshotted key
 * loses to the snapshot row, permanently (spec 02.06 §3.2.1). Under Invariant
 * I11 an existing configuration may not be refused on upgrade, so the engine
 * logs the combination at ERROR instead of failing.
 */
public class SnowflakeIdSnapshotWarningTest {

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-snowflake-snapshot-warning", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private static ClickHouseSinkConnectorConfig config(boolean snowflakeId) {
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        props.put(ClickHouseSinkConnectorConfigVariables.SNOWFLAKE_ID.toString(), String.valueOf(snowflakeId));
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static Properties snapshotMode(String mode) {
        Properties props = new Properties();
        if (mode != null) {
            props.setProperty("snapshot.mode", mode);
        }
        return props;
    }

    private static List<String> errorsLoggedBy(Runnable action) {
        Logger coreLogger = (Logger) LogManager.getLogger(DebeziumChangeEventCapture.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }
        return appender.events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.ERROR))
                .map(e -> e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("snowflake.id=false with a data snapshot (explicit or the unset default) is detected and logged at ERROR")
    public void rawGtidVersioningWithDataSnapshotIsLoud() {
        for (String mode : Arrays.asList("initial", "initial_only", "always", "when_needed", null)) {
            Properties props = snapshotMode(mode);
            assertTrue(DebeziumChangeEventCapture.rawGtidVersioningWithDataSnapshot(props, config(false)),
                    "snapshot.mode=" + mode + " reads data: raw-GTID versioning loses every streamed change of "
                            + "a snapshotted key");
            List<String> errors = errorsLoggedBy(
                    () -> DebeziumChangeEventCapture.warnIfRawGtidVersioningWithDataSnapshot(props, config(false)));
            assertEquals(1, errors.size(), "exactly one ERROR for snapshot.mode=" + mode + ": " + errors);
            assertTrue(errors.get(0).contains("snowflake.id") && errors.get(0).contains("snapshot.mode"),
                    "the message names both keys: " + errors.get(0));
        }
    }

    @Test
    @DisplayName("no-data snapshot modes, and snowflake.id=true, are silent")
    public void noDataSnapshotModesAreSilent() {
        for (String mode : Arrays.asList("never", "no_data", "schema_only", "recovery", "schema_only_recovery")) {
            Properties props = snapshotMode(mode);
            assertFalse(DebeziumChangeEventCapture.rawGtidVersioningWithDataSnapshot(props, config(false)),
                    "snapshot.mode=" + mode + " reads no data rows");
            assertTrue(errorsLoggedBy(
                    () -> DebeziumChangeEventCapture.warnIfRawGtidVersioningWithDataSnapshot(props, config(false)))
                    .isEmpty(), "no ERROR for snapshot.mode=" + mode);
        }
        assertFalse(DebeziumChangeEventCapture.rawGtidVersioningWithDataSnapshot(snapshotMode("initial"), config(true)),
                "the default snowflake.id=true keeps snapshot and streamed rows in one domain");
        assertTrue(errorsLoggedBy(
                () -> DebeziumChangeEventCapture.warnIfRawGtidVersioningWithDataSnapshot(snapshotMode("initial"), config(true)))
                .isEmpty());
    }
}

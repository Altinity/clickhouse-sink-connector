package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the interaction between DISABLE_DROP_TRUNCATE and snapshot DDL.
 *
 * <p>During a snapshot Debezium replays schema bootstrap as a
 * drop-and-recreate pair for every captured table. If the drop half is
 * suppressed by the DISABLE_DROP_TRUNCATE guard while the CREATE half executes, a
 * pre-existing destination table makes the CREATE fail with
 * TABLE_ALREADY_EXISTS and the DDL stream stalls in a retry loop. The
 * guard therefore only applies to STREAMING DDL — an accidental drop on the
 * source mid-replication — and must exempt snapshot DDL, whose delivery is
 * governed by ENABLE_SNAPSHOT_DDL instead.</p>
 */
@DisplayName("DISABLE_DROP_TRUNCATE must exempt snapshot-phase DDL")
public class DropTruncateSnapshotExemptionTest {

    // DESTRUCTIVE: inert test fixture. This string is only classified by
    // checkIfDDLNeedsToBeIgnored; no database connection exists in this test
    // and nothing is executed — blast radius is zero.
    private static final String DROP_DDL =
            "DROP TABLE IF EXISTS `employees`.`temporal_types_DATETIME`";

    private static boolean checkIgnored(Properties props, SourceRecord sr,
                                        boolean isDropOrTruncate) throws Exception {
        DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
        Method m = DebeziumChangeEventCapture.class.getDeclaredMethod(
                "checkIfDDLNeedsToBeIgnored",
                String.class, Properties.class, SourceRecord.class, AtomicBoolean.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(capture, DROP_DDL, props,
                sr, new AtomicBoolean(isDropOrTruncate));
    }

    private static SourceRecord recordWithOffset(Map<String, ?> sourceOffset) {
        return new SourceRecord(Collections.emptyMap(), sourceOffset,
                "test-topic", Schema.STRING_SCHEMA, DROP_DDL);
    }

    @Test
    @DisplayName("snapshot DROP passes through when snapshot DDL is enabled")
    public void snapshotDropIsNotBlockedByDropTruncateGuard() throws Exception {
        Properties props = new Properties();
        props.setProperty("disable.drop.truncate", "true");
        props.setProperty("enable.snapshot.ddl", "true");

        SourceRecord snapshotRecord =
                recordWithOffset(Collections.singletonMap("snapshot", "INITIAL"));

        assertFalse(checkIgnored(props, snapshotRecord, true),
                "a snapshot-phase DROP must not be suppressed: its paired "
                        + "CREATE TABLE would then hit TABLE_ALREADY_EXISTS "
                        + "against a pre-existing destination table");
    }

    @Test
    @DisplayName("streaming DROP is still blocked by the guard")
    public void streamingDropRemainsBlocked() throws Exception {
        Properties props = new Properties();
        props.setProperty("disable.drop.truncate", "true");
        props.setProperty("enable.snapshot.ddl", "true");

        SourceRecord streamingRecord = recordWithOffset(Collections.emptyMap());

        assertTrue(checkIgnored(props, streamingRecord, true),
                "a streaming DROP must still be suppressed when the operator "
                        + "asked for DISABLE_DROP_TRUNCATE protection");
    }

    @Test
    @DisplayName("snapshot DROP is still ignored when snapshot DDL is disabled")
    public void snapshotDropStillIgnoredWhenSnapshotDdlDisabled() throws Exception {
        Properties props = new Properties();
        props.setProperty("disable.drop.truncate", "true");
        props.setProperty("enable.snapshot.ddl", "false");

        SourceRecord snapshotRecord =
                recordWithOffset(Collections.singletonMap("snapshot", "INITIAL"));

        assertTrue(checkIgnored(props, snapshotRecord, true),
                "with snapshot DDL disabled, snapshot statements are ignored "
                        + "by the ENABLE_SNAPSHOT_DDL check regardless of the "
                        + "DISABLE_DROP_TRUNCATE guard");
    }

    @Test
    @DisplayName("streaming non-destructive DDL is unaffected by the guard")
    public void streamingNonDestructiveDdlUnaffected() throws Exception {
        Properties props = new Properties();
        props.setProperty("disable.drop.truncate", "true");
        props.setProperty("enable.snapshot.ddl", "true");

        SourceRecord streamingRecord = recordWithOffset(Collections.emptyMap());

        assertFalse(checkIgnored(props, streamingRecord, false),
                "non-destructive streaming DDL must never be suppressed by "
                        + "the DISABLE_DROP_TRUNCATE guard");
    }
}

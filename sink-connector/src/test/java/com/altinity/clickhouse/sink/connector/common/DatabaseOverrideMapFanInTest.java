package com.altinity.clickhouse.sink.connector.common;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 04.05 FM-04.05-3: {@code clickhouse.database.override.map} routes a
 * source database to a target database. Two source databases routed to ONE
 * target database make every same-named table share one ClickHouse table, so
 * a replicated TRUNCATE of either source table (spec 04.05 section 3.2 step 1
 * truncates the resolved target) empties the other source's rows as well --
 * a destructive loss no later event repairs. The parser refuses a duplicated
 * source name but accepts a duplicated destination.
 */
public class DatabaseOverrideMapFanInTest {

    /** A one-to-one map is accepted (pins the correct half). */
    @Test
    @DisplayName("FM-04.05-3: a one-to-one override map is accepted")
    public void oneToOneMapIsAccepted() throws Exception {
        Map<String, String> map = Utils.parseSourceToDestinationDatabaseMap("shard1:app1,shard2:app2");
        assertEquals("app1", map.get("shard1"));
        assertEquals("app2", map.get("shard2"));
    }

    @Test
    // DESTRUCTIVE: message text only -- names the replicated truncation hazard; nothing is executed here.
    @Disabled("DEFECT FM-04.05-3: two source databases mapped to one target database are accepted, so a "
            + "replicated TRUNCATE of one source table destroys the other source's replica rows")
    @DisplayName("FM-04.05-3: two source databases mapped to one target database are refused")
    public void fanInToOneTargetDatabaseIsRefused() {
        assertThrows(Exception.class, () -> {
            Map<String, String> map = Utils.parseSourceToDestinationDatabaseMap("shard1:app,shard2:app");
            if (map == null) {
                throw new IllegalArgumentException("refused");
            }
        });
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

public class DebeziumOffsetManagementTest {

    // Test function to validate the isWithinRange function
    @Test
    public void testIsWithinRange() {
        // Create batch timestamps list.
        List<Pair<Long, Long>> batchTimestamps = new ArrayList();

        batchTimestamps.add(Pair.of(1L, 2L));
        batchTimestamps.add(Pair.of(3L, 4L));
        batchTimestamps.add(Pair.of(5L, 6L));
        batchTimestamps.add(Pair.of(7L, 8L));

        // Test case 1
        DebeziumOffsetManagement dom = new DebeziumOffsetManagement(batchTimestamps);
        assert(dom.isWithinRange(Pair.of(10L, 12L)) == false);

        // Test case 2
        dom = new DebeziumOffsetManagement(batchTimestamps);

        dom.isWithinRange(Pair.of(1L, 2L));
        assert(dom.isWithinRange(Pair.of(1L, 2L)) == true);

    }

}

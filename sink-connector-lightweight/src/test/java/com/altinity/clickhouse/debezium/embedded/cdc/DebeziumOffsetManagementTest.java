package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

public class DebeziumOffsetManagementTest {

    // Test function to validate the isWithinRange function
    @Test
    public void testIsWithinRange() {
        // Test case 1
        DebeziumOffsetManagement dom = new DebeziumOffsetManagement(null);
        assert(dom.isWithinRange(null) == false);

        // Test case 2
        dom = new DebeziumOffsetManagement(null);

        dom.isWithinRange(Pair.of(1L, 2L));
        assert(dom.isWithinRange(Pair.of(1L, 2L)) == false);

    }

}

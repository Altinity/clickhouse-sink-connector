package com.altinity.clickhouse.sink.connector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The lightweight connector's sequence number must decide {@code _version} even when the
 * MySQL source runs with GTIDs: the snowflake id lives in another numeric domain and is not
 * monotonic in binlog order.
 */
public class SequenceNumberBeatsGtidVersionTest {

    @Test
    public void sequenceNumberWinsOverGtidWithSnowflake() {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTs_ms(1790967640123L);
        s.setGtid(123650L);
        s.setSequenceNumber(1790967640123L * 1_000_000L + 1_000_000_042L);
        s.calculateVersion(true);
        assertEquals(1790967640123L * 1_000_000L + 1_000_000_042L, s.getVersion());
    }

    @Test
    public void sequenceNumberWinsOverRawGtid() {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTs_ms(1790967640123L);
        s.setGtid(123650L);
        s.setSequenceNumber(42L);
        s.calculateVersion(false);
        assertEquals(42L, s.getVersion());
    }

    @Test
    public void gtidStillUsedWithoutSequenceNumber() {
        ClickHouseStruct s = new ClickHouseStruct();
        s.setTs_ms(1790967640123L);
        s.setGtid(123650L);
        s.calculateVersion(false);
        assertEquals(123650L, s.getVersion());
    }
}

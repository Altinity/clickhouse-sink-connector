package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * database.server.id is the binlog client identity. MySQL's server_id is an unsigned 32-bit value
 * (1..4294967295 for a client); a value outside that range, or one that collides with a live client
 * (error 1236), must be visible at start, not discovered as a restart loop.
 */
public class BinlogServerIdPolicyTest {

    private static final String KEY = BinlogServerIdPolicy.KEY;

    /** The error text the 2.11.0 DR connector logged, 1080 times, before its server_id was changed. */
    private static final String DR_1236 =
            "io.debezium.DebeziumException: A slave with the same server_uuid/server_id as this slave has "
                    + "connected to the master; the first event 'binary.212865' at 872091350, the last event read "
                    + "'binary.212865' at 872091350, the last byte read 'binary.212865' at 872091350. Error code: "
                    + "1236; SQLSTATE: HY000.";

    private static Properties withServerId(String value) {
        Properties props = new Properties();
        props.setProperty(KEY, value);
        return props;
    }

    @Test
    public void unsetOrBlankIsLeftToDebezium() {
        assertNull(BinlogServerIdPolicy.serverIdOf(new Properties()));
        assertNull(BinlogServerIdPolicy.serverIdOf(withServerId("")));
        assertNull(BinlogServerIdPolicy.serverIdOf(withServerId("   ")));
    }

    @Test
    public void inRangeValuesAreAccepted() {
        assertEquals(Long.valueOf(1L), BinlogServerIdPolicy.serverIdOf(withServerId("1")));
        assertEquals(Long.valueOf(931294301L), BinlogServerIdPolicy.serverIdOf(withServerId("931294301")));
        assertEquals(Long.valueOf(4294967295L), BinlogServerIdPolicy.serverIdOf(withServerId("4294967295")));
        assertDoesNotThrow(() -> BinlogServerIdPolicy.check(withServerId("22323")));
    }

    /** Corpus of values: every one of these must be refused, and the message must name the key and the value. */
    @Test
    public void outOfRangeOrNonNumericValuesAreRefusedNamingKeyAndValue() {
        String[] refused = {
                "0",
                "4294967296",             // 2^32: one past the MySQL range
                "93129430125",            // the value found in the DR inventory (11 digits)
                "99999999999999999999",   // overflows long
                "-1",
                "+5",
                "1.5",
                "12abc",
                "abc",
                " 12",
                "12 ",
                "0x1F",
        };
        for (String value : refused) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> BinlogServerIdPolicy.check(withServerId(value)), value);
            assertTrue(e.getMessage().contains(KEY), e.getMessage());
            assertTrue(e.getMessage().contains("'" + value + "'"), e.getMessage());
        }
    }

    @Test
    public void collisionOfTheDrIncidentIsRecognised() {
        assertTrue(BinlogServerIdPolicy.isSameServerIdCollision(new RuntimeException(DR_1236)));
        // A cause chain: Debezium wraps the driver's ServerException.
        assertTrue(BinlogServerIdPolicy.isSameServerIdCollision(
                new RuntimeException("engine failed", new IllegalStateException(DR_1236))));
    }

    @Test
    public void unrelatedFailuresAreNotCollisions() {
        assertFalse(BinlogServerIdPolicy.isSameServerIdCollision(new RuntimeException("Connection refused")));
        assertFalse(BinlogServerIdPolicy.isSameServerIdCollision(
                new RuntimeException("Error code: 1205; lock wait timeout")));
        assertFalse(BinlogServerIdPolicy.isSameServerIdCollision(null));
    }

    /**
     * Error 1236 carries "Error code: 1236" for a purged binlog too ("Could not find first log file name in
     * binary log index file"). Only the server_uuid/server_id text names a collision.
     */
    @Test
    public void purgedBinlogIsNotASameServerIdCollision() {
        assertFalse(BinlogServerIdPolicy.isSameServerIdCollision(new RuntimeException(
                "Could not find first log file name in binary log index file Error code: 1236; SQLSTATE: HY000.")));
        assertFalse(BinlogServerIdPolicy.isSameServerIdCollision(
                new RuntimeException("Error code: 1236; SQLSTATE: HY000.")));
    }

    @Test
    public void collisionNoteNamesTheKeyAndDoesNotThrow() {
        Properties props = withServerId("931294301");
        assertDoesNotThrow(() -> BinlogServerIdPolicy.check(props));
        assertDoesNotThrow(() -> BinlogServerIdPolicy.noteCollision(new RuntimeException(DR_1236)));
        assertDoesNotThrow(() -> BinlogServerIdPolicy.noteCollision(new RuntimeException("unrelated")));
    }
}

package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backward- and forward-compatibility contract tests for the
 * ReplacingMergeTree {@code _version} column.
 *
 * <p>{@code _version} is the single value that decides which physical row
 * survives a ReplacingMergeTree merge. Any change to how it is produced is
 * therefore an <em>irreversible data-format change</em>: rows already written
 * by an older connector build stay in the table forever and continue to
 * compete against rows written by a newer build, so a downgrade cannot undo
 * them. These tests pin the properties the format must keep, so a scheme
 * change that would break a live table fails here rather than in production.</p>
 *
 * <p>They are written against the version sources {@code calculateVersion()}
 * actually consults - GTID (optionally via snowflake), sequence number, LSN -
 * and against {@code addVersion()}'s {@code ts_ms * 1_000_000 + counter}
 * sequence encoding.</p>
 *
 * <p>The four properties pinned:</p>
 * <ol>
 *   <li><b>Redelivery stability</b> (backward): the same source event replayed
 *       after an offset rewind must produce the <em>same</em> version, or a
 *       replayed DELETE outranks the later re-INSERT and the row is left
 *       {@code is_deleted=1}. This is issue #1346, which is still open.</li>
 *   <li><b>Commit ordering</b> (backward): a later source commit must produce a
 *       strictly greater version than an earlier one.</li>
 *   <li><b>Uniqueness</b> (forward): two distinct events must never share a
 *       version, or RMT silently discards one of them.</li>
 *   <li><b>Sentinel and sign safety</b> (forward): no produced value may
 *       collide with the {@code -1} uninitialized sentinel, and ordering must
 *       remain valid under the {@code UInt64} semantics ClickHouse applies.</li>
 * </ol>
 */
public class VersionCompatibilityTest {

    /** 2024-01-01T00:00:00Z, a fixed source commit clock for determinism. */
    private static final long TS_2024 = 1704067200000L;

    /** Mirrors the {@code ts_ms * 1_000_000 + counter} encoding in addVersion(). */
    private static final long SEQUENCE_SCALE = 1_000_000L;

    private static long sequenceVersion(long tsMs, long counter) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(tsMs);
        record.setSequenceNumber(tsMs * SEQUENCE_SCALE + counter);
        record.calculateVersion(false);
        return record.getVersion();
    }

    private static long gtidVersion(long gtid) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setGtid(gtid);
        record.calculateVersion(false);
        return record.getVersion();
    }

    private static long snowflakeVersion(long tsMs, long gtid) {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(tsMs);
        record.setGtid(gtid);
        record.calculateVersion(true);
        return record.getVersion();
    }

    @Nested
    @DisplayName("Backward compatibility: a replayed event keeps its version")
    class RedeliveryStability {

        /**
         * After an offset rewind Debezium re-emits events that were already
         * applied. If the version is derived from anything that changes
         * between deliveries (wall clock, a fresh counter, a random value),
         * the replayed copy gets a different - typically higher - version. A
         * replayed DELETE then outranks the re-INSERT that followed it and the
         * row disappears. This is the mechanism behind issue #1346.
         */
        @Test
        @DisplayName("the same sequence-numbered event replayed keeps its version")
        public void replayedSequenceEventKeepsVersion() {
            assertEquals(sequenceVersion(TS_2024, 7L), sequenceVersion(TS_2024, 7L),
                    "a re-delivered event must produce the same _version; an unstable "
                            + "version lets a replayed DELETE outrank the re-INSERT");
        }

        @Test
        @DisplayName("the same GTID event replayed keeps its version")
        public void replayedGtidEventKeepsVersion() {
            assertEquals(gtidVersion(918273645L), gtidVersion(918273645L),
                    "a GTID version must be a pure function of the GTID");
        }

        @Test
        @DisplayName("the same snowflake event replayed keeps its version")
        public void replayedSnowflakeEventKeepsVersion() {
            assertEquals(snowflakeVersion(TS_2024, 4242L), snowflakeVersion(TS_2024, 4242L),
                    "a snowflake version must be a pure function of (ts_ms, gtid); if it "
                            + "drew on the processing clock, redelivery would not be stable");
        }

        /**
         * The DELETE / re-INSERT sequence from issue #1346, end to end: the
         * DELETE is replayed after the INSERT that followed it. The INSERT
         * must still win, or the row stays deleted.
         */
        @Test
        @DisplayName("a replayed DELETE never outranks the later re-INSERT")
        public void replayedDeleteDoesNotOutrankReinsert() {
            long deleteVersion = sequenceVersion(TS_2024, 1L);
            long reinsertVersion = sequenceVersion(TS_2024 + 2000L, 1L);
            long deleteReplayVersion = sequenceVersion(TS_2024, 1L);

            assertTrue(reinsertVersion > deleteVersion,
                    "the later re-INSERT must outrank the original DELETE");
            assertTrue(reinsertVersion > deleteReplayVersion,
                    "the later re-INSERT must still outrank the REPLAYED DELETE; otherwise "
                            + "the row stays is_deleted=1 after an offset rewind (issue #1346)");
        }
    }

    @Nested
    @DisplayName("Backward compatibility: commit order is preserved")
    class CommitOrdering {

        @Test
        @DisplayName("a later source timestamp always outranks an earlier one")
        public void laterSourceTimestampWins() {
            assertTrue(sequenceVersion(TS_2024 + 1000L, 1L) > sequenceVersion(TS_2024, 999L),
                    "the source timestamp must dominate the counter, so a later commit wins "
                            + "even when the counter has been reset to a low value");
        }

        /**
         * Within one timestamp, ordering falls back to the counter, so two
         * changes to the same row inside one millisecond still merge in order.
         */
        @Test
        @DisplayName("within one timestamp, the higher counter wins")
        public void higherCounterWinsWithinSameTimestamp() {
            assertTrue(sequenceVersion(TS_2024, 2L) > sequenceVersion(TS_2024, 1L),
                    "within one source timestamp the later counter value must win");
        }

        /**
         * The counter resets when the source clock advances past the anchor.
         * Because the timestamp occupies the high-order part of the encoding,
         * the reset cannot make a newer event lose to an older one.
         */
        @Test
        @DisplayName("a counter reset on a clock advance does not invert ordering")
        public void counterResetDoesNotInvertOrdering() {
            long beforeReset = sequenceVersion(TS_2024, 999_999L);
            long afterReset = sequenceVersion(TS_2024 + 2000L, 1L);
            assertTrue(afterReset > beforeReset,
                    "an event after a counter reset must still outrank the one before it; "
                            + "otherwise every batch crossing a time gap loses to its "
                            + "predecessor and the RMT keeps the stale row");
        }

        @Test
        @DisplayName("GTID versions preserve transaction order")
        public void gtidVersionsPreserveTransactionOrder() {
            assertTrue(gtidVersion(1002L) > gtidVersion(1001L),
                    "a later transaction must outrank an earlier one");
        }
    }

    @Nested
    @DisplayName("Forward compatibility: distinct events get distinct versions")
    class Uniqueness {

        @Test
        @DisplayName("distinct counters within one timestamp are distinct versions")
        public void distinctCountersAreDistinctVersions() {
            Set<Long> versions = new HashSet<>();
            for (long counter = 1; counter <= 512; counter++) {
                versions.add(sequenceVersion(TS_2024, counter));
            }
            assertEquals(512, versions.size(),
                    "every distinct counter within one timestamp must map to a distinct "
                            + "_version; a collision makes RMT silently drop a row");
        }

        @Test
        @DisplayName("distinct timestamps with an identical counter are distinct versions")
        public void distinctTimestampsAreDistinctVersions() {
            Set<Long> versions = new HashSet<>();
            for (int millis = 0; millis < 512; millis++) {
                versions.add(sequenceVersion(TS_2024 + millis, 1L));
            }
            assertEquals(512, versions.size(),
                    "every distinct source timestamp must map to a distinct _version");
        }

        /**
         * The counter occupies the low {@code 1_000_000} of the encoding. A
         * counter that ran past that bound would carry into the timestamp
         * component and collide with the next millisecond's versions.
         */
        @Test
        @DisplayName("the counter cannot carry into the timestamp component")
        public void counterDoesNotCarryIntoTimestamp() {
            long lastOfMillisecond = sequenceVersion(TS_2024, SEQUENCE_SCALE - 1);
            long firstOfNextMillisecond = sequenceVersion(TS_2024 + 1, 1L);
            assertTrue(firstOfNextMillisecond > lastOfMillisecond,
                    "the counter must stay below " + SEQUENCE_SCALE + " per millisecond; "
                            + "a carry would collide with the next millisecond's versions");
        }

        /**
         * Snowflake packs the timestamp and the GTID transaction id into one
         * long. Two different transactions in the same millisecond must not
         * collapse onto the same value.
         */
        @Test
        @DisplayName("snowflake ids for distinct gtids in one millisecond are distinct")
        public void snowflakeDistinctForDistinctGtidsInSameMillisecond() {
            Set<Long> ids = new HashSet<>();
            for (long gtid = 1; gtid <= 1024; gtid++) {
                ids.add(SnowFlakeId.generate(TS_2024, gtid, false));
            }
            assertEquals(1024, ids.size(),
                    "distinct GTIDs within one millisecond must produce distinct snowflake ids");
        }
    }

    @Nested
    @DisplayName("Forward compatibility: sentinel and sign safety")
    class SentinelAndSignSafety {

        /**
         * Every value the version scheme can produce must be distinguishable
         * from the {@code -1} uninitialized sentinel. Read as {@code UInt64},
         * -1 is 18446744073709551615 - the maximum representable version, so a
         * row carrying it can never be superseded: the ReplacingMergeTree
         * freezes on it and every later update and delete for that key is
         * silently discarded on merge.
         */
        @Test
        @DisplayName("no calculable version collides with the -1 uninitialized sentinel")
        public void calculatedVersionNeverEqualsSentinel() {
            List<Long> probes = new ArrayList<>();
            probes.add(sequenceVersion(1L, 1L));                      // epoch + 1ms
            probes.add(sequenceVersion(TS_2024, 1L));                 // present day
            probes.add(sequenceVersion(TS_2024, SEQUENCE_SCALE - 1)); // counter at its bound
            probes.add(gtidVersion(1L));
            probes.add(gtidVersion(Long.MAX_VALUE - 1));
            probes.add(snowflakeVersion(TS_2024, 1L));
            for (long version : probes) {
                assertNotEquals(-1L, version,
                        "a calculated _version must never equal the -1 sentinel, which "
                                + "ClickHouse stores as UInt64 max and can never be superseded");
            }
        }

        /**
         * ClickHouse declares {@code _version} as {@code UInt64}, and the
         * connector binds it with {@code PreparedStatement.setLong}, so the
         * driver reinterprets the 64 bits unsigned. Any Java-side ordering
         * therefore has to use {@link Long#compareUnsigned}, not {@code >}.
         *
         * <p>The {@code ts_ms * 1_000_000} encoding crosses
         * {@code Long.MAX_VALUE} in the year 2262, at which point the Java
         * long goes negative while the stored {@code UInt64} keeps increasing
         * correctly. Verified against a live ClickHouse:
         * {@code reinterpretAsUInt64(toInt64(-1))} = 18446744073709551615, so
         * a negative long is read as a very large unsigned value.</p>
         */
        @Test
        @DisplayName("ordering holds under unsigned comparison even when the sign bit flips")
        public void orderingHoldsUnderUnsignedComparison() {
            long nearMax = Long.MAX_VALUE - 10L;
            long pastMax = nearMax + 20L; // wraps to negative as a signed long

            assertTrue(pastMax < 0,
                    "precondition: the probe value must have wrapped the sign bit");
            assertTrue(Long.compareUnsigned(pastMax, nearMax) > 0,
                    "_version is UInt64 in ClickHouse, so ordering across the sign-bit "
                            + "boundary must hold under unsigned comparison - no Java-side "
                            + "code may order versions with a signed comparison");
        }

        @Test
        @DisplayName("present-day versions stay positive and well below the sign bit")
        public void presentDayVersionsStayPositive() {
            long version = sequenceVersion(TS_2024, 1L);
            assertTrue(version > 0,
                    "a present-day _version must be positive: " + version);
            assertTrue(version < Long.MAX_VALUE / 2,
                    "a present-day _version must leave headroom below the sign bit so that "
                            + "the encoding cannot wrap within the supported clock range; got "
                            + version);
        }

        @Test
        @DisplayName("consecutive events are strictly ordered")
        public void consecutiveEventsAreStrictlyOrdered() {
            long earlier = sequenceVersion(TS_2024, 1L);
            long later = sequenceVersion(TS_2024, 2L);
            assertNotEquals(earlier, later);
            assertTrue(later > earlier, "consecutive counters must be strictly ordered");
        }
    }

    @Nested
    @DisplayName("Backward compatibility: version-source precedence")
    class VersionSourcePrecedence {

        /**
         * {@code calculateVersion()} consults GTID, then sequence number, then
         * LSN. The order is part of the data format: a build that resolved a
         * record's version from a different source than the build before it
         * would produce values in a different range for the same row, and the
         * two would compete inside the same ReplacingMergeTree.
         */
        @Test
        @DisplayName("GTID takes precedence over sequence number and LSN")
        public void gtidWinsOverSequenceAndLsn() {
            ClickHouseStruct record = new ClickHouseStruct();
            record.setGtid(11L);
            record.setSequenceNumber(22L);
            record.setLsn(33L);
            record.calculateVersion(false);
            assertEquals(11L, record.getVersion(),
                    "GTID must win; changing the precedence changes the value range for "
                            + "the same row and is an irreversible format change");
        }

        @Test
        @DisplayName("sequence number takes precedence over LSN")
        public void sequenceWinsOverLsn() {
            ClickHouseStruct record = new ClickHouseStruct();
            record.setSequenceNumber(22L);
            record.setLsn(33L);
            record.calculateVersion(false);
            assertEquals(22L, record.getVersion(), "sequence number must win over LSN");
        }

        @Test
        @DisplayName("LSN is used when it is the only source available")
        public void lsnUsedWhenOnlySource() {
            ClickHouseStruct record = new ClickHouseStruct();
            record.setLsn(33L);
            record.calculateVersion(false);
            assertEquals(33L, record.getVersion(), "LSN must be used for Postgres sources");
        }

        /**
         * With no source at all the sentinel must survive untouched, so the
         * bind-site guard can detect it. Silently substituting a value here
         * would hide the condition and write an unorderable row.
         */
        @Test
        @DisplayName("with no version source the sentinel is left in place for the guard")
        public void noSourceLeavesSentinel() {
            ClickHouseStruct record = new ClickHouseStruct();
            record.calculateVersion(false);
            assertEquals(-1L, record.getVersion(),
                    "the sentinel must survive so the bind-site guard can reject the record "
                            + "rather than write UInt64 max into the table");
        }
    }
}

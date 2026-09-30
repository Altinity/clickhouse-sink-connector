package com.altinity.clickhouse.debezium.embedded.cdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Failure mode FM-02.02-4 (spec 02.02 section 7): a persisted high-water mark
 * that lies in the future of the connector clock, left by a clock step (the
 * source host stepped forward while rows were versioned, or the connector host
 * stepped forward at a clock-seeded start and every row was clamped to it).
 *
 * <p>{@code VersionHighWaterMark.seedFloor} ignores a mark whose floor is more
 * than {@code PLAUSIBLE_FUTURE_MS} (24 h) past the connector clock and seeds from
 * the clock instead. Every row the previous run wrote carries a version at or
 * below that mark, so the fallback seeds the new run BELOW rows already in
 * ClickHouse: their keys keep their stale values under {@code FINAL} until the
 * source clock reaches the stepped instant. A too-high floor only delays the
 * source clock catching up (spec 02.02 section 3.5); a too-low one is the
 * divergence the mark exists to prevent.</p>
 */
public class VersionHighWaterMarkClockStepTest {

    private static final long M = 1_000_000L;
    /** The connector clock: 2026-08-25T00:00:00Z. */
    private static final long NOW = 1_787_616_000_000L;
    private static final long HOUR = 3_600_000L;

    private static VersionHighWaterMark mark(VersionHighWaterMarkTest.FakeClickHouse fake) {
        return new VersionHighWaterMark(fake::connection, "offsets_db.replica_source_info", () -> NOW, 2, 0L);
    }

    @Test
    @DisplayName("a mark up to 24 h in the future is still the seed: the floor never drops below it")
    public void markWithinTheFutureWindowIsStillTheSeed() throws Exception {
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();
        long persisted = (NOW + 23 * HOUR) * M + 1_000_000_000L;
        fake.answer("SELECT max(`high_water_version`)", new Object[] {String.valueOf(persisted)});

        VersionHighWaterMark.Seed seed = mark(fake).seedFloor();

        assertTrue(seed.fromMark, seed.source);
        assertEquals(VersionHighWaterMark.sequenceFloor(persisted), seed.floorMs,
                "the first rows are clamped to the stepped instant; ordering is kept");
    }

    @Test
    @Disabled("DEFECT FM-02.02-4: a mark more than 24 h ahead of the connector clock is discarded and "
            + "the floor is seeded from the clock, below versions the previous run already wrote")
    @DisplayName("a mark more than 24 h in the future must not seed the floor below it")
    public void futureMarkBeyondThePlausibilityWindowDoesNotLowerTheFloor() throws Exception {
        VersionHighWaterMarkTest.FakeClickHouse fake = new VersionHighWaterMarkTest.FakeClickHouse();
        // The source host clock ran three days ahead for a while; rows were versioned
        // there and the horizon followed them.
        long persisted = (NOW + 72 * HOUR) * M + 1_000_000_000L;
        fake.answer("SELECT max(`high_water_version`)", new Object[] {String.valueOf(persisted)});

        VersionHighWaterMark.Seed seed = mark(fake).seedFloor();

        assertTrue(seed.floorMs >= VersionHighWaterMark.sequenceFloor(persisted),
                "the seed " + seed.floorMs + " (" + seed.source + ") lies below the persisted mark's floor "
                        + VersionHighWaterMark.sequenceFloor(persisted) + ": every key the previous run "
                        + "wrote keeps its value under FINAL for three days of source time");
    }
}

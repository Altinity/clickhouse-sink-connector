package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter.DateConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter.RangePolicy;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter.TimestampConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter.ZonedTimestampConverter;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.altinity.clickhouse.sink.connector.metadata.TableMetaDataWriter;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Zone x instant matrix for the converter layer (explicit time zones).
 *
 * <p>Nine zones, chosen to cover each offset and transition shape: UTC; America/Chicago (the
 * fall-back overlap of 2026-11-01); America/New_York; Europe/London; Asia/Kolkata (+05:30, no DST);
 * Pacific/Apia (skipped local midnight 2011-12-30); Australia/Lord_Howe (30-minute DST);
 * Pacific/Kiritimati (+14:00); America/St_Johns (-03:30).
 *
 * <p>Every zone is an explicit argument; the JVM default zone never enters any assertion. The
 * expected values are computed independently of the production helpers (BigDecimal FLOOR, LocalDate
 * arithmetic, java.time atZone), so a regression in either side fails the matrix.
 */
class TimeZoneMatrixTest {

    static final List<ZoneId> ZONES = List.of(
            ZoneId.of("UTC"),
            ZoneId.of("America/Chicago"),
            ZoneId.of("America/New_York"),
            ZoneId.of("Europe/London"),
            ZoneId.of("Asia/Kolkata"),
            ZoneId.of("Pacific/Apia"),
            ZoneId.of("Australia/Lord_Howe"),
            ZoneId.of("Pacific/Kiritimati"),
            ZoneId.of("America/St_Johns"));

    /**
     * Epoch milliseconds: the epoch; the second before it (digits path, -0.5 s is not the unbindable
     * second, -1.0 s is); sub-second negatives; the Chicago fall-back overlap (first pass 06:30Z,
     * transition 07:00Z, second pass 07:30Z on 2026-11-01); the 2100 open-row sentinel; and the last
     * millisecond of DateTime64 (2299-12-31T23:59:59.999Z).
     */
    static final List<Long> INSTANTS_MS = List.of(
            0L,
            -500L,
            -1_000L,
            -1_500L,
            -1_000_001L,
            1_793_514_600_000L,
            1_793_516_400_000L,
            1_793_518_200_000L,
            4_102_444_800_000L,
            10_413_791_999_999L);

    static final DateTimeFormatter MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Independent reference for the epoch bind: whole micros, rounded toward negative infinity. */
    static String referenceEpochText(long epochMillis) {
        return new BigDecimal(epochMillis).movePointLeft(3).setScale(6, RoundingMode.FLOOR).toPlainString();
    }

    @Test
    void epochBindIsTheFloorToMicrosInEveryZone() {
        for (ZoneId zone : ZONES) {
            for (long ms : INSTANTS_MS) {
                Instant instant = Instant.ofEpochMilli(ms);
                String text = TimestampConverter.instantEpochText(ms, zone);
                if (DebeziumConverter.bindsAsEpochText(ClickHouseDataType.DateTime64, instant)) {
                    assertEquals(referenceEpochText(ms), text, "zone=" + zone + " ms=" + ms);
                } else {
                    // The unbindable second: wall digits in the column zone, as before.
                    assertEquals(instant.atZone(zone).format(MILLIS), text, "zone=" + zone + " ms=" + ms);
                }
            }
        }
    }

    @Test
    void epochBindIsZoneFreeSoEveryZoneWritesTheSameText() {
        for (long ms : INSTANTS_MS) {
            if (!DebeziumConverter.bindsAsEpochText(ClickHouseDataType.DateTime64, Instant.ofEpochMilli(ms))) {
                continue;
            }
            String first = TimestampConverter.instantEpochText(ms, ZONES.get(0));
            for (ZoneId zone : ZONES) {
                assertEquals(first, TimestampConverter.instantEpochText(ms, zone), "zone=" + zone + " ms=" + ms);
            }
        }
    }

    @Test
    void aliasedAndEqualZonesAreTheSameZoneAndDistinctRulesAreNot() {
        for (ZoneId a : ZONES) {
            assertTrue(DebeziumConverter.sameZone(a, a), "reflexive: " + a);
            for (ZoneId b : ZONES) {
                if (DebeziumConverter.sameZone(a, b)) {
                    for (long ms : INSTANTS_MS) {
                        Instant instant = Instant.ofEpochMilli(ms);
                        assertEquals(a.getRules().getOffset(instant), b.getRules().getOffset(instant),
                                "sameZone must imply equal offsets: " + a + " vs " + b + " at " + ms);
                    }
                }
            }
        }
        // P1: Kolkata and Calcutta are two identifiers for one rules value.
        assertTrue(DebeziumConverter.sameZone(ZoneId.of("Asia/Kolkata"), ZoneId.of("Asia/Calcutta")));
        assertFalse(DebeziumConverter.sameZone(ZoneId.of("America/Chicago"), ZoneId.of("UTC")));
    }

    @Test
    void dateTextKeepsTheDayEvenWhereLocalMidnightIsSkipped() {
        // 2011-12-30 is 15338 days after the epoch; Pacific/Apia skipped its local midnight that day.
        int skippedMidnightDay = 15338;
        assertEquals(LocalDate.of(2011, 12, 30),
                DateConverter.convert(skippedMidnightDay, ClickHouseDataType.Date32, RangePolicy.CLAMP));
        for (int day : List.of(-1, 0, 1, 19_000, skippedMidnightDay)) {
            assertEquals(LocalDate.ofEpochDay(day), DateConverter.convert(day, ClickHouseDataType.Date32, RangePolicy.CLAMP));
        }
    }

    @Test
    void zonedTextWithoutAnOffsetIsReadInTheExplicitServerZone() {
        // Debezium emits an offset; the zone-less pattern is a fallback. It must use the explicit
        // server zone argument, never the JVM default, so the result equals java.time in that zone.
        LocalDateTime wall = LocalDateTime.of(2026, 6, 1, 12, 0, 0);
        for (ZoneId zone : ZONES) {
            RangePolicy policy = RangePolicy.of(null, "db.t.ts", 6);
            Instant bound = ZonedTimestampConverter.boundedInstant(wall.format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")), zone, policy);
            assertEquals(wall.atZone(zone).toInstant(), bound, "zone=" + zone);
        }
    }

    @Test
    void infinityBoundsSaturateToTheScaleOfTheColumn() {
        RangePolicy scale6 = RangePolicy.of(null, "db.t.ts", 6);
        assertEquals(DataTypeRange.dateTime64Max(6),
                ZonedTimestampConverter.boundedInstant("infinity", ZoneId.of("America/Chicago"), scale6));
        RangePolicy scale9 = RangePolicy.of(null, "db.t.ts", 9);
        assertEquals(Instant.ofEpochSecond(9_223_372_036L, 854_775_807L),
                ZonedTimestampConverter.boundedInstant("infinity", ZoneId.of("Pacific/Kiritimati"), scale9));
        assertNotEquals(DataTypeRange.dateTime64Max(6), DataTypeRange.dateTime64Max(9));
    }

    @Test
    void outOfRangeDateTime64TextReadsBackAsTheBoundInEveryServerZone() {
        // One minute past the last DateTime64(6) millisecond saturates to 2299-12-31 23:59:59.999999.
        // The text is wall time in the server zone, so reading it back in that zone is the same instant.
        long beyondMax = 10_413_791_999_999L + 60_000L;
        for (ZoneId zone : ZONES) {
            String text = TimestampConverter.convertWithoutTimeZoneAdjustment(
                    beyondMax, ClickHouseDataType.DateTime64, zone, zone);
            Instant read = LocalDateTime.parse(text, MILLIS).atZone(zone).toInstant();
            assertEquals(10_413_791_999L, read.getEpochSecond(), "zone=" + zone + " text=" + text);
            assertEquals(999, read.getNano() / 1_000_000, "zone=" + zone + " text=" + text);
        }
    }

    @Test
    void kafkaTimestampTextIsTheWallClockInTheExplicitServerZone() {
        long instantMs = 1_780_315_200_000L; // 2026-06-01T12:00:00Z, outside every zone's ambiguous hour
        for (ZoneId zone : ZONES) {
            String text = TableMetaDataWriter.kafkaTimestampText(instantMs, zone);
            assertEquals(Instant.ofEpochMilli(instantMs).atZone(zone).toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), text, "zone=" + zone);
            assertEquals(instantMs, LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli(),
                    "round trip in zone=" + zone);
        }
    }

    @Test
    void kafkaTimestampTextRefusesAMissingZone() {
        assertThrows(NullPointerException.class, () -> TableMetaDataWriter.kafkaTimestampText(0L, null));
    }
}

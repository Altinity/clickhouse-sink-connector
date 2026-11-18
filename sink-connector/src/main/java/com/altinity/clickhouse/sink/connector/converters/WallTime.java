package com.altinity.clickhouse.sink.connector.converters;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.List;

/**
 * The one helper for zone-less wall times (Spec 07.03 section 3.1.1), shared by
 * {@link DebeziumConverter.MicroTimestampConverter} and
 * {@link DebeziumConverter.TimestampConverter}.
 *
 * <p>A wall time names one instant in most zones, none in a spring-forward gap
 * and two in a fall-back overlap. The rule is the same everywhere: a gap takes
 * the offset before the transition (the wall time is read as if the clock had
 * not yet jumped, so {@code 02:30} in {@code America/Chicago} on the spring
 * day is {@code 03:30 CDT}); an overlap takes the earlier offset (the first
 * occurrence, which is also what ClickHouse keeps for digits). Neither is
 * exact, so both are reported as lossy conversions (Spec 07.03 section 3.1.14).
 */
final class WallTime {

    private WallTime() {
    }

    /**
     * The kind of lossy conversion that a wall time in {@code rules} is, or null
     * when the wall time names exactly one instant.
     */
    static LossyConversionReporter.Kind ambiguity(ZoneRules rules, LocalDateTime wall) {
        int offsets = rules.getValidOffsets(wall).size();
        if (offsets == 0) {
            return LossyConversionReporter.Kind.DST_GAP;
        }
        if (offsets > 1) {
            return LossyConversionReporter.Kind.DST_OVERLAP;
        }
        return null;
    }

    /** The instant a wall time stands for under the rule above. */
    static Instant instantOf(ZoneRules rules, LocalDateTime wall) {
        List<ZoneOffset> offsets = rules.getValidOffsets(wall);
        if (offsets.size() == 1) {
            return wall.toInstant(offsets.get(0));
        }
        if (offsets.isEmpty()) {
            ZoneOffsetTransition transition = rules.getTransition(wall);
            return wall.toInstant(transition.getOffsetBefore());
        }
        return wall.toInstant(offsets.get(0));
    }

    /**
     * Whether {@code instant}, rendered as wall digits in {@code zone}, is the
     * second occurrence of those digits (the later of two in an overlap). Such
     * digits are stored as the first occurrence, so the stored instant is an
     * hour early.
     */
    static boolean isLaterOccurrence(ZoneId zone, Instant instant) {
        ZoneRules rules = zone.getRules();
        LocalDateTime wall = instant.atZone(zone).toLocalDateTime();
        List<ZoneOffset> offsets = rules.getValidOffsets(wall);
        return offsets.size() > 1 && !offsets.get(0).equals(instant.atZone(zone).getOffset());
    }

    /** Source text of a wall time for a log line, e.g. {@code 2026-03-08 02:30:00.000000 in America/Chicago}. */
    static String describe(LocalDateTime wall, ZoneId zone, DateTimeFormatter format) {
        return wall.format(format) + " in " + zone.getId();
    }
}

package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.common.Metrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one reporter for a value stored as something other than the source held
 * (Spec 07.03 section 3.1.14). Every converter reports through here.
 *
 * <p>Each conversion is counted: in-process by {@link #count(Kind)} and on the
 * connector metrics as {@code clickhouse_sink_lossy_conversions_total{kind}}
 * ({@link Metrics#incrementLossyConversion(String)}). It is logged at WARN
 * with the marker {@code LOSSY_CONVERSION} the first time each (kind, column)
 * pair occurs; after that, the conversions of that kind are counted, and one
 * summary WARN per kind is written at most every {@value #SUMMARY_INTERVAL_MILLIS}
 * milliseconds. The log therefore has one line per column per kind plus a
 * bounded number of summaries, however many rows are lossy.
 *
 * <p>Strict mode ({@code clamp.out.of.range=false}) does not come through here:
 * {@link DebeziumConverter.RangePolicy} throws instead.
 */
public final class LossyConversionReporter {

    /** The kinds of lossy conversion. {@link #label()} is the metric label value. */
    public enum Kind {
        /** A temporal or decimal value outside the ClickHouse range, saturated to the bound. */
        RANGE_CLAMP,
        /** A DATETIME wall time that does not exist in the zone (spring-forward gap). */
        DST_GAP,
        /** A DATETIME wall time that occurs twice in the zone (fall-back overlap), or digits that name the first occurrence. */
        DST_OVERLAP,
        /** An integer other than 0 or 1 bound into a Bool column, stored as true. */
        BOOL_COLLAPSE,
        /** An integer that does not fit a narrower ClickHouse integer column, truncated to its width. */
        INT_WRAP,
        /** A decimal with more fractional digits than the column scale, truncated. */
        DECIMAL_TRUNCATE,
        /** A DATETIME fraction with more digits than the column scale, truncated. */
        FRACTION_TRUNCATE,
        /** A text value whose trailing NUL byte is bound into a FixedString column, which reads the NUL back as padding. */
        FIXEDSTRING_NUL,
        /**
         * A MySQL {@code TIMESTAMP} that arrives as the instant 1970-01-01 00:00:00 UTC exactly. MySQL's smallest
         * real TIMESTAMP is 1970-01-01 00:00:01 UTC, so this instant is the zero TIMESTAMP {@code 0000-00-00 00:00:00}
         * and is stored as the epoch, a legal but different value.
         */
        ZERO_TIMESTAMP;

        /** The lower-case name used as the {@code kind} metric label and in log lines. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** At most one summary WARN per kind per this many milliseconds. */
    static final long SUMMARY_INTERVAL_MILLIS = 5L * 60L * 1000L;

    private static final Logger log = LogManager.getLogger(LossyConversionReporter.class);

    private static final Map<Kind, AtomicLong> TOTALS = new EnumMap<>(Kind.class);
    private static final Map<Kind, AtomicLong> WARNINGS = new EnumMap<>(Kind.class);
    private static final Map<Kind, AtomicLong> SUPPRESSED = new EnumMap<>(Kind.class);
    private static final Map<Kind, Long> LAST_LOGGED_MILLIS = new EnumMap<>(Kind.class);
    private static final Set<String> FIRST_SEEN = new HashSet<>();

    static {
        for (Kind kind : Kind.values()) {
            TOTALS.put(kind, new AtomicLong());
            WARNINGS.put(kind, new AtomicLong());
            SUPPRESSED.put(kind, new AtomicLong());
        }
    }

    private LossyConversionReporter() {
    }

    /**
     * Records one lossy conversion.
     *
     * @param kind   what happened
     * @param column the column label {@code db.table.column}, or null when unknown
     * @param source the source value, as text, with its zone where it has one
     * @param stored the value actually bound into ClickHouse, as text
     */
    public static void report(Kind kind, String column, String source, String stored) {
        report(kind, column, source, stored, System.currentTimeMillis());
    }

    /** The number of conversions of {@code kind} reported in this process since start. */
    public static long count(Kind kind) {
        return TOTALS.get(kind).get();
    }

    /** The number of WARN lines written for {@code kind} in this process (the first per column, and the summaries). */
    static long warnings(Kind kind) {
        return WARNINGS.get(kind).get();
    }

    /** Clears every count and the first-seen memory. Tests only. */
    static synchronized void resetForTests() {
        for (Kind kind : Kind.values()) {
            TOTALS.get(kind).set(0);
            WARNINGS.get(kind).set(0);
            SUPPRESSED.get(kind).set(0);
            LAST_LOGGED_MILLIS.remove(kind);
        }
        FIRST_SEEN.clear();
    }

    static synchronized void report(Kind kind, String column, String source, String stored, long nowMillis) {
        TOTALS.get(kind).incrementAndGet();
        Metrics.incrementLossyConversion(kind.label());

        String where = column == null ? "(unknown column)" : column;
        if (FIRST_SEEN.add(kind.label() + '|' + where)) {
            WARNINGS.get(kind).incrementAndGet();
            LAST_LOGGED_MILLIS.putIfAbsent(kind, nowMillis);
            log.warn("LOSSY_CONVERSION kind={} column={} source={} stored={}. First occurrence in this column; "
                            + "later ones are counted in clickhouse_sink_lossy_conversions_total{kind=\"{}\"} "
                            + "and summarised at most every 5 minutes. To refuse such values instead, set "
                            + "clamp.out.of.range=false.",
                    kind.label(), where, source, stored, kind.label());
            return;
        }
        SUPPRESSED.get(kind).incrementAndGet();
        Long last = LAST_LOGGED_MILLIS.get(kind);
        if (last != null && nowMillis - last >= SUMMARY_INTERVAL_MILLIS) {
            long suppressed = SUPPRESSED.get(kind).getAndSet(0);
            WARNINGS.get(kind).incrementAndGet();
            LAST_LOGGED_MILLIS.put(kind, nowMillis);
            log.warn("LOSSY_CONVERSION kind={} summary: {} conversions since the last summary were counted, "
                            + "not logged (total {} since start). See clickhouse_sink_lossy_conversions_total.",
                    kind.label(), suppressed, TOTALS.get(kind).get());
        }
    }
}

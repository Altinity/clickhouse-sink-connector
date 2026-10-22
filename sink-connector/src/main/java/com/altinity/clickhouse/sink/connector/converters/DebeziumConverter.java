package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.format.BinaryStreamUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.time.temporal.ChronoUnit;

import static java.time.Instant.ofEpochMilli;

public class DebeziumConverter {

    private static final int MICROS_IN_SEC = 1000000;
    private static final int MICROS_IN_MILLI = 1000;

    private static final Logger log = LogManager.getLogger(DebeziumConverter.class);

    /**
     * The fixed render patterns, built once. {@link DateTimeFormatter} is
     * immutable and thread-safe; building one from its pattern string parses
     * the pattern every time, and the converters below used to do that per
     * VALUE -- it was measured at ~4% of the whole per-row bind cost (Spec
     * 04.06 section 3.3). The output is byte-identical to the per-call form.
     */
    static final DateTimeFormatter SECONDS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final DateTimeFormatter MILLIS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    static final DateTimeFormatter MICROS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    static final DateTimeFormatter EIGHT_DIGIT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSS");
    static final DateTimeFormatter NANOS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

    /**
     * Renders an instant as epoch text, {@code <seconds>.<6 fraction digits>}
     * (Spec 07.03 section 3.1.4). ClickHouse parses this into a
     * {@code DateTime64} column exactly, whatever zone the column declares,
     * whereas wall-clock digits are ambiguous in a fall-back overlap hour:
     * {@code '2026-11-01 01:30:00'} in {@code America/Chicago} names two
     * instants and ClickHouse stores the first, so the second cannot be
     * stored through digits at all. Sub-microsecond digits are truncated, as
     * {@link #MICROS_FORMAT} truncates them.
     */
    static String epochText(Instant instant) {
        // FLOOR, not DOWN: DOWN truncates toward zero, so a negative instant with a
        // sub-microsecond part was one microsecond late (Spec 07.03 section 3.1.11).
        return BigDecimal.valueOf(instant.getEpochSecond())
                .add(BigDecimal.valueOf(instant.getNano(), 9))
                .setScale(6, RoundingMode.FLOOR)
                .toPlainString();
    }

    /**
     * Whether two zones are the same zone: equal identifiers, or equal
     * {@link ZoneRules} (Spec 07.03 section 3.1.13). {@link ZoneId#equals} compares
     * identifiers only, so {@code US/Central} and {@code America/Chicago} were
     * "different zones" and the different-zones branch shifted zone-less digits by
     * the source offset.
     */
    public static boolean sameZone(ZoneId a, ZoneId b) {
        return a.equals(b) || a.getRules().equals(b.getRules());
    }

    /**
     * Whether an instant bound into a column of {@code targetType} is rendered
     * as {@link #epochText(Instant)} (Spec 07.03 section 3.1.4). Only a
     * {@code DateTime64} column parses epoch text ({@code DateTime} rejects
     * {@code '1793518200'}: "Cannot parse DateTime from String", measured on
     * 24.8.14), and ClickHouse drops the sign of an epoch between -1 and 0
     * ({@code '-0.500000'} reads back as +0.5 s), so the second before
     * 1970-01-01 00:00:00 UTC keeps the digits rendering.
     */
    static boolean bindsAsEpochText(ClickHouseDataType targetType, Instant instant) {
        if (targetType != ClickHouseDataType.DateTime64) {
            return false;
        }
        return !(instant.getEpochSecond() == -1L && instant.getNano() > 0);
    }

    /**
     * Binds a bounded instant for the target column (Spec 07.03 sections 3.1.4
     * and 3.1.14). A DateTime64 column takes epoch text, which is exact in any
     * zone. Any other column takes wall digits in its own zone, which are exact
     * unless the instant is the second occurrence of those digits in that zone:
     * ClickHouse keeps the first occurrence of digits, so that case is reported
     * as {@link LossyConversionReporter.Kind#DST_OVERLAP} and the stored value is
     * the first occurrence, an hour before the source instant.
     *
     * @param formatZone the zone the column declares, or the server zone when it declares none
     */
    static String renderResolved(Instant instant, ClickHouseDataType type, ZoneId formatZone,
                                 DateTimeFormatter formatter, boolean outOfRange, RangePolicy policy) {
        if (bindsAsEpochText(type, instant)) {
            return epochText(instant);
        }
        if (outOfRange) {
            return instant.atZone(ZoneOffset.UTC).format(formatter);
        }
        if (WallTime.isLaterOccurrence(formatZone, instant)) {
            String stored = instant.atZone(formatZone).toLocalDateTime().format(formatter);
            policy.lossy(LossyConversionReporter.Kind.DST_OVERLAP,
                    instant + " (second occurrence of its wall time in " + formatZone.getId() + ")",
                    stored + " (first occurrence in " + formatZone.getId() + ")");
            return stored;
        }
        return instant.atZone(formatZone).format(formatter);
    }

    /**
     * The digits of a value whose source zone is the server zone (Spec 07.03
     * section 3.1.1). The digits name a wall time in the zone the column
     * declares, so they are exact when that wall time occurs once there; a gap
     * or an overlap is resolved by {@link WallTime} and reported.
     */
    static String sameZoneWall(LocalDateTime wall, ZoneId formatZone, ClickHouseDataType type,
                               DateTimeFormatter formatter, RangePolicy policy, DateTimeFormatter sourceFormat) {
        boolean[] rangeExceeded = new boolean[1];
        Instant bounded = policy.boundDateTime(wall.toInstant(ZoneOffset.UTC), type, rangeExceeded);
        LocalDateTime boundedWall = LocalDateTime.ofInstant(bounded, ZoneOffset.UTC);
        LossyConversionReporter.Kind kind = rangeExceeded[0]
                ? null : WallTime.ambiguity(formatZone.getRules(), boundedWall);
        if (kind == null) {
            return bounded.atZone(ZoneOffset.UTC).format(formatter);
        }
        Instant resolved = WallTime.instantOf(formatZone.getRules(), boundedWall);
        String stored = renderResolved(resolved, type, formatZone, formatter, false, policy);
        policy.lossy(kind, WallTime.describe(boundedWall, formatZone, sourceFormat), stored);
        return stored;
    }

    /**
     * The digits of a value whose source zone differs from the server zone: a
     * wall time in the source zone (Spec 07.03 section 3.1.1). A gap takes the
     * offset before the transition, an overlap the earlier offset; either is
     * reported as lossy.
     */
    static String differentZoneWall(LocalDateTime wall, ZoneId sourceZone, ZoneId formatZone,
                                    ClickHouseDataType type, DateTimeFormatter formatter, RangePolicy policy,
                                    DateTimeFormatter sourceFormat) {
        ZoneRules sourceRules = sourceZone.getRules();
        LossyConversionReporter.Kind kind = WallTime.ambiguity(sourceRules, wall);
        boolean[] rangeExceeded = new boolean[1];
        Instant bounded = policy.boundDateTime(WallTime.instantOf(sourceRules, wall), type, rangeExceeded);
        String stored = renderResolved(bounded, type, formatZone, formatter, rangeExceeded[0], policy);
        if (kind != null && !rangeExceeded[0]) {
            policy.lossy(kind, WallTime.describe(wall, sourceZone, sourceFormat), stored);
        }
        return stored;
    }

    /**
     * Raised when a source value does not fit the ClickHouse column type and
     * {@code clamp.out.of.range} is false (Spec 07.03 section 3.3). The batch
     * fails; nothing is written for it.
     */
    public static class ValueOutOfRangeException extends IllegalArgumentException {
        public ValueOutOfRangeException(String message) {
            super(message);
        }
    }

    /**
     * What to do with a value outside the ClickHouse type's range: saturate to
     * the bound (default, {@code clamp.out.of.range=true}) or fail the batch
     * ({@code clamp.out.of.range=false}). Under the default a saturation is
     * stored and counted, and logged at WARN once per column with a bounded
     * summary after that (Spec 07.03 sections 3.3 rule 2 and 3.1.14). Every
     * lossy kind, not only the range clamp, is refused under strict mode.
     */
    public static final class RangePolicy {

        /**
         * Saturate to the bound, counted and logged at WARN once per column
         * (Spec 07.03 section 3.1.14). Used by the policy-less converter
         * overloads, which have no configuration to consult.
         */
        public static final RangePolicy CLAMP = new RangePolicy(true, null, DataTypeRange.DATETIME64_DEFAULT_SCALE);

        /** Fail the batch ({@code clamp.out.of.range=false}). */
        public static final RangePolicy STRICT = new RangePolicy(false, null, DataTypeRange.DATETIME64_DEFAULT_SCALE);

        private final boolean clamp;
        private final String column;
        private final int scale;

        private RangePolicy(boolean clamp, String column, int scale) {
            this.clamp = clamp;
            this.column = column;
            this.scale = scale;
        }

        /**
         * The policy for one bound column at the default DateTime64 scale (6).
         * Use {@link #of(ClickHouseSinkConnectorConfig, String, int)} for a
         * DateTime64 column whose declared scale is known.
         *
         * @param config the connector configuration (null means the default, which saturates)
         * @param column the column being bound, e.g. {@code db.orders.expires_at};
         *               may be null when unknown
         * @return the policy
         */
        public static RangePolicy of(ClickHouseSinkConnectorConfig config, String column) {
            return of(config, column, DataTypeRange.DATETIME64_DEFAULT_SCALE);
        }

        /**
         * The policy for one bound column, from the connector configuration and
         * the column's declared DateTime64 scale (Spec 07.03 section 3.1.8): the
         * DateTime64 upper bound is the one for that scale, not a scale-6 bound
         * applied to every column.
         *
         * @param config the connector configuration (null means the default, which saturates)
         * @param column the column being bound; may be null when unknown
         * @param scale  the DateTime64 scale of the column, 0..9
         * @return the policy
         * @throws IllegalArgumentException when {@code scale} is outside 0..9
         */
        public static RangePolicy of(ClickHouseSinkConnectorConfig config, String column, int scale) {
            DataTypeRange.dateTime64Max(scale); // validates the scale
            boolean clamp = config == null || config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.CLAMP_OUT_OF_RANGE.toString());
            return new RangePolicy(clamp, column, scale);
        }

        /** Whether out-of-range values are saturated rather than rejected. */
        public boolean clamps() {
            return clamp;
        }

        /** The column label carried into messages, or null. */
        public String column() {
            return column;
        }

        /** The DateTime64 scale the upper bound is computed for, 0..9. */
        public int scale() {
            return scale;
        }

        /**
         * Bounds a DateTime/DateTime64 instant; {@code rangeExceeded[0]} is set
         * when the value was outside the range (the callers then render the
         * bound in UTC).
         */
        Instant boundDateTime(Instant provided, ClickHouseDataType type, boolean[] rangeExceeded) {
            Instant bounded = checkIfDateTimeExceedsSupportedRange(provided, type, rangeExceeded, scale);
            if (rangeExceeded[0]) {
                int reportScale = type == ClickHouseDataType.DateTime64 ? scale : 0;
                report(provided.toString(), formatBound(bounded, reportScale), type.name(), dateTimeBounds(type));
            }
            return bounded;
        }

        /**
         * Renders a bound with {@code scale} fractional digits, so the message
         * shows the value that is actually stored (23:59:59.999999, not .000).
         */
        static String formatBound(Instant bound, int scale) {
            String pattern = scale == 0 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm:ss." + "S".repeat(scale);
            return DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(bound);
        }

        /** Bounds a Date/Date32 value given as epoch days. */
        int boundDate(int epochDays, ClickHouseDataType type) {
            int bounded = DateConverter.checkIfDateExceedsSupportedRange(epochDays, type);
            if (bounded != epochDays) {
                report(LocalDate.ofEpochDay(epochDays).toString(), LocalDate.ofEpochDay(bounded).toString(),
                        type.name(), dateBounds(type));
            }
            return bounded;
        }

        /** Bounds a TIMESTAMP (ZonedTimestamp) instant to the DateTime64 range. */
        Instant boundZonedTimestamp(Instant provided) {
            // Compared at full precision: the old millisecond test with a
            // seconds-only bound zeroed the fraction of every value in the last
            // second of 2299 (23:59:59.500 became 23:59:59.000).
            Instant max = DataTypeRange.dateTime64Max(scale);
            Instant min = DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64;
            Instant bounded = provided;
            if (provided.isAfter(max)) {
                bounded = max;
            } else if (provided.isBefore(min)) {
                bounded = min;
            }
            if (bounded != provided) {
                report(provided.toString(), formatBound(bounded, scale), ClickHouseDataType.DateTime64.name(),
                        dateTimeBounds(ClickHouseDataType.DateTime64));
            }
            return bounded;
        }

        /** Bounds a decimal to the Decimal128 range. */
        BigDecimal boundDecimal(BigDecimal value) {
            BigDecimal bounded = value;
            if (value.compareTo(BinaryStreamUtils.DECIMAL128_MAX) > 0) {
                bounded = BinaryStreamUtils.DECIMAL128_MAX;
            } else if (value.compareTo(BinaryStreamUtils.DECIMAL128_MIN) < 0) {
                bounded = BinaryStreamUtils.DECIMAL128_MIN;
            }
            if (bounded != value) {
                report(value.toPlainString(), bounded.toPlainString(), "Decimal128",
                        "[" + BinaryStreamUtils.DECIMAL128_MIN.toPlainString() + " .. "
                                + BinaryStreamUtils.DECIMAL128_MAX.toPlainString() + "]");
            }
            return bounded;
        }

        /**
         * Reports a lossy conversion this policy governs (Spec 07.03 section
         * 3.1.14): counted and logged under the default; refused under
         * {@code clamp.out.of.range=false}. {@code source} is the value as the
         * source held it, {@code stored} the value that would be bound.
         */
        void lossy(LossyConversionReporter.Kind kind, String source, String stored) {
            if (clamp) {
                LossyConversionReporter.report(kind, column, source, stored);
                return;
            }
            throw refusal(kind, source, stored);
        }

        private void report(String provided, String bounded, String type, String bounds) {
            lossy(LossyConversionReporter.Kind.RANGE_CLAMP, provided + " (" + type + " range " + bounds + ")",
                    bounded);
        }

        /**
         * The refusal under {@code clamp.out.of.range=false}: the batch fails,
         * and the message names the column, the value, what the default would
         * have stored and the two ways out.
         */
        private ValueOutOfRangeException refusal(LossyConversionReporter.Kind kind, String source, String stored) {
            String where = column == null ? "" : " for column " + column;
            return new ValueOutOfRangeException(String.format(
                    "Value %s%s cannot be stored exactly in ClickHouse (%s). The default would store %s "
                            + "instead, and the source never held that value, so the batch is refused. "
                            + "Either widen or retype the ClickHouse column, or set %s=true to store the "
                            + "value and count it in clickhouse_sink_lossy_conversions_total{kind=\"%s\"} "
                            + "(logged at WARN once per column).",
                    source, where, kind.label(), stored,
                    ClickHouseSinkConnectorConfigVariables.CLAMP_OUT_OF_RANGE, kind.label()));
        }

        private String dateTimeBounds(ClickHouseDataType type) {
            if (type == ClickHouseDataType.DateTime || type == ClickHouseDataType.DateTime32) {
                return "[" + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MIN) + " .. "
                        + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MAX) + "]";
            }
            return "[" + formatBound(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64, scale) + " .. "
                    + formatBound(DataTypeRange.dateTime64Max(scale), scale) + "]";
        }

        private static String dateBounds(ClickHouseDataType type) {
            if (type == ClickHouseDataType.Date32) {
                return "[" + LocalDate.ofEpochDay(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32) + " .. "
                        + LocalDate.ofEpochDay(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32) + "]";
            }
            return "[" + LocalDate.ofEpochDay(0) + " .. " + LocalDate.ofEpochDay(BinaryStreamUtils.U_INT16_MAX) + "]";
        }
    }


    public static class MicroTimeConverter {

        private static final long MICROS_PER_SECOND = 1_000_000L;
        private static final long MICROS_PER_MINUTE = 60L * MICROS_PER_SECOND;
        private static final long MICROS_PER_HOUR = 60L * MICROS_PER_MINUTE;

        /**
         * Formats a Debezium {@code io.debezium.time.MicroTime} value -- the
         * MySQL {@code TIME} as a SIGNED total of microseconds -- as
         * {@code [-]HH:mm:ss.ffffff} for a ClickHouse {@code String} column.
         *
         * <p>MySQL {@code TIME} is a duration in {@code -838:59:59 .. 838:59:59},
         * not a time of day, so the hours field is unbounded and the value may
         * be negative. Reducing it modulo 24 h through a {@code LocalTime} (the
         * previous implementation) turned {@code -01:00:00} into
         * {@code 23:00:00} and {@code 25:30:00} into {@code 01:30:00}, silently
         * (Spec 07.03 section 3.2).</p>
         *
         * @param value the signed microsecond total (a {@link Long})
         * @return the formatted time
         */
        public static String convert(Object value) {
            long micros = ((Number) value).longValue();
            if (micros == Long.MIN_VALUE) {
                // Not a MySQL TIME (|value| > 838 h); refuse rather than misformat.
                throw new IllegalArgumentException("MicroTime value out of range: " + micros);
            }
            String sign = micros < 0 ? "-" : "";
            long magnitude = Math.abs(micros);
            long hours = magnitude / MICROS_PER_HOUR;
            long minutes = (magnitude % MICROS_PER_HOUR) / MICROS_PER_MINUTE;
            long seconds = (magnitude % MICROS_PER_MINUTE) / MICROS_PER_SECOND;
            long fraction = magnitude % MICROS_PER_SECOND;
            return String.format("%s%02d:%02d:%02d.%06d", sign, hours, minutes, seconds, fraction);
        }
    }

    public static class MicroTimestampConverter {
        // DATETIME(4), DATETIME(5), DATETIME(6)
        // Represents the number of microseconds past the epoch and does not include time zone information.
        //ToDO: IF values exceed the ones supported by clickhouse
        public static String convert(Object value, ZoneId sourceTimezone,
                                     ZoneId serverTimezone, ClickHouseDataType clickHouseDataType) {
            return convert(value, sourceTimezone, serverTimezone, clickHouseDataType, null);
        }

        /**
         * As {@link #convert(Object, ZoneId, ZoneId, ClickHouseDataType)}, rendering
         * a converted instant in the zone the target column declares.
         *
         * @param columnTimeZone the zone declared by the ClickHouse column type
         *                       (e.g. {@code DateTime64(6, 'UTC')}); null when the
         *                       column declares none, in which case ClickHouse
         *                       parses the literal in the session zone and the
         *                       value is rendered in {@code serverTimezone}
         *                       (Spec 07.03 section 3.1.3)
         */
        public static String convert(Object value, ZoneId sourceTimezone,
                                     ZoneId serverTimezone, ClickHouseDataType clickHouseDataType,
                                     ZoneId columnTimeZone) {
            return convert(value, sourceTimezone, serverTimezone, clickHouseDataType, columnTimeZone,
                    RangePolicy.CLAMP);
        }

        /**
         * As {@link #convert(Object, ZoneId, ZoneId, ClickHouseDataType, ZoneId)},
         * applying {@code policy} to a value outside the ClickHouse range
         * (Spec 07.03 section 3.3). The policy-less overloads saturate and WARN.
         */
        public static String convert(Object value, ZoneId sourceTimezone,
                                     ZoneId serverTimezone, ClickHouseDataType clickHouseDataType,
                                     ZoneId columnTimeZone, RangePolicy policy) {
            ZoneId formatZone = columnTimeZone == null ? serverTimezone : columnTimeZone;
            Long epochMicroSeconds = (Long) value;

            //DateTime64 has a 8 digit precision.
            DateTimeFormatter destFormatter = EIGHT_DIGIT_FORMAT;
            if(clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = SECONDS_FORMAT;
            }
            long epochSeconds = epochMicroSeconds / 1_000_000L;
            long nanoOffset = ( epochMicroSeconds % 1_000_000L ) * 1_000L ;

            // Debezium encoded the zone-less digits as a UTC epoch: the digits, read as UTC.
            LocalDateTime wall = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds, nanoOffset), ZoneOffset.UTC);
            if (sameZone(sourceTimezone, serverTimezone)) {
                // The digits are the source's wall time. They are exact in the column zone
                // unless that zone has a gap or an overlap at that wall time (Spec 07.03 3.1.1).
                return sameZoneWall(wall, formatZone, clickHouseDataType, destFormatter, policy,
                        MICROS_FORMAT);
            }

            // Different zones: the digits are a wall time in the source zone (Spec 07.03 3.1.1).
            return differentZoneWall(wall, sourceTimezone, formatZone, clickHouseDataType, destFormatter, policy,
                    MICROS_FORMAT);
        }
    }

    public static class TimestampConverter {

        /**
         * Function to convert Debezium Timestamp fields to DATETIME(0), DATETIME(1), DATETIME(2), DATETIME(3)
         * Input represents number of milliseconds from Epoch and does not include timezone information.
         * Timestamp does not have microseconds
         * ISO formatted String.
         *
         * @param value
         * @return
         */
        public static String convert(Object value, ClickHouseDataType clickHouseDataType, ZoneId sourceTimeZone, ZoneId serverTimezone) {
            return convert(value, clickHouseDataType, sourceTimeZone, serverTimezone, null);
        }

        /**
         * As {@link #convert(Object, ClickHouseDataType, ZoneId, ZoneId)}, rendering
         * a converted instant in the zone the target column declares.
         *
         * @param columnTimeZone the zone declared by the ClickHouse column type;
         *                       null when the column declares none, in which case
         *                       the value is rendered in {@code serverTimezone}
         *                       (Spec 07.03 section 3.1.3)
         */
        public static String convert(Object value, ClickHouseDataType clickHouseDataType, ZoneId sourceTimeZone,
                                     ZoneId serverTimezone, ZoneId columnTimeZone) {
            return convert(value, clickHouseDataType, sourceTimeZone, serverTimezone, columnTimeZone,
                    RangePolicy.CLAMP);
        }

        /**
         * As {@link #convert(Object, ClickHouseDataType, ZoneId, ZoneId, ZoneId)},
         * applying {@code policy} to a value outside the ClickHouse range
         * (Spec 07.03 section 3.3). The policy-less overloads saturate and WARN.
         */
        public static String convert(Object value, ClickHouseDataType clickHouseDataType, ZoneId sourceTimeZone,
                                     ZoneId serverTimezone, ZoneId columnTimeZone, RangePolicy policy) {
            DateTimeFormatter destFormatter = MILLIS_FORMAT;

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = SECONDS_FORMAT;
            }

            ZoneId formatZone = columnTimeZone == null ? serverTimezone : columnTimeZone;
            Long epochMillis = (Long) value;

            LocalDateTime wallTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
            if (sameZone(sourceTimeZone, serverTimezone)) {
                // Debezium encoded the zone-less digits as a UTC epoch; the digits are the source's
                // wall time. Exact in the column zone unless that zone has a gap or an overlap at
                // that wall time (Spec 07.03 section 3.1.1), which sameZoneWall reports.
                return sameZoneWall(wallTime, formatZone, clickHouseDataType, destFormatter, policy,
                        MILLIS_FORMAT);
            }

            // Explicitly different zones: the digits are a wall time in the source zone (Spec 07.03 3.1.1).
            return differentZoneWall(wallTime, sourceTimeZone, formatZone, clickHouseDataType, destFormatter,
                    policy, MILLIS_FORMAT);
        }

        /**
         * Renders an epoch-millisecond value that already IS an instant, such as
         * Debezium's {@code source.ts_ms}, as epoch text for a {@code DateTime64}
         * column (Spec 12.04 section 3.3). The value is never reinterpreted as
         * a wall time in any zone: {@link #convert(Object, ClickHouseDataType,
         * ZoneId, ZoneId)} is for zone-less DATETIME digits and shifts a true
         * instant by the source zone's offset when source and server zones differ.
         *
         * <p>The one second before the epoch is the exclusion of Spec 07.03 section 3.1.4
         * (ClickHouse drops the sign of epoch text there): it is bound as digits
         * in {@code columnZone}, as {@link #bindsAsEpochText} prescribes for every
         * other converter. Those digits are exact: 1969-12-31 has no overlap hour.
         *
         * @param epochMillis milliseconds since 1970-01-01T00:00:00Z
         * @param columnZone the zone of the {@code DateTime64} column
         * @return {@code <seconds>.<6 fraction digits>}, or {@code yyyy-MM-dd HH:mm:ss.SSS}
         *         in {@code columnZone} for the one second before the epoch
         */
        public static String instantEpochText(long epochMillis, ZoneId columnZone) {
            Instant instant = Instant.ofEpochMilli(epochMillis);
            if (!bindsAsEpochText(ClickHouseDataType.DateTime64, instant)) {
                return instant.atZone(columnZone).format(MILLIS_FORMAT);
            }
            return epochText(instant);
        }


        public static String convertWithoutTimeZoneAdjustment(Object value, ClickHouseDataType clickHouseDataType, ZoneId sourceTimeZone, ZoneId serverTimezone) {
            DateTimeFormatter destFormatter = MILLIS_FORMAT;

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = SECONDS_FORMAT;
            }

            Long epochMillis = (Long) value;
            // Step 1: Convert from incorrect timezone to LocalDateTime
            //LocalDateTime wrongTime = LocalDateTime.ofInstant(ofEpochMilli(epochMillis), sourceTimeZone);


            // Add this offset to wrongly calculated epoch.
            Instant i = Instant.ofEpochMilli(epochMillis);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDTWithLimits = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
            // The (possibly saturated) instant is formatted as wall time in serverTimezone, the zone ClickHouse
            // reads this text in. UTC text would read back hours late (the old out-of-range defect).
            return modifiedDTWithLimits.atZone(serverTimezone).format(destFormatter).toString();
        }

        /**
         * Converts timestamp with nanosecond precision for DateTime64 columns.
         * 
         * @param epochSeconds seconds from epoch
         * @param nanoAdjustment nanoseconds within the second (0-999999999)
         * @param clickHouseDataType the target ClickHouse data type
         * @param sourceTimeZone source timezone
         * @param serverTimezone server timezone
         * @return formatted timestamp string with nanosecond precision
         */
        public static String convertWithoutTimeZoneAdjustmentNanos(long epochSeconds, int nanoAdjustment, 
                ClickHouseDataType clickHouseDataType, ZoneId sourceTimeZone, ZoneId serverTimezone) {
            // Use 9-digit nanosecond precision for DateTime64
            DateTimeFormatter destFormatter = NANOS_FORMAT;

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = SECONDS_FORMAT;
            } else if (clickHouseDataType == ClickHouseDataType.DateTime64) {
                // DateTime64 supports up to nanosecond precision
                destFormatter = NANOS_FORMAT;
            }

            Instant i = Instant.ofEpochSecond(epochSeconds, nanoAdjustment);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDTWithLimits = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
            return modifiedDTWithLimits.atZone(serverTimezone).format(destFormatter);
        }

        public static String convertWithoutTimeZoneAdjustmentNanos(long epochNanoseconds,
                                                                   ClickHouseDataType clickHouseDataType,  ZoneId serverTimezone) {
            DateTimeFormatter destFormatter = NANOS_FORMAT;

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = SECONDS_FORMAT;
            } else if (clickHouseDataType == ClickHouseDataType.DateTime64) {
                // DateTime64 supports up to nanosecond precision
                destFormatter = NANOS_FORMAT;
            }
            Instant instant = Instant.ofEpochSecond(
                    epochNanoseconds / 1_000_000_000,
                    epochNanoseconds % 1_000_000_000);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDTWithLimits = checkIfDateTimeExceedsSupportedRange(instant, clickHouseDataType, rangeExceeded);
            if (rangeExceeded[0]) {
                return modifiedDTWithLimits.atZone(ZoneOffset.UTC).format(destFormatter);
            }
            return modifiedDTWithLimits.atZone(serverTimezone).format(destFormatter);
        }
    }


    /**
     * As {@link #checkIfDateTimeExceedsSupportedRange(Instant, ClickHouseDataType, boolean[], int)}
     * at the default DateTime64 scale ({@link DataTypeRange#DATETIME64_DEFAULT_SCALE}).
     * Used only by the policy-less overloads, which have no column to read a scale from.
     */
    public static Instant checkIfDateTimeExceedsSupportedRange(Instant providedDateTime, ClickHouseDataType clickHouseDataType, boolean[] rangeExceeded) {
        return checkIfDateTimeExceedsSupportedRange(providedDateTime, clickHouseDataType, rangeExceeded,
                DataTypeRange.DATETIME64_DEFAULT_SCALE);
    }

    /**
     * Bounds an instant to the range of {@code clickHouseDataType}. For DateTime64 the
     * upper bound depends on {@code scale} (Spec 07.03 section 3.1.8).
     *
     * @param scale the DateTime64 scale of the target column, 0..9; ignored for DateTime/DateTime32
     */
    public static Instant checkIfDateTimeExceedsSupportedRange(Instant providedDateTime, ClickHouseDataType clickHouseDataType,
                                                               boolean[] rangeExceeded, int scale) {
        rangeExceeded[0] = false;

        if(clickHouseDataType == ClickHouseDataType.DateTime ||
                clickHouseDataType == ClickHouseDataType.DateTime32) {
            if(providedDateTime.isBefore(Instant.from(ofEpochMilli(DataTypeRange.DATETIME32_MIN)))) {
                rangeExceeded[0] = true;
                return Instant.ofEpochSecond(DataTypeRange.DATETIME32_MIN);
            } else if(providedDateTime.isAfter(Instant.ofEpochSecond(DataTypeRange.DATETIME32_MAX))) {
                rangeExceeded[0] = true;
                return Instant.ofEpochSecond(DataTypeRange.DATETIME32_MAX);
            }
        } else if(clickHouseDataType == ClickHouseDataType.DateTime64) {
            Instant max = DataTypeRange.dateTime64Max(scale);
            if (providedDateTime.isBefore(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64)) {
                rangeExceeded[0] = true;
                return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64;
            } else if (providedDateTime.isAfter(max)) {
                rangeExceeded[0] = true;
                return max;
            }
        }

        return providedDateTime;
    }
    public static class DateConverter {


        /**
         * MySQL: The DATE type is used for values with a date part but no time part.
         * MySQL retrieves and displays DATE values in 'YYYY-MM-DD' format. The supported range is '1000-01-01' to '9999-12-31'.
         *
         * Function to convert Debezium Date fields to the calendar day they name.
         * A {@link LocalDate}, not a {@code java.sql.Date}: {@code java.sql.Date.valueOf}
         * builds the value at local midnight in the JVM zone, and where that midnight
         * does not exist the day moves (Pacific/Apia skipped 2011-12-30). The bind
         * writes {@code yyyy-MM-dd} text (Spec 07.03 section 3.1.7).
         * @param value - NUMBER OF DAYS since epoch.
         * @return the day, zone-free
         */
        public static LocalDate convert(Object value, ClickHouseDataType chDataType) {
            return convert(value, chDataType, RangePolicy.CLAMP);
        }

        /**
         * As {@link #convert(Object, ClickHouseDataType)}, applying {@code policy}
         * to a value outside the ClickHouse Date/Date32 range (Spec 07.03
         * section 3.3). The policy-less overload saturates and WARNs.
         */
        public static LocalDate convert(Object value, ClickHouseDataType chDataType, RangePolicy policy) {
            int epochInDays = policy.boundDate((Integer) value, chDataType);
            return LocalDate.ofEpochDay(epochInDays);
        }

        /**
         * Function to check if the data exceeds the range.
         * Based on the Data types, the limits for Date and Date32 are checked and returned.
         * @param epochInDays
         * @param chDataType
         * @return
         */
        public static Integer checkIfDateExceedsSupportedRange(Integer epochInDays, ClickHouseDataType chDataType) {

            if(chDataType == ClickHouseDataType.Date32) {
                if (epochInDays < DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32) {
                    return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32;
                } else if (epochInDays > DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32) {
                    return DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32;
                }
            } else if(chDataType == ClickHouseDataType.Date) {
                if(epochInDays < 0) {
                    return 0;
                } else if(epochInDays > BinaryStreamUtils.U_INT16_MAX) {
                    return BinaryStreamUtils.U_INT16_MAX;
                }
            } else {
                log.warn("Unknown DATE field:" + chDataType);
            }

            return epochInDays;

        }
    }

    public static class ZonedTimestampConverter {

        /**
         * PostgreSQL timestamptz special values, delivered by Debezium as
         * these literal strings.
         */
        private static final String POSITIVE_INFINITY = "infinity";
        private static final String NEGATIVE_INFINITY = "-infinity";

        /**
         * Function to convert timestamp(with timezone)
         * to formatted timestamp(DateTime clickhouse)
         * @param value
         * @return
         */
        public static String convert(Object value, ZoneId serverTimezone) {
            return convert(value, serverTimezone, RangePolicy.CLAMP);
        }

        /**
         * As {@link #convert(Object, ZoneId)}, applying {@code policy} to an
         * instant outside the DateTime64 range (Spec 07.03 section 3.3). The
         * PostgreSQL {@code infinity} / {@code -infinity} literals keep their
         * documented saturation under either policy: they are not out-of-range
         * numbers but values with no finite representation.
         */
        public static String convert(Object value, ZoneId serverTimezone, RangePolicy policy) {
            return convert(value, serverTimezone, null, policy);
        }

        /**
         * As {@link #convert(Object, ZoneId, RangePolicy)}, for a target column
         * of {@code targetType}. Into a {@code DateTime64} column the bounded
         * instant is rendered as epoch text (Spec 07.03 section 3.1.4), which
         * ClickHouse parses exactly in any column zone; wall-clock digits in
         * {@code serverTimezone} name two instants in a fall-back overlap hour
         * and ClickHouse stores the first. A {@code DateTime} or {@code String}
         * target (or a null type, the contract of the older overloads) keeps
         * the digits rendering of section 3.1.3.
         */
        public static String convert(Object value, ZoneId serverTimezone, ClickHouseDataType targetType,
                                     RangePolicy policy) {
            return convert(value, serverTimezone, targetType, policy, false);
        }

        /**
         * As {@link #convert(Object, ZoneId, ClickHouseDataType, RangePolicy)}. When
         * {@code mysqlTimestamp} is true the value is known to come from a MySQL
         * {@code TIMESTAMP} column, whose smallest real value is 1970-01-01 00:00:01
         * UTC; the instant 1970-01-01 00:00:00 UTC exactly is then the zero TIMESTAMP
         * {@code 0000-00-00 00:00:00}, which has no ClickHouse value of its own. It is
         * stored as the epoch and reported as {@code ZERO_TIMESTAMP} (Spec 07.03
         * section 3.1.15): counted and logged by default, refused under
         * {@code clamp.out.of.range=false}. A PostgreSQL {@code timestamptz} at the
         * epoch is a real value and must be passed with {@code false}.
         */
        public static String convert(Object value, ZoneId serverTimezone, ClickHouseDataType targetType,
                                     RangePolicy policy, boolean mysqlTimestamp) {
            Instant bounded = boundedInstant(value, serverTimezone, policy);
            if (mysqlTimestamp && Instant.EPOCH.equals(bounded)) {
                policy.lossy(LossyConversionReporter.Kind.ZERO_TIMESTAMP, "0000-00-00 00:00:00",
                        "1970-01-01 00:00:00 UTC");
            }
            if (bindsAsEpochText(targetType, bounded)) {
                return epochText(bounded);
            }
            return ZonedDateTime.ofInstant(bounded, serverTimezone).format(MICROS_FORMAT.withZone(serverTimezone));
        }

        /**
         * Parses the Debezium {@code ZonedTimestamp} text and applies
         * {@code policy} to an instant outside the DateTime64 range.
         */
        static Instant boundedInstant(Object value, ZoneId serverTimezone, RangePolicy policy) {

            // PostgreSQL timestamptz accepts the special values infinity and
            // -infinity, which Debezium delivers verbatim as these literal
            // strings (PostgresValueConverter#convertTimestampWithZone). They match
            // none of the patterns below, so without handling them here the value
            // silently becomes an empty string. ClickHouse DateTime64 cannot
            // represent an actual infinity, so saturate to the bounds of the target
            // type - the same clamping already applied to out-of-range timestamps
            // below - which preserves the PostgreSQL ordering semantics that
            // infinity sorts after, and -infinity before, every other timestamp.
            // https://github.com/Altinity/clickhouse-sink-connector/issues/1231
            if (value instanceof String) {
                String literal = ((String) value).trim();
                if (POSITIVE_INFINITY.equalsIgnoreCase(literal)) {
                    return DataTypeRange.dateTime64Max(policy.scale());
                } else if (NEGATIVE_INFINITY.equalsIgnoreCase(literal)) {
                    return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64;
                }
            }

            // The order of this array matters,
            // for example you might truncate microseconds
            // to milliseconds(3) if .SSS is above .SSSSSS
            String[] date_formats = {
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SXXX",
                    "yyyy-MM-dd'T'HH:mm:ssXXX",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSSSZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SSZ",
                    "yyyy-MM-dd'T'HH:mm:ss.SZ",
                    "yyyy-MM-dd'T'HH:mm:ssZ",
                    "yyyy-MM-dd'T'HH:mm:ss"
            };

            ZonedDateTime parsed = null;
            for (String formatString : date_formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatString)
                            .withZone(serverTimezone);
                    parsed = ZonedDateTime.parse((String) value, formatter.withZone(serverTimezone));
                    break;
                } catch (Exception e) {
                    // Continue to next format
                }
            }
            if (parsed == null) {
                // Previously logged and returned "" -- an empty string bound
                // for a TIMESTAMP the source holds (Spec 07.06 section 3.3).
                throw new IllegalArgumentException(String.format(
                        "ZonedTimestamp value '%s'%s matches none of the accepted ISO-8601 forms; "
                                + "refusing to store an empty string in its place",
                        value, policy.column() == null ? "" : " for column " + policy.column()));
            }
            // Bounded outside the parse loop: a rejected value must fail the
            // batch, not be mistaken for a format mismatch.
            return policy.boundZonedTimestamp(parsed.toInstant());
        }
    }

    /**
     * Removes trailing zeros and an optional trailing dot from the input string.
     *
     * @param data The string to be processed.
     * @return the string without trailing zeros and dot.
     */
    static public String removeTrailingZeros(String data) {
        String result = "";
        if (data != null) {
            result = StringUtils.stripEnd(StringUtils.stripEnd(data, "0"), ".");
        }
        return result;
    }

    /**
     * BigDecimalConverter provides a method to truncate a BigDecimal
     * value based on supported limits.
     */
    public static class BigDecimalConverter {

        /**
         * Truncates the provided BigDecimal value to the maximum or minimum
         * supported value if it exceeds the ClickHouse limits.
         *
         * @param value the BigDecimal value to be truncated.
         * @return the truncated BigDecimal value.
         */
        public BigDecimal truncate(BigDecimal value) {
            return truncate(value, RangePolicy.CLAMP);
        }

        /**
         * As {@link #truncate(BigDecimal)}, applying {@code policy} to a value
         * outside the Decimal128 range (Spec 07.03 section 3.3). The
         * policy-less overload saturates and WARNs.
         *
         * @param value the BigDecimal value to bound.
         * @param policy the out-of-range policy.
         * @return the bounded value.
         */
        public BigDecimal truncate(BigDecimal value, RangePolicy policy) {
            return policy.boundDecimal(value);
        }
    }
}

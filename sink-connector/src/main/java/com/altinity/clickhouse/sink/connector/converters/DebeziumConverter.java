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
import java.sql.Date;import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneOffsetTransition;
import java.time.temporal.ChronoUnit;

import static java.time.Instant.ofEpochMilli;

public class DebeziumConverter {

    private static final int MICROS_IN_SEC = 1000000;
    private static final int MICROS_IN_MILLI = 1000;

    private static final Logger log = LogManager.getLogger(DebeziumConverter.class);

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
     * the documented mapping of the sentinel, not an event: it is logged at
     * DEBUG only (Spec 07.03 section 3.3 rule 2). At WARN it flooded the log
     * -- one line per row was 98% of a connector log, and one line per
     * column per minute still buried the messages that matter under a
     * steady stream across hundreds of bitemporal columns.
     */
    public static final class RangePolicy {

        /**
         * Saturate to the bound (logged at DEBUG). Used by the policy-less
         * converter overloads, which have no configuration to consult.
         */
        public static final RangePolicy CLAMP = new RangePolicy(true, null);

        /** Fail the batch ({@code clamp.out.of.range=false}). */
        public static final RangePolicy STRICT = new RangePolicy(false, null);

        private static final DateTimeFormatter BOUND_FORMAT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

        private final boolean clamp;
        private final String column;

        private RangePolicy(boolean clamp, String column) {
            this.clamp = clamp;
            this.column = column;
        }

        /**
         * The policy for one bound column, from the connector configuration.
         *
         * @param config the connector configuration (null means the default, which saturates)
         * @param column the column being bound, e.g. {@code db.orders.expires_at};
         *               may be null when unknown
         * @return the policy
         */
        public static RangePolicy of(ClickHouseSinkConnectorConfig config, String column) {
            boolean clamp = config == null || config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.CLAMP_OUT_OF_RANGE.toString());
            return new RangePolicy(clamp, column);
        }

        /** Whether out-of-range values are saturated rather than rejected. */
        public boolean clamps() {
            return clamp;
        }

        /** The column label carried into messages, or null. */
        public String column() {
            return column;
        }

        /**
         * Bounds a DateTime/DateTime64 instant; {@code rangeExceeded[0]} is set
         * when the value was outside the range (the callers then render the
         * bound in UTC).
         */
        Instant boundDateTime(Instant provided, ClickHouseDataType type, boolean[] rangeExceeded) {
            Instant bounded = checkIfDateTimeExceedsSupportedRange(provided, type, rangeExceeded);
            if (rangeExceeded[0]) {
                report(provided.toString(), BOUND_FORMAT.format(bounded), type.name(), dateTimeBounds(type));
            }
            return bounded;
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
            long millis = provided.toEpochMilli();
            Instant bounded = provided;
            if (millis > BinaryStreamUtils.DATETIME64_MAX * 1000) {
                bounded = Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MAX);
            } else if (millis < BinaryStreamUtils.DATETIME64_MIN * 1000) {
                bounded = Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MIN);
            }
            if (bounded != provided) {
                report(provided.toString(), BOUND_FORMAT.format(bounded), ClickHouseDataType.DateTime64.name(),
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

        private void report(String provided, String bounded, String type, String bounds) {
            String where = column == null ? "" : " for column " + column;
            if (clamp) {
                // The documented mapping, not an event: DEBUG only (spec 07.03
                // section 3.3 rule 2). Never WARN here -- a bitemporal schema
                // saturates on every row of every table.
                log.debug("Value {}{} is outside the ClickHouse {} range {}; stored as {} ({}=true)",
                        provided, where, type, bounds, bounded,
                        ClickHouseSinkConnectorConfigVariables.CLAMP_OUT_OF_RANGE);
                return;
            }
            throw new ValueOutOfRangeException(String.format(
                    "Value %s%s is outside the ClickHouse %s range %s. Refusing to store %s in its "
                            + "place: the source never held that value. Widen the ClickHouse column "
                            + "type, or return to the default %s=true to saturate out-of-range values "
                            + "(logged at DEBUG only).",
                    provided, where, type, bounds, bounded,
                    ClickHouseSinkConnectorConfigVariables.CLAMP_OUT_OF_RANGE));
        }

        private static String dateTimeBounds(ClickHouseDataType type) {
            if (type == ClickHouseDataType.DateTime || type == ClickHouseDataType.DateTime32) {
                return "[" + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MIN) + " .. "
                        + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME32_MAX) + "]";
            }
            return "[" + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME64_MIN) + " .. "
                    + DataTypeRange.epochSecondsToDateString(DataTypeRange.DATETIME64_MAX) + "]";
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
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSS");
            if(clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            }
            long epochSeconds = epochMicroSeconds / 1_000_000L;
            long nanoOffset = ( epochMicroSeconds % 1_000_000L ) * 1_000L ;

            if (sourceTimezone.equals(serverTimezone)) {
                // Replication: Debezium encoded local digits as UTC epoch; decode back to the same digits by
                // formatting as UTC. Preserves gap times (e.g. 02:00 on spring-forward day) that no real zone
                // can format as local wall clock.
                long seconds = epochMicroSeconds / 1_000_000L;
                long nanos = (epochMicroSeconds % 1_000_000L) * 1_000L;
                Instant i = Instant.ofEpochSecond(seconds, nanos);
                boolean[] rangeExceeded = new boolean[1];
                Instant modifiedDT = policy.boundDateTime(i, clickHouseDataType, rangeExceeded);
                return modifiedDT.atZone(ZoneOffset.UTC).format(destFormatter);
            }

            // sourceTimezone != serverTimezone: offset correction for "wrong epoch" encoding
            LocalDateTime localDT = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds, nanoOffset), ZoneOffset.UTC);
            ZonedDateTime zonedInSource = localDT.atZone(sourceTimezone);
            ZoneOffsetTransition transition = sourceTimezone.getRules().getTransition(localDT);
            int sourceOffset;
            if (transition != null && transition.isGap()) {
                sourceOffset = transition.getOffsetBefore().getTotalSeconds() * 1000;
            } else {
                sourceOffset = zonedInSource.getOffset().getTotalSeconds() * 1000;
            }

            long sourceOffsetMicros = sourceOffset * 1000L;

            Long epochMicrosWithOffset = epochMicroSeconds - sourceOffsetMicros;
            long seconds = epochMicrosWithOffset / 1_000_000;
            long nanos = (epochMicrosWithOffset % 1_000_000) * 1_000;

            Instant i = Instant.ofEpochSecond(seconds, nanos);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDT = policy.boundDateTime(i, clickHouseDataType, rangeExceeded);
            if(rangeExceeded[0]) {
                return modifiedDT.atZone(ZoneOffset.UTC).format(destFormatter).toString();
            }
            return modifiedDT.atZone(formatZone).format(destFormatter);
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
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            }

            ZoneId formatZone = columnTimeZone == null ? serverTimezone : columnTimeZone;
            Long epochMillis = (Long) value;
            boolean[] rangeExceeded = new boolean[1];

            if (sourceTimeZone.equals(serverTimezone)) {
                // Debezium encoded the zone-less DATETIME digits as a UTC epoch
                // (LocalDateTime.toInstant(UTC)); the only decode that returns
                // the same digits is the inverse: format as UTC. Pushing the
                // digits through a real DST zone -- the previous
                // TimeZone.getRawOffset / inDaylightTime code -- moved every
                // spring-forward gap time back an hour (2026-03-08 02:30:00
                // America/Chicago became 01:30:00), silently. Same rule as
                // MicroTimestampConverter (Spec 07.03 section 3.1.1).
                Instant encoded = Instant.ofEpochMilli(epochMillis);
                Instant modifiedDTWithLimits = policy.boundDateTime(encoded, clickHouseDataType, rangeExceeded);
                return modifiedDTWithLimits.atZone(ZoneOffset.UTC).format(destFormatter);
            }

            // Explicitly different zones: the digits are a wall time in the
            // source zone. A wall time inside a spring-forward gap does not
            // exist in that zone; take the offset before the transition, as
            // MicroTimestampConverter does, so both converters agree.
            LocalDateTime wallTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
            ZoneOffsetTransition transition = sourceTimeZone.getRules().getTransition(wallTime);
            ZoneOffset sourceOffset = (transition != null && transition.isGap())
                    ? transition.getOffsetBefore()
                    : sourceTimeZone.getRules().getOffset(wallTime);
            Instant i = wallTime.toInstant(sourceOffset);

            Instant modifiedDTWithLimits = policy.boundDateTime(i, clickHouseDataType, rangeExceeded);
            if (rangeExceeded[0]) {
                // return the modifiedDTWithLimits as a string without timezone conversion
                return modifiedDTWithLimits.atZone(ZoneOffset.UTC).format(destFormatter);
            }
            return modifiedDTWithLimits.atZone(formatZone).format(destFormatter);
        }


        public static String convertWithoutTimeZoneAdjustment(Object value, ClickHouseDataType clickHouseDataType, ZoneId sourceTimeZone, ZoneId serverTimezone) {
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            }

            Long epochMillis = (Long) value;
            // Step 1: Convert from incorrect timezone to LocalDateTime
            //LocalDateTime wrongTime = LocalDateTime.ofInstant(ofEpochMilli(epochMillis), sourceTimeZone);


            // Add this offset to wrongly calculated epoch.
            Instant i = Instant.ofEpochMilli(epochMillis);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDTWithLimits = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
            if (rangeExceeded[0]) {
                // return the modifiedDTWithLimits as a string without timezone conversion
                return modifiedDTWithLimits.atZone(ZoneOffset.UTC).format(destFormatter).toString();
            }
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
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            } else if (clickHouseDataType == ClickHouseDataType.DateTime64) {
                // DateTime64 supports up to nanosecond precision
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
            }

            Instant i = Instant.ofEpochSecond(epochSeconds, nanoAdjustment);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDTWithLimits = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
            if (rangeExceeded[0]) {
                return modifiedDTWithLimits.atZone(ZoneOffset.UTC).format(destFormatter);
            }
            return modifiedDTWithLimits.atZone(serverTimezone).format(destFormatter);
        }

        public static String convertWithoutTimeZoneAdjustmentNanos(long epochNanoseconds,
                                                                   ClickHouseDataType clickHouseDataType,  ZoneId serverTimezone) {
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            } else if (clickHouseDataType == ClickHouseDataType.DateTime64) {
                // DateTime64 supports up to nanosecond precision
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS");
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


    public static Instant checkIfDateTimeExceedsSupportedRange(Instant providedDateTime, ClickHouseDataType clickHouseDataType, boolean[] rangeExceeded) {
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
            if (providedDateTime.isBefore(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64)) {
                rangeExceeded[0] = true;
                return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64;
            } else if (providedDateTime.isAfter(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64)) {
                rangeExceeded[0] = true;
                return DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64;
            }
        }

        return providedDateTime;
    }
    public static class DateConverter {


        /**
         * MySQL: The DATE type is used for values with a date part but no time part.
         * MySQL retrieves and displays DATE values in 'YYYY-MM-DD' format. The supported range is '1000-01-01' to '9999-12-31'.
         *
         * Function to convert Debezium Date fields
         * to java.sql.Date
         * @param value - NUMBER OF DAYS since epoch.
         * @return
         */
        public static Date convert(Object value, ClickHouseDataType chDataType) {
            return convert(value, chDataType, RangePolicy.CLAMP);
        }

        /**
         * As {@link #convert(Object, ClickHouseDataType)}, applying {@code policy}
         * to a value outside the ClickHouse Date/Date32 range (Spec 07.03
         * section 3.3). The policy-less overload saturates and WARNs.
         */
        public static Date convert(Object value, ClickHouseDataType chDataType, RangePolicy policy) {
            int epochInDays = policy.boundDate((Integer) value, chDataType);
            LocalDate d = LocalDate.ofEpochDay(epochInDays);

            return Date.valueOf(d);
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

            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                    .withZone(serverTimezone);

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
                    return ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MAX),
                            serverTimezone).format(destFormatter);
                } else if (NEGATIVE_INFINITY.equalsIgnoreCase(literal)) {
                    return ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MIN),
                            serverTimezone).format(destFormatter);
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
            Instant bounded = policy.boundZonedTimestamp(parsed.toInstant());
            return ZonedDateTime.ofInstant(bounded, serverTimezone).format(destFormatter);
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

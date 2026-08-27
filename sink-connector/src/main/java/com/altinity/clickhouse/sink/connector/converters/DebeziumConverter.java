package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.format.BinaryStreamUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.sql.Date;import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.zone.ZoneOffsetTransition;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static java.time.Instant.ofEpochMilli;

public class DebeziumConverter {

    private static final int MICROS_IN_SEC = 1000000;
    private static final int MICROS_IN_MILLI = 1000;

    /**
     * Renders a MySQL TIME value as HH:mm:ss with only the fractional digits
     * the value actually carries -- no fraction at all when it is zero.
     *
     * <p>A fixed "HH:mm:ss.SSSSSS" pattern cannot be right here, because the
     * Debezium schema for a TIME column carries no scale: TIME(0) 16:01:25 and
     * TIME(6) 16:01:25.000000 arrive as the same wire value, so no rendering
     * can reproduce both source texts. Trailing zeros in a fixed-point
     * fraction carry no value information, so omitting them never discards
     * anything the source sent; padding them on, by contrast, asserts a
     * microsecond scale the converter has no evidence for and that the
     * default TIME(0) column does not have. See issue #1215.
     *
     * <p>{@code appendFraction} with a zero minimum width emits the decimal
     * point only when there are digits to follow it, and trims the fraction
     * to its significant digits -- so .123456 and .1 both survive intact.
     *
     * <p>{@code removeTrailingZeros} below is deliberately not reused: it
     * strips zeros from the whole string, so a fraction-less "16:01:20" would
     * become "16:01:2".
     */
    private static final DateTimeFormatter TIME_FORMATTER =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 6, true)
                    .toFormatter();

    /**
     * Renders a MySQL TIME given as a signed count of microseconds, covering
     * the whole declared range -838:59:59 .. 838:59:59.
     *
     * <p>{@link LocalTime} is a clock reading, so it can represent neither a
     * negative TIME nor one past 24 hours; routing such a value through it
     * wraps it onto a 24-hour clock and produces a plausible-looking wrong
     * value with no error. MySQL TIME is a DURATION, and both ends of its
     * range are ordinary values in real schemas -- elapsed times, deltas, and
     * differences between two timestamps all produce them.
     *
     * <p>Debezium hands the value over unclamped: JdbcValueConverters calls
     * {@code MicroTime.toMicroOfDay(value, acceptLargeValues)} /
     * {@code Time.toMilliOfDay(value, acceptLargeValues)} with
     * {@code acceptLargeValues = supportsLargeTimeValues()}, which is true for
     * ADAPTIVE, ADAPTIVE_TIME_MICROSECONDS, MICROSECONDS and NANOSECONDS --
     * every mode this connector runs under. With that flag set, Debezium
     * skips its own range check and returns the raw {@code Duration}, so a
     * negative or over-24h TIME reaches this converter intact and it is this
     * converter's job not to lose it.
     *
     * <p>The output stays a plain string because the destination column is
     * ClickHouse String (see the (INT32, Time) and (INT64, MicroTime) entries
     * in ClickHouseDataTypeMapper), and MySQL's own TIME literal syntax is
     * what a reader and a checksum comparison both expect. The sign leads the
     * entire value, exactly as MySQL renders it: -01:30:00.5, never
     * -01:-30:-00.5. The hour field is NOT zero-padded past two digits, again
     * matching MySQL, so 838:59:59 keeps three.
     *
     * <p>Fraction handling is identical to {@link #TIME_FORMATTER}: only the
     * digits actually present are emitted, and trailing zeros are dropped.
     * Both are derived from the same value here, so an in-range value renders
     * byte-for-byte the same whichever path produced it.
     *
     * @param totalMicros signed microseconds; negative means a negative TIME
     * @return the MySQL TIME text for that duration
     */
    private static String renderSignedTime(long totalMicros) {

        // Math.abs on Long.MIN_VALUE returns itself, which would silently emit
        // a negative field. No MySQL TIME can reach that magnitude (the range
        // caps at ~3.02e12 micros against a ~9.22e18 limit), but the guard
        // costs nothing and turns an impossible input into a visible one.
        if (totalMicros == Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                    "TIME value out of range: " + totalMicros + " microseconds");
        }

        boolean negative = totalMicros < 0;
        long micros = Math.abs(totalMicros);

        long totalSeconds = micros / MICROS_IN_SEC;
        long fraction = micros % MICROS_IN_SEC;

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder out = new StringBuilder(16);
        if (negative) {
            out.append('-');
        }
        if (hours < 10) {
            out.append('0');
        }
        out.append(hours).append(':');
        if (minutes < 10) {
            out.append('0');
        }
        out.append(minutes).append(':');
        if (seconds < 10) {
            out.append('0');
        }
        out.append(seconds);

        if (fraction != 0) {
            // Six digits, then strip the trailing zeros the source did not
            // send -- the same rule TIME_FORMATTER applies via
            // appendFraction(..., 0, 6, true). String.format is avoided on
            // this per-row path.
            String digits = Long.toString(MICROS_IN_SEC + fraction).substring(1);
            int end = digits.length();
            while (end > 1 && digits.charAt(end - 1) == '0') {
                end--;
            }
            out.append('.').append(digits, 0, end);
        }

        return out.toString();
    }

    /**
     * True when the value fits a 24-hour clock and can therefore be rendered
     * by {@link #TIME_FORMATTER} unchanged.
     */
    private static boolean fitsOnAClock(long totalMicros) {
        return totalMicros >= 0 && totalMicros < 86400L * MICROS_IN_SEC;
    }

    private static final Logger log = LogManager.getLogger(DebeziumConverter.class);


    public static class MicroTimeConverter {
        /**
         * Function to convert Long(Epoch)
         * to Formatted String(Time)
         *
         * <p>Input is io.debezium.time.MicroTime: the number of microseconds
         * past midnight. Debezium emits it for MySQL TIME(4)..TIME(6) under
         * the default ADAPTIVE precision mode, and for TIME at every
         * precision under ADAPTIVE_TIME_MICROSECONDS and MICROSECONDS.
         *
         * @param value microseconds past midnight
         * @return the time rendered with only the fractional digits it has
         */
        public static String convert(Object value) {

            long micros = ((Number) value).longValue();

            // A negative or over-24h TIME has no LocalTime, so it is rendered
            // directly from the duration. See renderSignedTime.
            if (!fitsOnAClock(micros)) {
                return renderSignedTime(micros);
            }

            Instant i = Instant.EPOCH.plus(micros, ChronoUnit.MICROS);

            LocalTime time = i.atZone(ZoneOffset.UTC).toLocalTime();

            return time.format(TIME_FORMATTER);
        }
    }

    public static class TimeConverter {
        /**
         * Function to convert Integer(millis past midnight)
         * to Formatted String(Time)
         *
         * <p>Input is io.debezium.time.Time: an INT32 count of milliseconds
         * past midnight, which is what Debezium emits for MySQL
         * TIME(0)..TIME(3) under the default ADAPTIVE precision mode. This is
         * the millisecond counterpart of {@link MicroTimeConverter}; without
         * it an INT32 TIME value reached no time branch at all and was bound
         * as a raw integer. See issue #1215.
         *
         * @param value milliseconds past midnight
         * @return the time rendered with only the fractional digits it has
         */
        public static String convert(Object value) {

            long millis = ((Number) value).longValue();
            long micros = millis * MICROS_IN_MILLI;

            // A negative or over-24h TIME has no LocalTime, so it is rendered
            // directly from the duration. See renderSignedTime.
            if (!fitsOnAClock(micros)) {
                return renderSignedTime(micros);
            }

            Instant i = Instant.EPOCH.plus(millis, ChronoUnit.MILLIS);

            LocalTime time = i.atZone(ZoneOffset.UTC).toLocalTime();

            return time.format(TIME_FORMATTER);
        }
    }

    public static class MicroTimestampConverter {
        // DATETIME(4), DATETIME(5), DATETIME(6)
        // Represents the number of microseconds past the epoch and does not include time zone information.
        //ToDO: IF values exceed the ones supported by clickhouse
        public static String convert(Object value, ZoneId sourceTimezone,
                                     ZoneId serverTimezone, ClickHouseDataType clickHouseDataType) {
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
                Instant modifiedDT = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
                return modifiedDT.atZone(ZoneOffset.UTC).format(destFormatter).toString();
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
            Instant modifiedDT = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
            if(rangeExceeded[0]) {
                return modifiedDT.atZone(ZoneOffset.UTC).format(destFormatter).toString();
            }
            return modifiedDT.atZone(serverTimezone).format(destFormatter).toString();
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
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

            if (clickHouseDataType == ClickHouseDataType.DateTime || clickHouseDataType == ClickHouseDataType.DateTime32) {
                destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            }

            Long epochMillis = (Long) value;
            // Step 1: Convert from incorrect timezone to LocalDateTime
            //LocalDateTime wrongTime = LocalDateTime.ofInstant(ofEpochMilli(epochMillis), sourceTimeZone);

            // Get the milliseconds value of the timezone.
            TimeZone sourceTZ = TimeZone.getTimeZone(sourceTimeZone);
            int sourceOffset = sourceTZ.getRawOffset();
            Long epochMillisWithOffset = epochMillis - sourceOffset;

            if (sourceTZ.inDaylightTime(Date.from(Instant.ofEpochMilli(epochMillisWithOffset)))) {
                Long dstOffset = (long) (sourceTZ.getRawOffset() + sourceTZ.getDSTSavings());
                epochMillisWithOffset = epochMillis - dstOffset;
            }

            // Add this offset to wrongly calculated epoch.
            Instant i = Instant.ofEpochMilli(epochMillisWithOffset);

            boolean[] rangeExceeded = new boolean[1];
            Instant modifiedDTWithLimits = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType, rangeExceeded);
            if (rangeExceeded[0]) {
                // return the modifiedDTWithLimits as a string without timezone conversion
                return modifiedDTWithLimits.atZone(ZoneOffset.UTC).format(destFormatter).toString();
            }
            return modifiedDTWithLimits.atZone(serverTimezone).format(destFormatter).toString();
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
            Integer epochInDays = checkIfDateExceedsSupportedRange((Integer) value, chDataType);
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

            String result = "";
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

            boolean parsingSuccesful = false;
            for (String formatString : date_formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatString)
                            .withZone(serverTimezone);
                    ZonedDateTime zd = ZonedDateTime.parse((String) value,
                            formatter.withZone(serverTimezone));

                    long dateTimeInMs = zd.toInstant().toEpochMilli();
                    if (dateTimeInMs > BinaryStreamUtils.DATETIME64_MAX * 1000) {
                        zd = ZonedDateTime.ofInstant(
                                Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MAX),
                                serverTimezone);
                    } else if (dateTimeInMs < BinaryStreamUtils.DATETIME64_MIN * 1000) {
                        zd = ZonedDateTime.ofInstant(
                                Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MIN),
                                serverTimezone);
                    }
                    result = zd.format(destFormatter);
                    parsingSuccesful = true;
                    break;
                } catch (Exception e) {
                    // Continue to next format
                }
            }
            if (parsingSuccesful == false) {
                log.error("Error parsing zonedtimestamp " + (String) value);
            }
            return result;
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
            if (value.compareTo(BinaryStreamUtils.DECIMAL128_MAX) > 0) {
                log.warn("Decimal value {} is greater than max value {}",
                        value, BinaryStreamUtils.DECIMAL128_MAX);
                return BinaryStreamUtils.DECIMAL128_MAX;
            } else if (value.compareTo(BinaryStreamUtils.DECIMAL128_MIN) < 0) {
                log.warn("Decimal value {} is less than min value {}",
                        value, BinaryStreamUtils.DECIMAL128_MIN);
                return BinaryStreamUtils.DECIMAL128_MIN;
            } else {
                return value;
            }
        }
    }
}

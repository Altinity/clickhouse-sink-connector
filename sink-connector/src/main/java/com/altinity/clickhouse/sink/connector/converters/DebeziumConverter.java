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
import java.time.zone.ZoneOffsetTransition;
import java.time.temporal.ChronoUnit;
import java.util.TimeZone;

import static java.time.Instant.ofEpochMilli;

public class DebeziumConverter {

    private static final int MICROS_IN_SEC = 1000000;
    private static final int MICROS_IN_MILLI = 1000;

    private static final Logger log = LogManager.getLogger(DebeziumConverter.class);


    public static class MicroTimeConverter {
        /**
         * Function to convert Long(Epoch microseconds)
         * to Formatted String(Time).
         *
         * Handles MySQL TIME range -838:59:59.000000 to 838:59:59.000000
         * which exceeds Java LocalTime's 00:00-23:59 range.
         * Computes hours/minutes/seconds directly from microsecond value.
         *
         * @param value epoch microseconds
         * @return formatted time string (e.g., "10:30:00.000000" or "-01:30:00.000000")
         */
        public static String convert(Object value) {
            // Accept any Number: Debezium may deliver an Integer for small TIME
            // values, and a direct (Long) cast throws ClassCastException. The
            // sibling converters already use this widening cast.
            long totalMicros = ((Number) value).longValue();
            boolean negative = totalMicros < 0;
            long absMicros = Math.abs(totalMicros);

            long totalSeconds = absMicros / 1_000_000L;
            long remainingMicros = absMicros % 1_000_000L;

            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;

            String sign = negative ? "-" : "";
            return String.format("%s%02d:%02d:%02d.%06d",
                    sign, hours, minutes, seconds, remainingMicros);
        }
    }

    public static class MicroTimestampConverter {
        // DATETIME(4), DATETIME(5), DATETIME(6)
        // Represents the number of microseconds past the epoch and does not include time zone information.
        //ToDO: IF values exceed the ones supported by clickhouse
        public static String convert(Object value, ZoneId sourceTimezone,
                                     ZoneId serverTimezone, ClickHouseDataType clickHouseDataType) {
            Long epochMicroSeconds = ((Number) value).longValue();

            // DateTime64 — use 6-digit microsecond precision matching the input.
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
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

            Long epochMillis = ((Number) value).longValue();
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

            Long epochMillis = ((Number) value).longValue();
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
         * Function to convert timestamp(with timezone)
         * to formatted timestamp(DateTime clickhouse)
         * @param value
         * @return
         */
        public static String convert(Object value, ZoneId serverTimezone) {

            String result = "";
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                    .withZone(serverTimezone);

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
         * <p>The clamp targets the <b>Decimal128</b> bounds
         * (&plusmn;10<sup>38</sup>), not the wider Decimal256 bounds
         * (&plusmn;10<sup>76</sup>), and that is deliberate: the clamped value
         * has to survive serialization by the JDBC driver, and the driver
         * checks a <em>scaled</em> magnitude, not the raw one.
         * {@code BinaryStreamUtils.writeDecimal256} multiplies the value by
         * {@code 10^scale} and then requires the product to satisfy
         * {@code |v| < 10^76} exclusive. A column such as
         * {@code Decimal(64,18)} therefore leaves only 76 - 18 = 58 digits of
         * integral headroom. Clamping to {@code DECIMAL256_MIN}
         * (10<sup>76</sup>, 77 digits) produces a 95-digit product that fails
         * that check with
         * {@code IllegalArgumentException: BigDecimal(...) should be between
         * -10^76 and 10^76}, so the batch that was supposed to be rescued by
         * the clamp is rejected instead — the exact failure this method
         * exists to prevent. {@code DECIMAL128_MIN}/{@code MAX} (39 digits)
         * still fits after scaling for every scale ClickHouse permits.</p>
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

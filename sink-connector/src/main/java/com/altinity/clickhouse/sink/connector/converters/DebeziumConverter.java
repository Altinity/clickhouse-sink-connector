package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import com.clickhouse.data.ClickHouseChecker;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.data.ClickHouseValues;
import com.clickhouse.data.format.BinaryStreamUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class DebeziumConverter {

    private static final int MICROS_IN_SEC = 1000000;
    private static final int MICROS_IN_MILLI = 1000;

    private static final Logger log = LoggerFactory.getLogger(DebeziumConverter.class);


    public static class MicroTimeConverter {
        /**
         * Function to convert Long(Epoch)
         * to Formatted String(Time)
         * @param value
         * @return
         */
        public static String convert(Object value) {

            Instant i = Instant.EPOCH.plus((Long) value, ChronoUnit.MICROS);

            LocalTime time = i.atZone(ZoneOffset.UTC).toLocalTime();
            String formattedSecondsTimestamp= time.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS"));

            return formattedSecondsTimestamp;
            //return removeTrailingZeros(formattedSecondsTimestamp);
        }
    }

    public static class MicroTimestampConverter {
        // DATETIME(4), DATETIME(5), DATETIME(6)
        // Represents the number of microseconds past the epoch and does not include time zone information.
        //ToDO: IF values exceed the ones supported by clickhouse
        public static String convert(Object value, ZoneId serverTimezone) {
            Long microTimestamp = (Long) value;

            //Long milliTimestamp = microTimestamp / MICROS_IN_MILLI;
            //Instant receivedDT = Instant.ofEpochMilli(microTimestamp/MICROS_IN_MILLI).plusNanos(microTimestamp%1_000);
            //Instant receivedDT = Instant.ofEpochMilli(microTimestamp/MICROS_IN_MILLI).pl
            Instant receivedDT = Instant.EPOCH.plus(microTimestamp, ChronoUnit.MICROS).atZone(serverTimezone).toInstant();
            long result = receivedDT.atZone(serverTimezone).toEpochSecond();

            if(result < BinaryStreamUtils.DATETIME64_MIN) {
                //return Timestamp.from(Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MIN));
                return Timestamp.valueOf(LocalDateTime.of(LocalDate.of(1925, 1, 2), LocalTime.MIN)).toString();

            } else if(result > BinaryStreamUtils.DATETIME64_MAX) {
                //return Timestamp.from(Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MAX));
                return Timestamp.valueOf(LocalDateTime.of(LocalDate.of(2283, 11, 10), LocalTime.MAX)).toString();

            }
            System.out.println("MICROTIMESTAMP result" + result);
            System.out.println("TIMESTAMP result" + Timestamp.from(receivedDT).toString());
            return Timestamp.from(receivedDT).toString();
            //return Timestamp.from(Instant.ofEpochSecond(result));
        }
    }

    public static class TimestampConverter {

        /**
         * Function to convert Debezium Timestamp fields to DATETIME(0), DATETIME(1), DATETIME(2), DATETIME(3)
         * Input represents number of milliseconds from Epoch and does not include timezone information.
         * Timestamp does not have microseconds
         * ISO formatted String.
         * @param value
         * @return
         */
        public static String convert(Object value, ClickHouseDataType clickHouseDataType, ZoneId serverTimezone) {
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            // Input is a long.
            Instant i = Instant.ofEpochMilli((long) value);
            Instant modifiedDT = checkIfDateTimeExceedsSupportedRange(i, clickHouseDataType);
            return modifiedDT.atZone(serverTimezone).format(destFormatter).toString();
        }
    }

    public static Instant checkIfDateTimeExceedsSupportedRange(Instant providedDateTime, ClickHouseDataType clickHouseDataType) {

        if(clickHouseDataType == ClickHouseDataType.DateTime ||
                clickHouseDataType == ClickHouseDataType.DateTime32) {
            if(providedDateTime.getEpochSecond() < DataTypeRange.DATETIME32_MIN) {
                return Instant.ofEpochSecond(DataTypeRange.DATETIME32_MIN);
            } else if(providedDateTime.getEpochSecond() > DataTypeRange.DATETIME32_MAX) {
                return Instant.ofEpochSecond(DataTypeRange.DATETIME32_MAX);
            }
        } else if(clickHouseDataType == ClickHouseDataType.DateTime64) {
            if (providedDateTime.isBefore(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64)) {
                return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME64;
            } else if (providedDateTime.isAfter(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME64)) {
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
         * @param value
         * @return
         */
        public static Date convert(Object value, ClickHouseDataType chDataType) {
            Integer epochInDays = checkIfDateExceedsSupportedRange((Integer) value, chDataType);
            LocalDate d = LocalDate.ofEpochDay(epochInDays);

            return Date.valueOf(d);
        }

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
                log.error("Unknown DATE field");
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

//            TemporalAccessor parsedTime = ZonedTimestamp.FORMATTER.parse((String) value);
//            DateTimeFormatter bqZonedTimestampFormat =
//                    new DateTimeFormatterBuilder()
//                            .append(DateTimeFormatter.ISO_LOCAL_DATE)
//                            .appendLiteral(' ')
//                            .append(DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS"))
//                            .toFormatter();
//            return bqZonedTimestampFormat.format(parsedTime);

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
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatString).withZone(serverTimezone);
                    ZonedDateTime zd = ZonedDateTime.parse((String) value, formatter.withZone(serverTimezone));

                    long dateTimeInMs = zd.toInstant().toEpochMilli();
                    if(dateTimeInMs > BinaryStreamUtils.DATETIME64_MAX) {
                        zd = ZonedDateTime.ofInstant(Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MAX), serverTimezone);
                    } else if(dateTimeInMs < BinaryStreamUtils.DATETIME64_MIN) {
                        zd = ZonedDateTime.ofInstant(Instant.ofEpochSecond(BinaryStreamUtils.DATETIME64_MIN), serverTimezone);
                    }
                    //long v = ClickHouseChecker.between(ClickHouseValues.UTC_ZONE.equals(this.zoneId) ? dt.toEpochSecond(ZoneOffset.UTC) : dt.atZone(this.zoneId).toEpochSecond(), "DateTime", BinaryStreamUtils.DATETIME64_MIN, BinaryStreamUtils.DATETIME64_MAX);

                    result = zd.format(destFormatter);
                    //result = removeTrailingZeros(result);
                    parsingSuccesful = true;
                    break;
                } catch(Exception e) {
                    // Continue
                }
            }
            if(parsingSuccesful == false) {
                log.error("Error parsing zonedtimestamp " + (String) value);
            }

            return result;
        }
    }

    static public String removeTrailingZeros(String data) {
        String result = "";

        if(data != null) {
            result = StringUtils.stripEnd(StringUtils.stripEnd(data, "0"), ".");
        }

        return result;
    }
}

package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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

        //ToDO: IF values exceed the ones supported by clickhouse
        public static Timestamp convert(Object value) {
            Long microTimestamp = (Long) value;

            //Long milliTimestamp = microTimestamp / MICROS_IN_MILLI;
            //Instant receivedDT = Instant.ofEpochMilli(microTimestamp/MICROS_IN_MILLI).plusNanos(microTimestamp%1_000);
            Instant receivedDT = Instant.EPOCH.plus(microTimestamp, ChronoUnit.MICROS);
            Instant modifiedDT = checkIfDateTimeExceedsSupportedRange(receivedDT, true);

            return Timestamp.from(modifiedDT);
        }
    }

    public static class TimestampConverter {

        /**
         * Function to convert Debezium Timestamp fields to DATETIME(0), DATETIME(1), DATETIME(2)
         * Timestamp does not have microseconds
         * ISO formatted String.
         * @param value
         * @return
         */
        public static String convert(Object value, boolean isDateTime64) {
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            Instant providedDT = Instant.ofEpochMilli((long) value);

            Instant modifiedDT = checkIfDateTimeExceedsSupportedRange(providedDT, isDateTime64);

            //return modifiedDT.toEpochMilli();
            return ZonedDateTime.ofInstant(modifiedDT, ZoneId.of("UTC")).format(destFormatter);
        }


    }

    public static Instant checkIfDateTimeExceedsSupportedRange(Instant providedDateTime, boolean isDateTime64) {

        if(providedDateTime.isBefore(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME)) {
            return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME;
        } else if (providedDateTime.isAfter(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME)){
            return DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME;
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
        public static Date convert(Object value) {
            Integer epochInDays = checkIfDateExceedsSupportedRange((Integer) value);

            // The value is epoch in Days
            long msSinceEpoch = TimeUnit.DAYS.toMillis(epochInDays);
            java.util.Date date = new java.util.Date(msSinceEpoch);


            return new java.sql.Date(date.getTime());
        }

        public static Integer checkIfDateExceedsSupportedRange(Integer epochInDays) {

            if(epochInDays < DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32) {
                return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATE32;
            } else if (epochInDays > DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32){
                return DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATE32;
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
        public static String convert(Object value, String timeZone) {

//            TemporalAccessor parsedTime = ZonedTimestamp.FORMATTER.parse((String) value);
//            DateTimeFormatter bqZonedTimestampFormat =
//                    new DateTimeFormatterBuilder()
//                            .append(DateTimeFormatter.ISO_LOCAL_DATE)
//                            .appendLiteral(' ')
//                            .append(DateTimeFormatter.ofPattern("HH:mm:ss.SSSSSS"))
//                            .toFormatter();
//            return bqZonedTimestampFormat.format(parsedTime);

            String result = "";
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

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

            boolean parsingSuccessful = false;
            ZonedDateTime parsedDT = null;
            for (String formatString : date_formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatString);
                    parsedDT = ZonedDateTime.parse((String) value, formatter.withZone(ZoneId.of("UTC")));
                    parsingSuccessful = true;
                    break;
                } catch(Exception e) {
                    if (e.getCause() != null) {
                        parsingSuccessful = true;
                        break;
                    }
                }
            }

            if (parsingSuccessful) {
                Instant i;
                if (parsedDT ==  null) {
                    i = Instant.MIN;
                } else {
                    i = parsedDT.toInstant();
                }
                //check date range
                i = checkIfDateTimeExceedsSupportedRange(i, true);
                result = ZonedDateTime.ofInstant(i, ZoneId.of(timeZone)).format(destFormatter);
            } else {
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

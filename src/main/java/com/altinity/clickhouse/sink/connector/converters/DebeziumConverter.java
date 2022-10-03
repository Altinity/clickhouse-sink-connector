package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;

import java.sql.Date;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

public class DebeziumConverter {

    private static final int MICROS_IN_SEC = 1000000;
    private static final int MICROS_IN_MILLI = 1000;

    public static class MicroTimeConverter {
        /**
         * Function to convert Long(Epoch)
         * to Formatted String(Time)
         * @param value
         * @return
         */
        public static String convert(Object value) {
            Long milliTimestamp = (Long) value / 1000;
            java.util.Date date = new java.util.Date(milliTimestamp);

            SimpleDateFormat bqTimeSecondsFormat = new SimpleDateFormat("HH:mm:ss");
            String formattedSecondsTimestamp = bqTimeSecondsFormat.format(date);
            return formattedSecondsTimestamp;
        }
    }

    public static class MicroTimestampConverter {

        //ToDO: IF values exceed the ones supported by clickhouse
        public static String convert(Object value) {
            Long microTimestamp = (Long) value;

            Long milliTimestamp = microTimestamp / MICROS_IN_MILLI;
            java.util.Date date = new java.util.Date(milliTimestamp);

            SimpleDateFormat bqDatetimeSecondsFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            bqDatetimeSecondsFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            String formattedSecondsTimestamp = bqDatetimeSecondsFormat.format(date);

            Long microRemainder = microTimestamp % MICROS_IN_SEC;

            return formattedSecondsTimestamp;
        }
    }

    public static class TimestampConverter {

        /**
         * Function to convert Debezium Timestamp fields to
         * ISO formatted String.
         * @param value
         * @return
         */
        public static String convert(Object value, boolean isDateTime64) {
            LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) value), ZoneId.of("UTC"));

            LocalDateTime modifiedDate = checkIfDateTimeExceedsSupportedRange(date, isDateTime64);
            //DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            return modifiedDate.format(destFormatter);
        }

        public static LocalDateTime checkIfDateTimeExceedsSupportedRange(LocalDateTime providedDateTime, boolean isDateTime64) {

            if(providedDateTime.isBefore(DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME)) {
                return DataTypeRange.CLICKHOUSE_MIN_SUPPORTED_DATETIME;
            } else if (providedDateTime.isAfter(DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME)){
                return DataTypeRange.CLICKHOUSE_MAX_SUPPORTED_DATETIME;
            }

            return providedDateTime;

        }
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
        public static String convert(Object value) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
            LocalDateTime zd = LocalDateTime.parse((String) value, formatter);
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return zd.format(destFormatter);
        }
    }
}

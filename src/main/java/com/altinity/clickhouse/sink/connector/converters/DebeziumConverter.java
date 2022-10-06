package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.metadata.DataTypeRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Date;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
            Long milliTimestamp = (Long) value / 1000;
            java.util.Date date = new java.util.Date(milliTimestamp);

            SimpleDateFormat bqTimeSecondsFormat = new SimpleDateFormat("HH:mm:ss");
            String formattedSecondsTimestamp = bqTimeSecondsFormat.format(date);
            return formattedSecondsTimestamp;
        }
    }

    public static class MicroTimestampConverter {

        //ToDO: IF values exceed the ones supported by clickhouse
        public static Timestamp convert(Object value) {
            Long microTimestamp = (Long) value;

            //Long milliTimestamp = microTimestamp / MICROS_IN_MILLI;
            LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli(microTimestamp/MICROS_IN_MILLI).plusNanos(microTimestamp%1_000), ZoneId.of("UTC"));
            LocalDateTime modifiedDate = checkIfDateTimeExceedsSupportedRange(date, true);


            return Timestamp.from(modifiedDate.toInstant(ZoneOffset.UTC));
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
        public static Long convert(Object value, boolean isDateTime64) {
            LocalDateTime date = LocalDateTime.ofInstant(Instant.ofEpochMilli((long) value), ZoneId.of("UTC"));

            LocalDateTime modifiedDate = checkIfDateTimeExceedsSupportedRange(date, isDateTime64);
            //DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
            DateTimeFormatter destFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            return modifiedDate.toInstant(ZoneOffset.UTC).toEpochMilli();
        }


    }

    public static LocalDateTime checkIfDateTimeExceedsSupportedRange(LocalDateTime providedDateTime, boolean isDateTime64) {

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
        public static String convert(Object value) {

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

            String[] date_formats = {
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.S'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'"};

            boolean parsingSuccesful = false;
            for (String formatString : date_formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatString);
                    LocalDateTime zd = LocalDateTime.parse((String) value, formatter);
                    result = zd.format(destFormatter);
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
}

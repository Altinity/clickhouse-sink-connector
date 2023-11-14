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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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
            //Instant receivedDT = Instant.ofEpochMilli(microTimestamp/MICROS_IN_MILLI).pl
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
        public static Long convert(Object value, boolean isDateTime64) {
            Instant providedDT = Instant.ofEpochMilli((long) value);

            Instant modifiedDT = checkIfDateTimeExceedsSupportedRange(providedDT, isDateTime64);

            return modifiedDT.toEpochMilli();
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

            // The order of this array matters,
            // for example you might truncate microseconds
            // to milliseconds(3) if .SSS is above .SSSSSS
            String[] date_formats = {
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.SS'Z'",
                    "yyyy-MM-dd'T'HH:mm:ss.S'Z'"
            };

            boolean parsingSuccesful = false;
            for (String formatString : date_formats) {
                try {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern(formatString);
                    LocalDateTime zd = LocalDateTime.parse((String) value, formatter);
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

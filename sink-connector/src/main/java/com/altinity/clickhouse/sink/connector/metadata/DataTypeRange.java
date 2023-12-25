package com.altinity.clickhouse.sink.connector.metadata;

import com.clickhouse.data.format.BinaryStreamUtils;
import static java.time.Instant.from;
import static java.time.Instant.ofEpochMilli;

import java.time.*;

public class DataTypeRange
{

    // Set clickhouse-jdbc limits
    public static final Integer CLICKHOUSE_MIN_SUPPORTED_DATE32 = BinaryStreamUtils.DATE32_MIN;

    public static final Integer CLICKHOUSE_MAX_SUPPORTED_DATE32 = BinaryStreamUtils.DATE32_MAX;


    public static final long DATETIME64_MAX = LocalDateTime.of(LocalDate.of(2299, 12, 31), LocalTime.MAX).toEpochSecond(ZoneOffset.UTC);
    public static final long DATETIME64_MIN = LocalDateTime.of(LocalDate.of(1900, 1, 1), LocalTime.MIN).toEpochSecond(ZoneOffset.UTC);

    // DateTime
    public static final Instant CLICKHOUSE_MIN_SUPPORTED_DATETIME64 = from(ofEpochMilli
            (DATETIME64_MIN * 1000).atZone(ZoneId.of("UTC"))).plusNanos(DATETIME64_MIN * 1000 % 1_000);
    public static final Instant CLICKHOUSE_MAX_SUPPORTED_DATETIME64 = from(ofEpochMilli
            (DATETIME64_MAX * 1000).atZone(ZoneId.of("UTC")).withHour(23).withMinute(59).withSecond(59).withNano(999999999));


    // DateTime and DateTime32
    public static final long DATETIME32_MIN = 0L;
    public static final long DATETIME32_MAX = LocalDateTime.of(LocalDate.of(2106, 02, 07), LocalTime.of(6, 28, 15)).toEpochSecond(ZoneOffset.UTC);

    // DateTime max limits.
    public static final String DATETIME_MIN="1900-01-01 00:00:00.0";
    public static final String DATETIME_MAX="2299-12-31 23:59:59.999";

    public static final String DATETIME_1_MAX = "2299-12-31 23:59:59.9";

    public static final String DATETIME_2_MAX = "2299-12-31 23:59:59.99";

    public static final String DATETIME_3_MAX = "2299-12-31 23:59:59.999";

    public static final String DATETIME_4_MAX = "2299-12-31 23:59:59.9999";

    public static final String DATETIME_5_MAX = "2299-12-31 23:59:59.99999";

    public static final String DATETIME_6_MAX = "2299-12-31 23:59:59.999999";

    public static final String DATETIME64_6_MAX = "2299-12-31 23:59:59.99999999";

}

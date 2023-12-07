package com.altinity.clickhouse.sink.connector.metadata;

import com.clickhouse.data.format.BinaryStreamUtils;
import static com.clickhouse.data.format.BinaryStreamUtils.DATETIME64_MAX;
import static com.clickhouse.data.format.BinaryStreamUtils.DATETIME64_MIN;
import static java.time.Instant.from;
import static java.time.Instant.ofEpochMilli;

import java.time.*;

public class DataTypeRange
{

    // Set clickhouse-jdbc limits
    public static final Integer CLICKHOUSE_MIN_SUPPORTED_DATE32 = BinaryStreamUtils.DATE32_MIN;

    public static final Integer CLICKHOUSE_MAX_SUPPORTED_DATE32 = BinaryStreamUtils.DATE32_MAX;


    // DateTime
    public static final Instant CLICKHOUSE_MIN_SUPPORTED_DATETIME64 = from(ofEpochMilli
            (DATETIME64_MIN * 1000).atZone(ZoneId.of("UTC"))).plusNanos(DATETIME64_MIN * 1000 % 1_000);
    public static final Instant CLICKHOUSE_MAX_SUPPORTED_DATETIME64 = from(ofEpochMilli
            (DATETIME64_MAX * 1000).atZone(ZoneId.of("UTC")).withHour(23).withMinute(59).withSecond(59).withNano(999999999));


    // DateTime and DateTime32
    public static final long DATETIME32_MIN = 0L;
    public static final long DATETIME32_MAX = LocalDateTime.of(LocalDate.of(2106, 02, 07), LocalTime.of(6, 28, 15)).toEpochSecond(ZoneOffset.UTC);
}

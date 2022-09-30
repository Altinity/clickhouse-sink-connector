package com.altinity.clickhouse.sink.connector.metadata;

import com.clickhouse.client.data.BinaryStreamUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.sql.Date;

public class DataTypeRange
{

    // Set clickhouse-jdbc limits
    public static final Date CLICKHOUSE_MIN_SUPPORTED_DATE32 = new Date(BinaryStreamUtils.DATE32_MIN);

    public static final Date CLICKHOUSE_MAX_SUPPORTED_DATE32 = new Date(BinaryStreamUtils.DATE32_MAX);


    // DateTime
    public static final LocalDateTime CLICKHOUSE_MIN_SUPPORTED_DATETIME =  Instant.ofEpochMilli
            (BinaryStreamUtils.DATETIME64_MIN * 1000).atZone(ZoneId.of("UTC")).toLocalDateTime();
    public static final LocalDateTime CLICKHOUSE_MAX_SUPPORTED_DATETIME = Instant.ofEpochMilli
            (BinaryStreamUtils.DATETIME64_MAX * 1000).atZone(ZoneId.of("UTC")).toLocalDateTime();


}

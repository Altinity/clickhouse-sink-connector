package com.altinity.clickhouse.sink.connector.metadata;

import com.clickhouse.data.format.BinaryStreamUtils;
import static java.time.Instant.from;
import static java.time.Instant.ofEpochMilli;

import java.time.*;

/**
 * This class holds constants that define upper and lower bounds for
 * various date and time data types used in ClickHouse, particularly
 * Date32, DateTime, and DateTime64.
 */
public class DataTypeRange {

    // Set clickhouse-jdbc limits

    /**
     * Minimum supported value for Date32 in ClickHouse.
     */
    public static final Integer CLICKHOUSE_MIN_SUPPORTED_DATE32 =
            BinaryStreamUtils.DATE32_MIN;

    /**
     * Maximum supported value for Date32 in ClickHouse.
     */
    public static final Integer CLICKHOUSE_MAX_SUPPORTED_DATE32 =
            BinaryStreamUtils.DATE32_MAX;

    /**
     * Maximum epoch second for DateTime64, corresponding to
     * 2299-12-31 23:59:59 UTC.
     */
    public static final long DATETIME64_MAX = LocalDateTime
            .of(LocalDate.of(2299, 12, 31), LocalTime.MAX)
            .toEpochSecond(ZoneOffset.UTC);

    /**
     * Minimum epoch second for DateTime64, corresponding to
     * 1900-01-01 00:00:00 UTC.
     */
    public static final long DATETIME64_MIN = LocalDateTime
            .of(LocalDate.of(1900, 1, 1), LocalTime.MIN)
            .toEpochSecond(ZoneOffset.UTC);

    /**
     * Minimum {@link Instant} for supported DateTime64 in ClickHouse,
     * derived from {@code DATETIME64_MIN}.
     */
    public static final Instant CLICKHOUSE_MIN_SUPPORTED_DATETIME64 =
            from(ofEpochMilli(DATETIME64_MIN * 1000)
                    .atZone(ZoneId.of("UTC")))
                    .plusNanos(DATETIME64_MIN * 1000 % 1_000);

    /** The scale used when a DateTime64 column's scale is not known: DateTime64(6). */
    public static final int DATETIME64_DEFAULT_SCALE = 6;

    /**
     * The largest DateTime64(9) value: ClickHouse stores the instant as an
     * Int64 count of nanoseconds, so 2262-04-11 23:47:16.854775807 is the
     * last representable instant (measured on 24.8.8.17 and 25.8.16:
     * 2299-12-31 23:59:59.999999999 raises DECIMAL_OVERFLOW, code 407).
     */
    private static final long DATETIME64_SCALE9_MAX_SECONDS = 9_223_372_036L;
    private static final long DATETIME64_SCALE9_MAX_NANOS = 854_775_807L;

    /**
     * The largest value of a DateTime64 with {@code scale} fractional digits.
     * For scales 0..8 it is 2299-12-31 23:59:59 followed by {@code scale}
     * nines (measured: the value with more digits is accepted and wraps for
     * scales 0..8, and is rejected for scale 9). Scale 9 is capped by the
     * Int64 nanosecond representation (see {@link #DATETIME64_SCALE9_MAX_SECONDS}).
     *
     * @param scale the number of fractional digits, 0..9
     * @return the inclusive upper bound for that scale
     * @throws IllegalArgumentException when {@code scale} is outside 0..9
     */
    public static Instant dateTime64Max(int scale) {
        if (scale < 0 || scale > 9) {
            throw new IllegalArgumentException("DateTime64 scale must be 0..9, got " + scale);
        }
        if (scale == 9) {
            return Instant.ofEpochSecond(DATETIME64_SCALE9_MAX_SECONDS, DATETIME64_SCALE9_MAX_NANOS);
        }
        long unitNanos = 1L;
        for (int i = scale; i < 9; i++) {
            unitNanos *= 10L;
        }
        return Instant.ofEpochSecond(DATETIME64_MAX, 1_000_000_000L - unitNanos);
    }

    /**
     * Maximum {@link Instant} for supported DateTime64 in ClickHouse at the
     * default scale 6: 2299-12-31 23:59:59.999999. (It was 23:59:59.000 before
     * the per-scale bounds of Spec 07.03 section 3.1.8: the fraction was
     * zeroed, which stored a value the source never held.)
     */
    public static final Instant CLICKHOUSE_MAX_SUPPORTED_DATETIME64 = dateTime64Max(DATETIME64_DEFAULT_SCALE);

    // DateTime and DateTime32

    /**
     * Minimum epoch second for DateTime32 in ClickHouse.
     */
    public static final long DATETIME32_MIN = 0L;

    /**
     * Maximum epoch second for DateTime32 in ClickHouse, corresponding
     * to 2106-02-07 06:28:15 UTC.
     */
    public static final long DATETIME32_MAX = LocalDateTime
            .of(LocalDate.of(2106, 2, 7), LocalTime.of(6, 28, 15))
            .toEpochSecond(ZoneOffset.UTC);
    public static final long DATETIME32_MAX_TTL = LocalDateTime
            .of(LocalDate.of(2100, 1, 1), LocalTime.of(0, 00, 00))
            .toEpochSecond(ZoneOffset.UTC);
    // DateTime

    /**
     * Default DateTime minimum value in ClickHouse: 1900-01-01 00:00:00.0
     */
    public static final String DATETIME_MIN = "1900-01-01 00:00:00.0";

    /**
     * Default DateTime maximum value in ClickHouse: 2299-12-31 23:59:59.0
     */
    public static final String DATETIME_MAX = "2299-12-31 23:59:59.0";

    /**
     * Additional maximum reference for DateTime with microsecond
     * scale. Same as {@code DATETIME_MAX}.
     */
    public static final String DATETIME_1_MAX = "2299-12-31 23:59:59.0";

    /**
     * Additional maximum reference for DateTime with microsecond
     * scale. Same as {@code DATETIME_MAX}.
     */
    public static final String DATETIME_2_MAX = "2299-12-31 23:59:59.0";

    /**
     * Additional maximum reference for DateTime with microsecond
     * scale. Same as {@code DATETIME_MAX}.
     */
    public static final String DATETIME_3_MAX = "2299-12-31 23:59:59.0";

    /**
     * Additional maximum reference for DateTime with microsecond
     * scale. Same as {@code DATETIME_MAX}.
     */
    public static final String DATETIME_4_MAX = "2299-12-31 23:59:59.0";

    /**
     * Additional maximum reference for DateTime with microsecond
     * scale. Same as {@code DATETIME_MAX}.
     */
    public static final String DATETIME_5_MAX = "2299-12-31 23:59:59.0";

    /**
     * Additional maximum reference for DateTime with microsecond
     * scale. Same as {@code DATETIME_MAX}.
     */
    public static final String DATETIME_6_MAX = "2299-12-31 23:59:59.0";

    /**
     * Maximum reference for DateTime64(6) in ClickHouse, referencing
     * 2299-12-31 23:59:59.0
     */
    public static final String DATETIME64_6_MAX = "2299-12-31 23:59:59.0";

    /**
     * Converts epoch seconds to a date string in the format YYYY-MM-DD HH:mm:ss.
     *
     * @param epochSeconds the epoch seconds to convert
     * @return the date string in YYYY-MM-DD HH:mm:ss format
     */
    public static String epochSecondsToDateString(long epochSeconds) {
        LocalDateTime dateTime = Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneOffset.UTC).toLocalDateTime();
        return dateTime.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}

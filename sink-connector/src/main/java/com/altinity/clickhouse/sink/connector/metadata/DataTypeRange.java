package com.altinity.clickhouse.sink.connector.metadata;

import com.clickhouse.data.format.BinaryStreamUtils;
import static java.time.Instant.from;
import static java.time.Instant.ofEpochMilli;

import java.math.BigDecimal;
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
     * The value every out-of-range DateTime64 saturates to on the HIGH side, at
     * every scale: 2299-12-31 23:59:59.000 UTC (Spec 07.03 section 3.1.8).
     *
     * <p>It is a fixed historical constant, not the bound of the column's scale,
     * and that is deliberate. A saturated value becomes part of the row's
     * ReplacingMergeTree sorting key, so it must be the SAME value for one source
     * value across connector releases and across the bulk loader. This is the
     * value every release before 2.11.1 stored and the value the loader stores
     * ({@code multiIf(... CAST(toDateTime64('2299-12-31 23:59:59', p, 'UTC') ...))}),
     * so a tombstone written after the upgrade still replaces the row it deletes.
     * Returning the per-scale bound here instead stored {@code .999999} at scale 6,
     * a different sorting key for the same source value, and rows written before
     * the upgrade stopped being replaceable (Spec 07.03 section 3.1.8).</p>
     *
     * <p>The per-scale bound in {@link #dateTime64Max(int)} stays the ACCEPTANCE
     * range: a value inside it is stored exactly and is not saturated at all, which
     * is the other half of that section's rule.</p>
     */
    public static final Instant DATETIME64_SATURATION_MAX =
            Instant.ofEpochSecond(DATETIME64_MAX, 0);

    /**
     * Maximum {@link Instant} for supported DateTime64 in ClickHouse at the
     * default scale 6: 2299-12-31 23:59:59.999999, the largest value a
     * {@code DateTime64(6)} column accepts.
     *
     * <p>It is the scale-6 ACCEPTANCE bound and nothing else. It is not the
     * saturation value ({@link #DATETIME64_SATURATION_MAX}), and it must not be
     * used to bound a column whose scale is not 6: the write path asks
     * {@link #dateTime64Max(int)} for the scale of the column it is writing.</p>
     *
     * @deprecated a constant only tells the reader about scale 6 while the bound
     *             is per column; keep the field for binary compatibility and
     *             call {@link #dateTime64Max(int)} with the column's own scale.
     */
    @Deprecated
    public static final Instant CLICKHOUSE_MAX_SUPPORTED_DATETIME64 = dateTime64Max(DATETIME64_DEFAULT_SCALE);

    /**
     * {@link #CLICKHOUSE_MAX_SUPPORTED_DATETIME64} as a method, for callers that
     * would rather not reference a deprecated constant.
     *
     * @return the scale-6 acceptance bound, 2299-12-31 23:59:59.999999
     */
    public static Instant clickHouseMaxSupportedDateTime64() {
        return dateTime64Max(DATETIME64_DEFAULT_SCALE);
    }

    /**
     * The number of decimal digits a ClickHouse {@code Decimal128} column can
     * hold: 38. The largest storable magnitude is therefore {@code 10^38 - 1}
     * (99999999999999999999999999999999999999), NOT the Java Int128 constant
     * in clickhouse-data ({@code BinaryStreamUtils.DECIMAL128_MAX} =
     * 170141183460469231731687303715884105727, which has 39 digits).
     *
     * <p>Measured on 24.8.14.10545.altinitystable: {@code
     * toDecimal128OrNull('170141183460469231731687303715884105727', 0)} returns
     * NULL and {@code toDecimal128OrNull('99999999999999999999999999999999999999', 0)}
     * returns the value, so a decimal saturated to the Java constant is a value
     * the server refuses to store. The same holds for the smaller precisions
     * ({@code toDecimal32OrNull} rejects 10 digits, {@code toDecimal64OrNull}
     * rejects 19), which is why the bound is computed from the DECLARED
     * precision rather than fixed at one number.</p>
     */
    public static final int DECIMAL128_MAX_PRECISION = 38;

    /**
     * The widest precision ClickHouse defines for a Decimal column
     * ({@code Decimal256(S)}), which is also the largest precision this class
     * will compute a bound for.
     */
    public static final int DECIMAL_MAX_PRECISION = 76;

    /**
     * The largest value a ClickHouse {@code Decimal(precision, scale)} column
     * can hold: {@code 10^(precision - scale) - 10^-scale}, the number written
     * with {@code precision} nines and a point {@code scale} digits from the
     * right. For {@code Decimal(18, 6)} that is 999999999999.999999.
     *
     * <p>{@code scale == 0} gives the whole-number bound {@code 10^precision - 1}.
     * The bound is a property of the COLUMN, so a saturated decimal must be
     * computed from the declared precision and scale, not from one constant:
     * a value larger than this cannot be stored, and bounding to something the
     * column refuses would not be a bound at all.</p>
     *
     * @param precision total significant digits, 1..{@value #DECIMAL_MAX_PRECISION}
     * @param scale     fractional digits, 0..precision
     * @return the inclusive upper bound
     * @throws IllegalArgumentException when a parameter is outside its range
     */
    public static BigDecimal decimalMax(int precision, int scale) {
        if (precision < 1 || precision > DECIMAL_MAX_PRECISION) {
            throw new IllegalArgumentException("Decimal precision must be 1.." + DECIMAL_MAX_PRECISION
                    + ", got " + precision);
        }
        if (scale < 0 || scale > precision) {
            throw new IllegalArgumentException("Decimal scale must be 0.." + precision + ", got " + scale);
        }
        return BigDecimal.TEN.pow(precision).subtract(BigDecimal.ONE).movePointLeft(scale);
    }

    /**
     * The smallest value a ClickHouse {@code Decimal(precision, scale)} column
     * can hold: the negation of {@link #decimalMax(int, int)}.
     *
     * @param precision total significant digits, 1..{@value #DECIMAL_MAX_PRECISION}
     * @param scale     fractional digits, 0..precision
     * @return the inclusive lower bound
     * @throws IllegalArgumentException when a parameter is outside its range
     */
    public static BigDecimal decimalMin(int precision, int scale) {
        return decimalMax(precision, scale).negate();
    }

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
     * Largest DateTime64(0) value in ClickHouse, as {@code java.sql.Timestamp#toString}
     * prints it: 2299-12-31 23:59:59.0
     */
    public static final String DATETIME_MAX = "2299-12-31 23:59:59.0";

    // The value an out-of-range DateTime64 source value is STORED as, in the form java.sql.Timestamp#toString
    // prints it (Spec 07.03 section 3.1.8; see DATETIME64_SATURATION_MAX). It is one value for every scale, which
    // is what keeps a saturated value a stable ReplacingMergeTree sorting key. These are NOT the acceptance bounds
    // -- those differ per scale and are computed by dateTime64Max(int). Written out as literals so a test compares
    // against an independent statement of the rule, not against the code under test.

    /** A saturated DateTime64(1) value is stored as: 2299-12-31 23:59:59.0 */
    public static final String DATETIME_1_MAX = "2299-12-31 23:59:59.0";

    /** A saturated DateTime64(2) value is stored as: 2299-12-31 23:59:59.0 */
    public static final String DATETIME_2_MAX = "2299-12-31 23:59:59.0";

    /** A saturated DateTime64(3) value is stored as: 2299-12-31 23:59:59.0 */
    public static final String DATETIME_3_MAX = "2299-12-31 23:59:59.0";

    /** A saturated DateTime64(4) value is stored as: 2299-12-31 23:59:59.0 */
    public static final String DATETIME_4_MAX = "2299-12-31 23:59:59.0";

    /** A saturated DateTime64(5) value is stored as: 2299-12-31 23:59:59.0 */
    public static final String DATETIME_5_MAX = "2299-12-31 23:59:59.0";

    /** A saturated DateTime64(6) value is stored as: 2299-12-31 23:59:59.0 */
    public static final String DATETIME_6_MAX = "2299-12-31 23:59:59.0";

    /** A saturated DateTime64(6) value, the connector's default scale: 2299-12-31 23:59:59.0 */
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

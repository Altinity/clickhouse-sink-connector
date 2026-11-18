package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter.MicroTimestampConverter;
import com.altinity.clickhouse.sink.connector.converters.DebeziumConverter.TimestampConverter;
import com.clickhouse.data.ClickHouseDataType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A zone-less DATETIME holds wall digits in the source zone. The text a converter renders is read by ClickHouse in the
 * zone of the DateTime64 column it is written to.
 * <ul>
 *   <li>A column that takes the configured zone (bare, or declared as that zone) holds the source wall instant for
 *       every source and server zone.</li>
 *   <li>An existing column declared in another zone keeps the wall digits it already holds when the source zone is
 *       the server zone. The sink does not create such a column; startup WARNs per column (spec 07.03 section 3.1.6).</li>
 * </ul>
 */
class DateTimeColumnZoneRenderingTest {

    private static final ZoneId SOURCE = ZoneId.of("America/Chicago");

    /** Wall 2026-01-15 06:00:00 in America/Chicago (UTC-6) is 2026-01-15T12:00:00Z. */
    private static final LocalDateTime WALL = LocalDateTime.parse("2026-01-15T06:00:00");
    private static final Instant EXPECTED = Instant.parse("2026-01-15T12:00:00Z");

    private static final ZoneId[] SERVER_ZONES = {ZoneId.of("UTC"), SOURCE, ZoneId.of("Asia/Tokyo")};

    @Test
    public void timestampColumnInTheConfiguredZoneHoldsTheSourceWallInstant() {
        long epochMillis = WALL.toInstant(ZoneOffset.UTC).toEpochMilli();
        for (ZoneId server : SERVER_ZONES) {
            for (ZoneId column : new ZoneId[]{null, server}) {
                String text = TimestampConverter.convert(epochMillis, ClickHouseDataType.DateTime64,
                        SOURCE, server, column);
                assertEquals(EXPECTED, readInColumnZone(text, column == null ? server : column),
                        "server=" + server + " column=" + column + " rendered=" + text);
            }
        }
    }

    @Test
    public void microTimestampColumnInTheConfiguredZoneHoldsTheSourceWallInstant() {
        Instant encoded = WALL.toInstant(ZoneOffset.UTC);
        long epochMicros = encoded.getEpochSecond() * 1_000_000L + encoded.getNano() / 1_000L;
        for (ZoneId server : SERVER_ZONES) {
            for (ZoneId column : new ZoneId[]{null, server}) {
                String text = MicroTimestampConverter.convert(epochMicros, SOURCE, server,
                        ClickHouseDataType.DateTime64, column);
                assertEquals(EXPECTED, readInColumnZone(text, column == null ? server : column),
                        "server=" + server + " column=" + column + " rendered=" + text);
            }
        }
    }

    @Test
    public void timestampExistingColumnInAnotherZoneKeepsTheWallDigitsWhenSourceIsServer() {
        long epochMillis = WALL.toInstant(ZoneOffset.UTC).toEpochMilli();
        for (ZoneId column : new ZoneId[]{ZoneId.of("UTC"), ZoneId.of("Asia/Tokyo")}) {
            String text = TimestampConverter.convert(epochMillis, ClickHouseDataType.DateTime64,
                    SOURCE, SOURCE, column);
            assertEquals(WALL.atZone(column).toInstant(), readInColumnZone(text, column),
                    "column=" + column + " rendered=" + text);
        }
    }

    @Test
    public void microTimestampExistingColumnInAnotherZoneKeepsTheWallDigitsWhenSourceIsServer() {
        Instant encoded = WALL.toInstant(ZoneOffset.UTC);
        long epochMicros = encoded.getEpochSecond() * 1_000_000L + encoded.getNano() / 1_000L;
        for (ZoneId column : new ZoneId[]{ZoneId.of("UTC"), ZoneId.of("Asia/Tokyo")}) {
            String text = MicroTimestampConverter.convert(epochMicros, SOURCE, SOURCE,
                    ClickHouseDataType.DateTime64, column);
            assertEquals(WALL.atZone(column).toInstant(), readInColumnZone(text, column),
                    "column=" + column + " rendered=" + text);
        }
    }

    /**
     * The instant ClickHouse reads from a rendered value: epoch text (digits and a dot) is an instant in any zone;
     * wall text (with a colon) is a wall time in the column's declared zone.
     */
    private static Instant readInColumnZone(String text, ZoneId columnZone) {
        if (text.indexOf(':') >= 0) {
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(columnZone).toInstant();
        }
        BigDecimal epoch = new BigDecimal(text);
        long seconds = epoch.setScale(0, RoundingMode.FLOOR).longValueExact();
        long nanos = epoch.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValueExact();
        return Instant.ofEpochSecond(seconds, nanos);
    }
}

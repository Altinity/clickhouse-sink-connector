package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.VariableScaleDecimal;
import io.debezium.data.geometry.Geometry;
import io.debezium.data.geometry.Point;
import io.debezium.time.NanoTimestamp;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spec 07.07 sections 3.6 and 3.7 (NEW-J7).
 *
 * <p>(a) The WKB and variable-scale-decimal branches read a {@link ByteBuffer} through {@code duplicate()}, so the
 * caller's buffer keeps its position and limit and a retry or second pass reads the same bytes; {@code rewind()}
 * reset a non-zero position to 0 and made the next read include bytes before the payload.</p>
 *
 * <p>(b) {@code io.debezium.time.NanoTimestamp} (declared {@code DateTime64(9, 'UTC')} by the DDL mapping) is
 * converted in {@code convert()} to a nanosecond-precision literal instead of binding the raw epoch count.</p>
 */
public class ClickHouseDataTypeMapperByteBufferNanoTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final int PREFIX = 4;

    private static ClickHouseSinkConnectorConfig config(Map<String, String> extra) {
        return new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>(extra)));
    }

    private static PreparedStatement recording(AtomicReference<Object> bound) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setString":
                        case "setBytes":
                        case "setObject":
                        case "setBigDecimal":
                            bound.set(args[1]);
                            return null;
                        case "isWrapperFor":
                            return false;
                        case "toString":
                            return "RecordingPreparedStatement";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == args[0];
                        default:
                            return null;
                    }
                });
    }

    private static byte[] pointWkb() {
        return new WKBWriter().write(new GeometryFactory().createPoint(new Coordinate(1, 2)));
    }

    /** A buffer whose payload starts at {@link #PREFIX}: position PREFIX, limit past the payload, bytes before it are noise. */
    private static ByteBuffer bufferWithPrefix(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(PREFIX + payload.length);
        buffer.put(new byte[]{(byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE});
        buffer.put(payload);
        buffer.position(PREFIX);
        return buffer;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    @Test
    @DisplayName("a Geometry WKB ByteBuffer keeps its position and limit and converts identically on a second pass")
    void geometryWkbBufferIsNotMutated() throws Exception {
        byte[] wkb = pointWkb();
        ByteBuffer buffer = bufferWithPrefix(wkb);
        Struct geometry = new Struct(Geometry.schema());
        geometry.put("wkb", buffer);

        AtomicReference<Object> first = new AtomicReference<>();
        ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME, geometry, 1,
                recording(first), config(Map.of()), ClickHouseDataType.String, UTC);
        assertEquals(PREFIX, buffer.position(), "position is the caller's, not rewound to 0");
        assertEquals(PREFIX + wkb.length, buffer.limit());

        AtomicReference<Object> second = new AtomicReference<>();
        ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Geometry.LOGICAL_NAME, geometry, 1,
                recording(second), config(Map.of()), ClickHouseDataType.String, UTC);

        assertEquals(hex(wkb), first.get());
        assertEquals(first.get(), second.get(), "a retry reads the same bytes");
    }

    @Test
    @DisplayName("a Point WKB ByteBuffer bound into a String column keeps its position and limit")
    void pointWkbBufferIsNotMutated() throws Exception {
        byte[] wkb = pointWkb();
        ByteBuffer buffer = bufferWithPrefix(wkb);
        Struct point = new Struct(Point.builder().build());
        point.put("x", 1.0d);
        point.put("y", 2.0d);
        point.put("wkb", buffer);

        for (int pass = 0; pass < 2; pass++) {
            AtomicReference<Object> bound = new AtomicReference<>();
            ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, Point.LOGICAL_NAME, point, 1,
                    recording(bound), config(Map.of()), ClickHouseDataType.String, UTC);
            assertEquals(hex(wkb), bound.get(), "pass " + pass);
            assertEquals(PREFIX, buffer.position(), "pass " + pass);
            assertEquals(PREFIX + wkb.length, buffer.limit(), "pass " + pass);
        }
    }

    @Test
    @DisplayName("a variable-scale decimal ByteBuffer keeps its position and limit and decodes the same value twice")
    void variableScaleDecimalBufferIsNotMutated() throws Exception {
        byte[] unscaled = BigInteger.valueOf(12345).toByteArray();
        ByteBuffer buffer = bufferWithPrefix(unscaled);
        Struct decimal = new Struct(VariableScaleDecimal.schema());
        decimal.put("scale", 2);
        decimal.put("value", buffer);

        for (int pass = 0; pass < 2; pass++) {
            AtomicReference<Object> bound = new AtomicReference<>();
            ClickHouseDataTypeMapper.convert(Schema.Type.STRUCT, VariableScaleDecimal.LOGICAL_NAME, decimal, 1,
                    recording(bound), config(Map.of()), ClickHouseDataType.Decimal, UTC);
            assertEquals(0, new BigDecimal("123.45").compareTo((BigDecimal) bound.get()), "pass " + pass);
            assertEquals(PREFIX, buffer.position(), "pass " + pass);
            assertEquals(PREFIX + unscaled.length, buffer.limit(), "pass " + pass);
        }
    }

    @Test
    @DisplayName("NanoTimestamp is bound as a nine-digit literal, not as the raw epoch count")
    void nanoTimestampIsConverted() throws Exception {
        long nanos = 1_700_000_000_123_456_789L; // 2023-11-14 22:13:20.123456789 UTC
        AtomicReference<Object> bound = new AtomicReference<>();

        ClickHouseDataTypeMapper.convert(Schema.Type.INT64, NanoTimestamp.SCHEMA_NAME, nanos, 1,
                recording(bound), config(Map.of()), ClickHouseDataType.DateTime64, UTC, UTC);

        assertEquals("2023-11-14 22:13:20.123456789", bound.get());
    }

    @Test
    @DisplayName("a NanoTimestamp before the epoch keeps its nanoseconds (floor semantics)")
    void nanoTimestampBeforeEpoch() throws Exception {
        long nanos = -1L; // 1969-12-31 23:59:59.999999999
        AtomicReference<Object> bound = new AtomicReference<>();

        ClickHouseDataTypeMapper.convert(Schema.Type.INT64, NanoTimestamp.SCHEMA_NAME, nanos, 1,
                recording(bound), config(Map.of()), ClickHouseDataType.DateTime64, UTC, UTC);

        assertEquals("1969-12-31 23:59:59.999999999", bound.get());
    }

    @Test
    @DisplayName("NanoTimestamp digits are the source wall time: a different source zone shifts into the column zone")
    void nanoTimestampHonoursSourceZone() throws Exception {
        Map<String, String> props = Map.of(
                ClickHouseSinkConnectorConfigVariables.SOURCE_DATETIME_TIMEZONE.toString(), "America/Chicago");
        long nanos = 1_700_000_000_123_456_789L; // wall time 22:13:20.123456789
        AtomicReference<Object> bound = new AtomicReference<>();

        ClickHouseDataTypeMapper.convert(Schema.Type.INT64, NanoTimestamp.SCHEMA_NAME, nanos, 1,
                recording(bound), config(props), ClickHouseDataType.DateTime64, UTC, UTC,
                DebeziumConverter.RangePolicy.CLAMP);

        // 22:13:20 in America/Chicago (UTC-6 in November) is 04:13:20 the next day in UTC.
        assertEquals("2023-11-15 04:13:20.123456789", bound.get());
    }

    @Test
    @DisplayName("NanoTimestamp is mapped to a DateTime64 ClickHouse type in the lookup table")
    void nanoTimestampHasADataTypeMapping() {
        assertNotNull(ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT64, NanoTimestamp.SCHEMA_NAME));
        assertEquals(ClickHouseDataType.DateTime64,
                ClickHouseDataTypeMapper.getClickHouseDataType(Schema.Type.INT64, NanoTimestamp.SCHEMA_NAME));
    }
}

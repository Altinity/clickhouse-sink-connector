package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.Enum;
import io.debezium.data.EnumSet;
import io.debezium.data.Json;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I15 failure modes of the value path of domain 07: poison values
 * that reach {@link ClickHouseDataTypeMapper#convert}. For each one the
 * question is whether the row is bound faithfully, refused loudly, or bound
 * as a value the source never held.
 *
 * <p>The enabled tests pin behaviour that is correct on 2.11.0. The disabled
 * tests assert the correct behaviour for a defect recorded in the failure-modes
 * section of the named spec; each fails on 2.11.0 (verified when it was
 * written).</p>
 */
public class ClickHouseDataTypeMapperPoisonValueTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** One recorded bind: the JDBC setter called and the value it received. */
    private static final class Bind {
        final String method;
        final Object value;

        Bind(String method, Object value) {
            this.method = method;
            this.value = value;
        }
    }

    private static PreparedStatement recording(List<Bind> binds) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith("set") && args != null && args.length >= 2) {
                binds.add(new Bind(name, args[1]));
                return null;
            }
            switch (name) {
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
        };
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, h);
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    private static List<Bind> bind(Schema.Type type, String schemaName, Object value, ClickHouseDataType target)
            throws Exception {
        List<Bind> binds = new ArrayList<>();
        ClickHouseDataTypeMapper.convert(type, schemaName, value, 1, recording(binds), config(), target, UTC);
        return binds;
    }

    // ------------------------------------------------------------------
    // Spec 07.02: floating point and decimals
    // ------------------------------------------------------------------

    /**
     * FM-07.02-1 (the half that works): a single-precision NaN or infinity
     * is bound through {@code setFloat}; ClickHouse {@code Float32} stores
     * {@code nan}/{@code inf} (measured with {@code clickhouse local} 24.8.14).
     */
    @Test
    @DisplayName("FM-07.02-1: FLOAT32 NaN and infinities are bound as floats")
    public void float32NaNAndInfinityAreBound() throws Exception {
        for (float f : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            List<Bind> binds = bind(Schema.Type.FLOAT32, null, f, ClickHouseDataType.Float32);
            assertEquals(1, binds.size());
            assertEquals("setFloat", binds.get(0).method);
            assertEquals(Float.valueOf(f), binds.get(0).value);
        }
    }

    /**
     * FM-07.02-1: a double-precision NaN or infinity (reachable from a
     * PostgreSQL {@code float8}; MySQL rejects them) goes through
     * {@code ClickHouseDoubleValue.of(v).asBigDecimal()}, which throws
     * {@code NumberFormatException("Infinite or NaN")} (clickhouse-data 0.9.8
     * bytecode). The batch fails on every attempt although ClickHouse
     * {@code Float64} stores {@code nan}/{@code inf}.
     */
    @Test
    @Disabled("DEFECT FM-07.02-1: FLOAT64 NaN/Infinity throws NumberFormatException in asBigDecimal() and the "
            + "batch is retried forever; ClickHouse Float64 can store the value")
    @DisplayName("FM-07.02-1: FLOAT64 NaN and infinities are bound, not refused")
    public void float64NaNAndInfinityAreBound() {
        for (double d : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            List<Bind> binds = assertDoesNotThrow(
                    () -> bind(Schema.Type.FLOAT64, null, d, ClickHouseDataType.Float64));
            assertEquals(1, binds.size());
            assertEquals(String.valueOf(d), String.valueOf(binds.get(0).value),
                    "the bound value must render as the Java double literal ClickHouse parses");
        }
    }

    /**
     * Spec 07.02 section 3.2 (the gap its section 5 names): a {@code DECIMAL}
     * is bound as the very {@code BigDecimal} Debezium delivered, never through
     * a {@code double}.
     */
    @Test
    @DisplayName("Spec 07.02 section 3.2: DECIMAL binds the BigDecimal itself, no float conversion")
    public void decimalIsBoundAsBigDecimalWithoutFloatConversion() throws Exception {
        BigDecimal value = new BigDecimal("12345678901234567890123456789012345.123456789012345678901234567890");
        List<Bind> binds = bind(Schema.Type.BYTES, Decimal.LOGICAL_NAME, value, ClickHouseDataType.Decimal);
        assertEquals(1, binds.size());
        assertEquals("setBigDecimal", binds.get(0).method);
        assertSame(value, binds.get(0).value);
    }

    // ------------------------------------------------------------------
    // Spec 07.01: integers
    // ------------------------------------------------------------------

    /**
     * FM-07.01-2: an integer outside the range of the ClickHouse column type
     * (a hand-created or drifted narrower column) is bound unchanged, and
     * ClickHouse 24.8 wraps it silently in the VALUES path: 4000000000 into
     * {@code Int32} stores -294967296, 300 into {@code UInt8} stores 44
     * (measured with {@code clickhouse local}). The correct behaviour is a
     * refusal naming the column, like the temporal range policy of spec 07.03.
     */
    @Test
    @Disabled("DEFECT FM-07.01-2: an integer wider than its ClickHouse column is bound and wrapped silently")
    @DisplayName("FM-07.01-2: an integer outside the target column range is refused, not wrapped")
    public void integerOutsideTheTargetColumnRangeIsRefused() {
        assertThrows(RuntimeException.class,
                () -> bind(Schema.Type.INT64, null, 4_000_000_000L, ClickHouseDataType.Int32));
        assertThrows(RuntimeException.class,
                () -> bind(Schema.Type.INT32, null, 300, ClickHouseDataType.UInt8));
    }

    /**
     * FM-07.01-3: MySQL {@code BOOL} is {@code TINYINT(1)} and holds
     * -128..127; the DDL path declares it {@code Bool}, and ClickHouse stores
     * any non-zero integer bound into {@code Bool} as {@code true}
     * ({@code toUInt8} reads 1; measured with {@code clickhouse local}). A
     * value other than 0/1 must be refused rather than collapsed.
     */
    @Test
    @Disabled("DEFECT FM-07.01-3: a TINYINT(1) value other than 0/1 bound into Bool is collapsed to true silently")
    @DisplayName("FM-07.01-3: a TINYINT other than 0/1 is refused for a Bool column")
    public void boolColumnRefusesATinyintOtherThanZeroOrOne() {
        assertThrows(RuntimeException.class,
                () -> bind(Schema.Type.INT16, null, (short) 2, ClickHouseDataType.Bool));
    }

    // ------------------------------------------------------------------
    // Spec 07.04: strings, ENUM, SET, JSON
    // ------------------------------------------------------------------

    /**
     * Spec 07.04 section 3.1 and FM-07.04-1: text, ENUM labels, SET lists and
     * JSON documents are bound verbatim -- multi-byte UTF-8, an emoji, quotes,
     * a backslash and a U+FFFD that the source-side decoder may already have
     * substituted all reach the driver unchanged; the connector itself never
     * re-encodes or repairs a string.
     */
    @Test
    @DisplayName("Spec 07.04: text, ENUM, SET and JSON are bound verbatim")
    public void textEnumSetAndJsonAreBoundVerbatim() throws Exception {
        String text = "héllo 😀 � 'q' \\ end";
        List<Bind> t = bind(Schema.Type.STRING, null, text, ClickHouseDataType.String);
        assertEquals("setString", t.get(0).method);
        assertSame(text, t.get(0).value);

        List<Bind> e = bind(Schema.Type.STRING, Enum.LOGICAL_NAME, "b", ClickHouseDataType.String);
        assertEquals("setString", e.get(0).method);
        assertEquals("b", e.get(0).value);

        List<Bind> s = bind(Schema.Type.STRING, EnumSet.LOGICAL_NAME, "a,b", ClickHouseDataType.String);
        assertEquals("setString", s.get(0).method);
        assertEquals("a,b", s.get(0).value);

        String json = "{\"a\":1,\"b\":[\"x\",null]}";
        List<Bind> j = bind(Schema.Type.STRING, Json.LOGICAL_NAME, json, ClickHouseDataType.String);
        assertEquals("setObject", j.get(0).method);
        assertSame(json, j.get(0).value);
    }

    // ------------------------------------------------------------------
    // Spec 07.05: binary
    // ------------------------------------------------------------------

    /**
     * FM-07.05-2: a {@code ByteBuffer} carrier is read with {@code array()},
     * which returns the whole backing array regardless of the buffer's
     * position, limit and array offset. A slice therefore binds bytes that
     * are not the value (and a read-only or direct buffer throws). The
     * Geometry branch of the same method already reads {@code remaining()}.
     */
    @Test
    @Disabled("DEFECT FM-07.05-2: a positioned/sliced ByteBuffer binds its whole backing array")
    @DisplayName("FM-07.05-2: a ByteBuffer binds exactly its remaining bytes")
    public void byteBufferSliceBindsOnlyItsRemainingBytes() throws Exception {
        ByteBuffer slice = ByteBuffer.wrap(new byte[]{0x01, 0x02, 0x03, 0x04}, 1, 2).slice();
        List<Bind> binds = bind(Schema.Type.BYTES, null, slice, ClickHouseDataType.String);
        assertEquals(1, binds.size());
        assertEquals("0203", binds.get(0).value);

        ByteBuffer readOnly = ByteBuffer.wrap(new byte[]{(byte) 0xde, (byte) 0xad}).asReadOnlyBuffer();
        List<Bind> ro = bind(Schema.Type.BYTES, null, readOnly, ClickHouseDataType.String);
        assertTrue(!ro.isEmpty() && "dead".equals(ro.get(0).value), "a read-only buffer binds its bytes");
    }
}

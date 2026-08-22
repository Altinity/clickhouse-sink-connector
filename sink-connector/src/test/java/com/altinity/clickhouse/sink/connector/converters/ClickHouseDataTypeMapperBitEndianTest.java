package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.Bits;
import org.apache.kafka.connect.data.Schema;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.time.ZoneId;

/**
 * Debezium delivers MySQL {@code BIT(n)} ({@code io.debezium.data.Bits}) as a
 * LITTLE-ENDIAN byte[] -- least significant byte first -- while MySQL presents
 * the value big-endian ({@code HEX(b)} prints the most significant byte first).
 *
 * <p>Encoding the array as received stored every multi-byte BIT byte-reversed.
 * Measured on a live stack (MySQL 8.0.36 ROW/FULL/GTID -> ClickHouse
 * 24.8.14.10547):</p>
 *
 * <pre>
 *   MySQL BIT(64) 0102030405060708 -> ClickHouse 0807060504030201
 *   MySQL BIT(24) 010203           -> ClickHouse 030201
 *   MySQL BIT(16) 0102             -> ClickHouse 0201
 *   MySQL BIT(16) FF00             -> ClickHouse 00ff
 * </pre>
 *
 * <p>The corruption is silent and row counts still match. It escaped the
 * earlier byte-boundary testing because every value used there was a
 * single byte or a palindrome (0x00.. / 0xFF.. repeated), which is
 * unchanged by reversal.</p>
 *
 * <p>BLOB/BINARY/VARBINARY arrive as a ByteBuffer already in source order and
 * were verified to round-trip exactly, so the reversal must apply to BIT
 * alone.</p>
 */
public class ClickHouseDataTypeMapperBitEndianTest {

    /**
     * Recording PreparedStatement built with java.lang.reflect.Proxy -- the
     * JDBC test-double idiom already used by this module (see
     * ClosedConnectionRefreshTest). Deliberately no mocking framework: there is
     * no Mockito on this module's classpath.
     */
    private static final class Recorder implements InvocationHandler {
        private String stringValue;
        private byte[] bytesValue;
        private int bindCalls;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setString":
                    stringValue = (String) args[1];
                    bindCalls++;
                    return null;
                case "setBytes":
                    bytesValue = (byte[]) args[1];
                    bindCalls++;
                    return null;
                case "toString":
                    return "RecordingPreparedStatement";
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                default:
                    throw new UnsupportedOperationException(
                            "unexpected PreparedStatement call: " + method.getName());
            }
        }
    }

    private static PreparedStatement proxyFor(Recorder rec) {
        return (PreparedStatement) Proxy.newProxyInstance(
                ClickHouseDataTypeMapperBitEndianTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, rec);
    }

    private static String convertBits(byte[] littleEndianFromDebezium) throws Exception {
        Recorder rec = new Recorder();
        boolean handled = ClickHouseDataTypeMapper.convert(
                Schema.Type.BYTES, Bits.LOGICAL_NAME, littleEndianFromDebezium, 1,
                proxyFor(rec), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                ClickHouseDataType.String, ZoneId.of("UTC"));
        Assert.assertTrue("BYTES/Bits must be handled", handled);
        Assert.assertEquals("exactly one bind call", 1, rec.bindCalls);
        Assert.assertNull("must not use setBytes without persist.raw.bytes", rec.bytesValue);
        return rec.stringValue;
    }

    /** BIT(64) 0x0102030405060708 -- the value that exposed the defect. */
    @Test
    public void testBit64AsymmetricValueIsBigEndian() throws Exception {
        byte[] littleEndian = {0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01};
        Assert.assertEquals("0102030405060708", convertBits(littleEndian));
    }

    /** BIT(24) 0x010203. */
    @Test
    public void testBit24AsymmetricValueIsBigEndian() throws Exception {
        Assert.assertEquals("010203", convertBits(new byte[]{0x03, 0x02, 0x01}));
    }

    /** BIT(16) 0xFF00 -- high byte set, so it also guards the #1387 fix. */
    @Test
    public void testBit16HighByteIsBigEndian() throws Exception {
        Assert.assertEquals("ff00", convertBits(new byte[]{0x00, (byte) 0xFF}));
    }

    /**
     * Regression guard for #1387: a single high byte must stay lossless. A
     * one-byte value has no byte order, so reversal must not disturb it.
     */
    @Test
    public void testSingleHighByteUnchanged() throws Exception {
        Assert.assertEquals("aa", convertBits(new byte[]{(byte) 0xAA}));
        Assert.assertEquals("ff", convertBits(new byte[]{(byte) 0xFF}));
        Assert.assertEquals("80", convertBits(new byte[]{(byte) 0x80}));
        Assert.assertEquals("01", convertBits(new byte[]{0x01}));
    }

    /**
     * Palindromic values are unchanged by reversal -- exactly why the earlier
     * boundary testing missed this defect. Kept so the fix cannot regress them.
     */
    @Test
    public void testPalindromicValuesUnchanged() throws Exception {
        Assert.assertEquals("ffffffffffffffff",
                convertBits(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
        Assert.assertEquals("0000000000000000",
                convertBits(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}));
    }

    /**
     * BLOB/BINARY (BYTES with no logical name) arrive as a ByteBuffer already
     * in source order and were verified on a live stack to round-trip exactly.
     * They must NOT be reversed.
     */
    @Test
    public void testBlobByteBufferNotReversed() throws Exception {
        Recorder rec = new Recorder();
        byte[] payload = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};
        boolean handled = ClickHouseDataTypeMapper.convert(
                Schema.Type.BYTES, null, ByteBuffer.wrap(payload), 1,
                proxyFor(rec), new ClickHouseSinkConnectorConfig(new HashMap<>()),
                ClickHouseDataType.String, ZoneId.of("UTC"));
        Assert.assertTrue("BYTES/blob must be handled", handled);
        Assert.assertEquals("BLOB bytes must keep source order",
                "0102030405060708", rec.stringValue);
    }
}

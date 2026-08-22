package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.Bits;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Regression tests for MySQL {@code BIT(n)} values reaching the
 * {@code Schema.Type.BYTES} branch of {@link ClickHouseDataTypeMapper#convert}.
 *
 * <p>A {@code BIT(n)} value arrives as a raw {@code byte[]}. That branch used to
 * stringify it with {@code new String(bytes)}, which decodes using the platform
 * default charset: every byte &gt;= 0x80 is an invalid single-byte UTF-8 sequence
 * and is replaced with U+FFFD (EF BF BD). 0x80, 0xAA and 0xFF all collapsed to
 * the same replacement character -- irreversible, silent corruption, with row
 * counts still matching so checksum jobs reported the table clean.
 *
 * <p>The sibling ByteBuffer branch already hex-encoded (honouring
 * {@code persist.raw.bytes}), which is why BLOB/BINARY were unaffected. These
 * tests pin both carriers to identical, lossless behaviour.
 *
 * <p>Verified failing before the fix and passing after it, and reproduced
 * end-to-end on MySQL 8.0.36 -&gt; sink connector -&gt; ClickHouse 24.8.14:
 * {@code BIT(8)} values 0x80/0xAA/0xFF all landed as hex EFBFBD with
 * {@code length()=3} instead of 1.
 *
 * <p>The recording {@link PreparedStatement} below is a {@link Proxy}, matching
 * the JDBC test-double idiom already used by {@code ClosedConnectionRefreshTest}
 * and {@code SystemConnectionRefreshTest}. This module has no mocking framework
 * on its test classpath, and one is not needed to observe a single setter.
 */
public class ClickHouseDataTypeMapperBitBytesTest {

    /**
     * Records the single {@code setXxx} call {@code convert()} makes for a
     * BYTES value, so the test can assert on the exact value bound to the
     * statement.
     */
    private static final class RecordingStatement {
        private String stringValue;
        private byte[] bytesValue;
        private int callCount;

        private final PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                handler());

        private InvocationHandler handler() {
            return (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setString":
                        callCount++;
                        stringValue = (String) args[1];
                        return null;
                    case "setBytes":
                        callCount++;
                        bytesValue = (byte[]) args[1];
                        return null;
                    case "toString":
                        return "RecordingStatement";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        // Nothing else is expected on the BYTES path. Failing
                        // loudly keeps an unnoticed behaviour change from
                        // silently satisfying these assertions.
                        throw new AssertionError(
                                "unexpected PreparedStatement call: " + method.getName());
                }
            };
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    /**
     * Drives the BYTES branch exactly as the writer does and returns the value
     * bound to the statement. {@code persist.raw.bytes} defaults to false, so
     * the hex-encoding path is the one exercised here.
     */
    private static String convertBits(Object value) throws SQLException {
        RecordingStatement recorder = new RecordingStatement();
        ClickHouseDataTypeMapper.convert(
                Schema.Type.BYTES, Bits.LOGICAL_NAME, value, 1, recorder.statement, config(),
                ClickHouseDataType.String, ZoneId.of("UTC"));
        Assertions.assertEquals(1, recorder.callCount,
                "expected exactly one binding call for a BYTES value");
        Assertions.assertNull(recorder.bytesValue,
                "persist.raw.bytes defaults to false, so setBytes must not be used");
        return recorder.stringValue;
    }

    /** Every high-bit byte must survive; before the fix all three became EF BF BD. */
    @Test
    public void bitHighBytesAreNotReplacedWithUtf8ReplacementChar() throws SQLException {
        Assertions.assertEquals("80", convertBits(new byte[] {(byte) 0x80}));
        Assertions.assertEquals("aa", convertBits(new byte[] {(byte) 0xAA}));
        Assertions.assertEquals("ff", convertBits(new byte[] {(byte) 0xFF}));
    }

    /** BIT(64) of all ones must not collapse to eight replacement characters. */
    @Test
    public void wideBitValueRoundTripsEveryByte() throws SQLException {
        byte[] allOnes = new byte[8];
        Arrays.fill(allOnes, (byte) 0xFF);

        Assertions.assertEquals("ffffffffffffffff", convertBits(allOnes));
    }

    /** Low bytes were already correct and must stay byte-identical. */
    @Test
    public void bitLowBytesAreUnchanged() throws SQLException {
        Assertions.assertEquals("017f", convertBits(new byte[] {0x01, 0x7F}));
    }

    /**
     * The two carriers of a BYTES value must agree: a byte[] and a ByteBuffer
     * holding the same bytes previously produced different results.
     */
    @Test
    public void byteArrayAndByteBufferAgree() throws SQLException {
        byte[] payload = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

        Assertions.assertEquals("deadbeef", convertBits(payload));
        Assertions.assertEquals("deadbeef", convertBits(ByteBuffer.wrap(payload)));
    }

    /** A zero-length BIT payload must not throw. */
    @Test
    public void emptyPayloadIsHandled() {
        Assertions.assertDoesNotThrow(() -> Assertions.assertEquals("", convertBits(new byte[0])));
    }
}

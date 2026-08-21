package com.altinity.clickhouse.sink.connector.converters;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.clickhouse.data.ClickHouseDataType;
import io.debezium.data.Bits;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
 */
public class ClickHouseDataTypeMapperBitBytesTest {

    private static ClickHouseSinkConnectorConfig config() {
        return new ClickHouseSinkConnectorConfig(new HashMap<>());
    }

    private static void convertBits(Object value, PreparedStatement ps) throws SQLException {
        ClickHouseDataTypeMapper.convert(
                Schema.Type.BYTES, Bits.LOGICAL_NAME, value, 1, ps, config(),
                ClickHouseDataType.String, ZoneId.of("UTC"));
    }

    /** Every high-bit byte must survive; before the fix all three became EF BF BD. */
    @Test
    public void bitHighBytesAreNotReplacedWithUtf8ReplacementChar() throws SQLException {
        byte[][] payloads = {
            new byte[] {(byte) 0x80},
            new byte[] {(byte) 0xAA},
            new byte[] {(byte) 0xFF},
        };
        String[] expected = {"80", "aa", "ff"};

        for (int i = 0; i < payloads.length; i++) {
            PreparedStatement ps = Mockito.mock(PreparedStatement.class);
            convertBits(payloads[i], ps);
            Mockito.verify(ps).setString(1, expected[i]);
        }
    }

    /** BIT(64) of all ones must not collapse to eight replacement characters. */
    @Test
    public void wideBitValueRoundTripsEveryByte() throws SQLException {
        byte[] allOnes = new byte[8];
        Arrays.fill(allOnes, (byte) 0xFF);

        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        convertBits(allOnes, ps);

        Mockito.verify(ps).setString(1, "ffffffffffffffff");
    }

    /** Low bytes were already correct and must stay byte-identical. */
    @Test
    public void bitLowBytesAreUnchanged() throws SQLException {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        convertBits(new byte[] {0x01, 0x7F}, ps);

        Mockito.verify(ps).setString(1, "017f");
    }

    /**
     * The two carriers of a BYTES value must agree: a byte[] and a ByteBuffer
     * holding the same bytes previously produced different results.
     */
    @Test
    public void byteArrayAndByteBufferAgree() throws SQLException {
        byte[] payload = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};

        PreparedStatement fromArray = Mockito.mock(PreparedStatement.class);
        convertBits(payload, fromArray);

        PreparedStatement fromBuffer = Mockito.mock(PreparedStatement.class);
        convertBits(ByteBuffer.wrap(payload), fromBuffer);

        Mockito.verify(fromArray).setString(1, "deadbeef");
        Mockito.verify(fromBuffer).setString(1, "deadbeef");
    }

    /** A zero-length BIT payload must not throw. */
    @Test
    public void emptyPayloadIsHandled() {
        PreparedStatement ps = Mockito.mock(PreparedStatement.class);
        Assertions.assertDoesNotThrow(() -> convertBits(new byte[0], ps));
    }
}

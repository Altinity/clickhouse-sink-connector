package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Spec 03.07 section 7, FM-03.07-2: the routing token of a table whose
 * primary key is binary ({@code BINARY(16)} UUIDs are the common case).
 *
 * <p>{@code ClickHouseStruct} keeps the Debezium key as {@code key.toString()}.
 * Kafka Connect's {@code Struct.toString()} appends each value with
 * {@code StringBuilder.append(Object)} (connect-api 3.8.0), and Debezium
 * delivers a BYTES column as a {@code ByteBuffer} ({@code
 * JdbcValueConverters.toByteBuffer}, debezium-core 3.1.3), whose
 * {@code toString()} is {@code java.nio.HeapByteBuffer[pos=0 lim=16 cap=16]}
 * -- the length, not the content. The token is deterministic, so per-row
 * order is safe; but every row of equal key length gets the SAME token, and a
 * hot table keyed by a binary UUID is written by ONE worker, exactly the
 * saturation key-aware routing exists to remove.</p>
 */
public class RoutedBatchBinaryKeyTest {

    private static final Schema KEY = SchemaBuilder.struct().field("id", Schema.BYTES_SCHEMA).build();
    private static final Schema ROW = SchemaBuilder.struct().field("id", Schema.BYTES_SCHEMA).build();

    private static ClickHouseStruct keyed(byte[] id) {
        Struct key = new Struct(KEY).put("id", ByteBuffer.wrap(id.clone()));
        Struct after = new Struct(ROW).put("id", ByteBuffer.wrap(id.clone()));
        return new ClickHouseStruct(0L, "srv.db.hot", key, 0, 0L, null, after, null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
    }

    private static byte[] uuid(int seed) {
        byte[] b = new byte[16];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (seed * 31 + i);
        }
        return b;
    }

    @Test
    @DisplayName("The same binary key always produces the same shard token (per-row order is safe)")
    public void theSameBinaryKeyAlwaysRoutesToTheSameShard() {
        assertEquals(RoutedBatch.createShardKey(keyed(uuid(7)), true),
                RoutedBatch.createShardKey(keyed(uuid(7)), true),
                "two events of one row, carried in two distinct ByteBuffer instances, must share a token");
    }

    @Test
    @Disabled("DEFECT FM-03.07-2: the shard token of a binary key is ByteBuffer.toString() (its length), so every "
            + "row of a BINARY(16)-keyed table routes to one worker and key-aware routing does not spread it")
    @DisplayName("Distinct binary keys of one table produce distinct shard tokens")
    public void distinctBinaryKeysOfOneTableProduceDistinctTokens() {
        assertNotEquals(RoutedBatch.createShardKey(keyed(uuid(1)), true),
                RoutedBatch.createShardKey(keyed(uuid(2)), true),
                "the token must be a function of the key's content, or a binary-keyed hot table is never spread");
    }
}

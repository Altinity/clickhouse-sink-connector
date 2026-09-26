package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * INSERT chunks are bounded in rows AND in estimated bytes (spec 03.06 §3.1).
 *
 * <p>The driver renders a whole chunk as SQL text in memory before it is sent;
 * {@code buffer.max.records} alone lets a chunk of wide rows grow to gigabytes
 * of text per worker. Chunks therefore close on whichever limit is met first,
 * never split a row, always hold at least one row, and preserve order.</p>
 */
public class BatchChunkerTest {

    private static List<ClickHouseStruct> rows(long... bytes) {
        List<ClickHouseStruct> list = new ArrayList<>();
        for (long b : bytes) {
            ClickHouseStruct r = new ClickHouseStruct();
            r.setEstimatedBytes(b);
            list.add(r);
        }
        return list;
    }

    private static long total(List<List<ClickHouseStruct>> chunks) {
        return chunks.stream().mapToLong(List::size).sum();
    }

    @Test
    @DisplayName("the row limit alone splits like a partition")
    public void rowLimitAlone() {
        List<ClickHouseStruct> in = rows(1, 1, 1, 1, 1);
        List<List<ClickHouseStruct>> out = BatchChunker.chunk(in, 2, 0);
        assertEquals(3, out.size());
        assertEquals(2, out.get(0).size());
        assertEquals(2, out.get(1).size());
        assertEquals(1, out.get(2).size());
        assertEquals(5, total(out));
        assertSame(in.get(0), out.get(0).get(0), "order and identity preserved");
        assertSame(in.get(4), out.get(2).get(0));
    }

    @Test
    @DisplayName("the byte limit closes a chunk before the row limit when the rows are wide")
    public void byteLimitClosesEarly() {
        // 100-byte rows, byte limit 250: three rows fit (300 > 250 -> no: two fit, 200; third would make 300).
        List<List<ClickHouseStruct>> out = BatchChunker.chunk(rows(100, 100, 100, 100, 100), 1_000, 250);
        assertEquals(3, out.size(), "2 + 2 + 1");
        assertEquals(2, out.get(0).size());
        assertEquals(2, out.get(1).size());
        assertEquals(1, out.get(2).size());
    }

    @Test
    @DisplayName("a single row wider than the byte limit is written on its own, never dropped or split")
    public void oversizedRowIsItsOwnChunk() {
        List<List<ClickHouseStruct>> out = BatchChunker.chunk(rows(10, 5_000, 10), 1_000, 100);
        assertEquals(3, out.size(), "narrow | oversized | narrow");
        assertEquals(1, out.get(1).size());
        assertEquals(5_000L, out.get(1).get(0).getEstimatedBytes());
        assertEquals(3, total(out));
    }

    @Test
    @DisplayName("rows never stamped (0 bytes) fall back to the row limit; both limits disabled means one chunk")
    public void unstampedRowsAndDisabledLimits() {
        List<List<ClickHouseStruct>> byRows = BatchChunker.chunk(rows(0, 0, 0), 2, 100);
        assertEquals(2, byRows.size(), "zero-byte rows still obey the row limit");

        List<List<ClickHouseStruct>> one = BatchChunker.chunk(rows(1 << 30, 1 << 30, 1 << 30), 0, 0);
        assertEquals(1, one.size(), "no limits: one chunk");
        assertEquals(3, one.get(0).size());
    }

    @Test
    @DisplayName("empty input yields no chunks; a null row counts as zero bytes")
    public void emptyAndNull() {
        assertTrue(BatchChunker.chunk(Collections.emptyList(), 10, 10).isEmpty());
        assertTrue(BatchChunker.chunk(null, 10, 10).isEmpty());
        List<ClickHouseStruct> withNull = new ArrayList<>(rows(50));
        withNull.add(null);
        List<List<ClickHouseStruct>> out = BatchChunker.chunk(withNull, 10, 60);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).size());
    }
}

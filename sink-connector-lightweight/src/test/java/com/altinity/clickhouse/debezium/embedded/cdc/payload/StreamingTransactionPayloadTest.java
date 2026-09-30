package com.altinity.clickhouse.debezium.embedded.cdc.payload;

import com.altinity.clickhouse.debezium.embedded.cdc.BinlogTransactionCompressionPreflightAccess;
import com.github.luben.zstd.Zstd;
import com.github.shyiko.mysql.binlog.event.DeleteRowsEventData;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.event.UpdateRowsEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;
import io.debezium.config.CommonConnectorConfig.EventProcessingFailureHandlingMode;
import io.debezium.connector.binlog.event.RowDeserializers;
import io.debezium.connector.binlog.event.TransactionPayloadDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The connector's TRANSACTION_PAYLOAD decoder streams a compressed transaction: any
 * uncompressed size, constant heap, the same events as Debezium's materializing decoder
 * (spec 01.08 §3.2, §5).
 */
class StreamingTransactionPayloadTest {

    private static final EventProcessingFailureHandlingMode FAIL = EventProcessingFailureHandlingMode.FAIL;

    private static TransactionPayloadEventData decode(byte[] body, Map<Long, TableMapEventData> tableMaps)
            throws IOException {
        return new TransactionPayloadDeserializer(tableMaps, FAIL).deserialize(new ByteArrayInputStream(body));
    }

    private static List<Event> dispatch(TransactionPayloadEventData data) {
        List<Event> out = new ArrayList<>();
        for (Event e : data.getUncompressedEvents()) {
            e.getData(); // what a Debezium handler does
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Reference: Debezium 3.1.3's materializing decoder, reproduced line for line
    // (the stock class is shadowed by the connector's, so it is re-implemented here).
    // ------------------------------------------------------------------

    private static List<Event> referenceDecode(byte[] body, Map<Long, TableMapEventData> tableMaps) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        int payloadSize = 0;
        int uncompressedSize = 0;
        while (in.available() > 0) {
            int fieldType = in.readPackedInteger();
            if (fieldType == 0) {
                break;
            }
            int fieldLen = in.readPackedInteger();
            switch (fieldType) {
                case 1: payloadSize = in.readPackedInteger(); break;
                case 2: in.readPackedInteger(); break;
                case 3: uncompressedSize = in.readPackedInteger(); break;
                default: in.read(fieldLen); break;
            }
        }
        if (uncompressedSize == 0) {
            uncompressedSize = payloadSize;
        }
        byte[] src = in.read(payloadSize);
        byte[] dst = new byte[uncompressedSize];
        Zstd.decompressByteArray(dst, 0, dst.length, src, 0, src.length);
        EventDeserializer d = new EventDeserializer();
        d.setEventDataDeserializer(EventType.WRITE_ROWS, new RowDeserializers.WriteRowsDeserializer(tableMaps, FAIL));
        d.setEventDataDeserializer(EventType.UPDATE_ROWS, new RowDeserializers.UpdateRowsDeserializer(tableMaps, FAIL));
        d.setEventDataDeserializer(EventType.DELETE_ROWS, new RowDeserializers.DeleteRowsDeserializer(tableMaps, FAIL));
        d.setEventDataDeserializer(EventType.EXT_WRITE_ROWS,
                new RowDeserializers.WriteRowsDeserializer(tableMaps, FAIL).setMayContainExtraInformation(true));
        d.setEventDataDeserializer(EventType.EXT_UPDATE_ROWS,
                new RowDeserializers.UpdateRowsDeserializer(tableMaps, FAIL).setMayContainExtraInformation(true));
        d.setEventDataDeserializer(EventType.EXT_DELETE_ROWS,
                new RowDeserializers.DeleteRowsDeserializer(tableMaps, FAIL).setMayContainExtraInformation(true));
        ByteArrayInputStream dstIn = new ByteArrayInputStream(dst);
        List<Event> events = new ArrayList<>();
        Event e = d.nextEvent(dstIn);
        while (e != null) {
            events.add(e);
            if (e.getHeader().getEventType() == EventType.TABLE_MAP && e.getData() != null) {
                TableMapEventData tm = e.getData();
                tableMaps.put(tm.getTableId(), tm);
            }
            e = d.nextEvent(dstIn);
        }
        return events;
    }

    /** Structural equality of two events: header fields and data, arrays compared by content. */
    private static void assertSameEvent(Event expected, Event actual, int index) {
        EventHeaderV4 eh = expected.getHeader();
        EventHeaderV4 ah = actual.getHeader();
        String where = "inner event #" + index + " (" + eh.getEventType() + ")";
        assertEquals(eh.getEventType(), ah.getEventType(), where);
        assertEquals(eh.getTimestamp(), ah.getTimestamp(), where);
        assertEquals(eh.getServerId(), ah.getServerId(), where);
        assertEquals(eh.getEventLength(), ah.getEventLength(), where);
        assertEquals(eh.getNextPosition(), ah.getNextPosition(), where);
        assertEquals(eh.getFlags(), ah.getFlags(), where);
        assertEquals(canonical(expected.getData()), canonical(actual.getData()), where);
    }

    private static String canonical(EventData data) {
        if (data instanceof WriteRowsEventData) {
            WriteRowsEventData w = (WriteRowsEventData) data;
            return "W" + w.getTableId() + w.getIncludedColumns() + rows(w.getRows());
        }
        if (data instanceof DeleteRowsEventData) {
            DeleteRowsEventData d = (DeleteRowsEventData) data;
            return "D" + d.getTableId() + d.getIncludedColumns() + rows(d.getRows());
        }
        if (data instanceof UpdateRowsEventData) {
            UpdateRowsEventData u = (UpdateRowsEventData) data;
            return "U" + u.getTableId() + u.getIncludedColumnsBeforeUpdate() + u.getIncludedColumns()
                    + u.getRows().stream()
                    .map(r -> Arrays.deepToString(r.getKey()) + "->" + Arrays.deepToString(r.getValue()))
                    .collect(Collectors.joining(","));
        }
        return String.valueOf(data);
    }

    private static String rows(List<Serializable[]> rows) {
        return rows.stream().map(Arrays::deepToString).collect(Collectors.joining(";"));
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A real MySQL 8.0 compressed transaction decodes to exactly the events Debezium's decoder produced")
    void realMySqlPayloadMatchesTheMaterializingDecoder() throws IOException {
        byte[] body = PayloadFixtures.mysql80Fixture();
        List<Event> expected = referenceDecode(body, new HashMap<>());
        TransactionPayloadEventData data = decode(body, new HashMap<>());
        List<Event> actual = dispatch(data);

        assertEquals(12, expected.size(), "fixture: BEGIN, 5 x (TABLE_MAP + rows), XID");
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSameEvent(expected.get(i), actual.get(i), i);
        }
        assertEquals(EventType.QUERY, actual.get(0).getHeader().getEventType());
        assertEquals(EventType.XID, actual.get(11).getHeader().getEventType());
        StreamingTransactionPayloadEventData streamed = (StreamingTransactionPayloadEventData) data;
        assertEquals(786, streamed.getUncompressedSizeLong(), "MySQL's decompressed_size for the fixture");
        assertEquals(304, streamed.getPayloadSizeLong());
    }

    @Test
    @DisplayName("Registration passes read no row data; the dispatch pass parses each body once; passes repeat identically")
    void passesAreLazyAndRepeatable() throws IOException {
        byte[] body = PayloadFixtures.mysql80Fixture();
        Map<Long, TableMapEventData> tableMaps = new HashMap<>();
        TransactionPayloadEventData data = decode(body, tableMaps);

        List<Event> registration = new ArrayList<>();
        data.getUncompressedEvents().forEach(registration::add);
        assertEquals(12, registration.size());
        for (Event e : registration) {
            if (e.getHeader().getEventType() == EventType.TABLE_MAP) {
                assertInstanceOf(TableMapEventData.class, e.getData(), "TABLE_MAP is parsed eagerly");
            } else {
                assertInstanceOf(LazyPayloadEvent.class, e);
                assertFalse(((LazyPayloadEvent) e).isParsed(), "a registration pass must not parse " + e);
            }
        }
        assertEquals(2, tableMaps.size(), "both tables registered in the shared map, in stream order");

        List<Event> first = dispatch(data);
        List<Event> second = dispatch(data);
        for (int i = 0; i < first.size(); i++) {
            assertSameEvent(first.get(i), second.get(i), i);
        }
        assertEquals(data.getUncompressedEvents().stream().count(), 12);
    }

    @Test
    @DisplayName("A payload above 4 GiB uncompressed decodes in a 256 MiB heap (no int limit, no whole-transaction buffer)")
    void payloadAboveFourGibDecodesInASmallHeap() throws Exception {
        // 4,200 rows x 1 MiB blobs = 4.4 GB uncompressed: past Integer.MAX_VALUE and past 2^32.
        long rows = 4_200;
        int blobSize = 1 << 20;
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx256m");
        cmd.add("-cp");
        cmd.add(testClasspath());
        cmd.add(LargePayloadDecodeMain.class.getName());
        cmd.add(Long.toString(rows));
        cmd.add(Integer.toString(blobSize));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(p.waitFor(10, TimeUnit.MINUTES), "child JVM did not finish");
        String result = Arrays.stream(output.split("\n")).filter(l -> l.startsWith("OK") || l.startsWith("FAIL"))
                .findFirst().orElse("(no result line) " + tail(output));
        assertEquals(0, p.exitValue(), result);
        assertTrue(result.startsWith("OK"), result);
        long uncompressed = Long.parseLong(result.replaceAll(".*uncompressedBytes=(\\d+).*", "$1"));
        assertTrue(uncompressed > (1L << 32), "the payload must exceed 2^32 bytes, was " + uncompressed);
        long maxHeap = Long.parseLong(result.replaceAll(".*maxHeapBytes=(\\d+).*", "$1"));
        assertTrue(maxHeap < uncompressed / 16, "heap " + maxHeap + " must be far below the payload " + uncompressed);
    }

    @Test
    @DisplayName("An 8-byte uncompressed-size field is read as 64 bits (the stock decoder threw 'Stumbled upon long')")
    void eightByteUncompressedSizeIsAccepted() throws IOException {
        byte[] inner = concat(PayloadFixtures.tableMap(9), PayloadFixtures.writeRow(9, 1, new byte[] {1, 2}),
                PayloadFixtures.xid(5));
        byte[] compressed = Zstd.compress(inner);
        // Declare a size above 2^32: the header parses; the end-of-pass check then refuses the mismatch.
        byte[] body = PayloadFixtures.body(compressed, 0, (1L << 32) + 7);
        TransactionPayloadEventData data = decode(body, new HashMap<>());
        assertEquals((1L << 32) + 7, ((StreamingTransactionPayloadEventData) data).getUncompressedSizeLong());
        assertEquals(Integer.MAX_VALUE, data.getUncompressedSize(), "int view saturates");
        PayloadEventDecodingException e = assertThrows(PayloadEventDecodingException.class, () -> dispatch(data));
        assertTrue(e.getMessage().contains("declares " + ((1L << 32) + 7)), e.getMessage());
    }

    @Test
    @DisplayName("A decompressed size that disagrees with the header fails loudly at the end of the pass")
    void sizeMismatchFailsLoudly() throws IOException {
        byte[] inner = concat(PayloadFixtures.tableMap(9), PayloadFixtures.writeRow(9, 1, new byte[] {1}),
                PayloadFixtures.xid(5));
        byte[] body = PayloadFixtures.body(Zstd.compress(inner), 0, (long) inner.length + 1);
        TransactionPayloadEventData data = decode(body, new HashMap<>());
        assertThrows(PayloadEventDecodingException.class, () -> dispatch(data));
    }

    @Test
    @DisplayName("A truncated zstd frame fails loudly instead of ending the transaction early")
    void truncatedFrameFailsLoudly() throws IOException {
        byte[] inner = concat(PayloadFixtures.tableMap(9), PayloadFixtures.writeRow(9, 1, PayloadFixtures.blob(1, 4096)),
                PayloadFixtures.xid(5));
        byte[] compressed = Zstd.compress(inner);
        byte[] cut = Arrays.copyOf(compressed, compressed.length / 2);
        byte[] body = PayloadFixtures.body(cut, 0, (long) inner.length);
        TransactionPayloadEventData data = decode(body, new HashMap<>());
        assertThrows(PayloadEventDecodingException.class, () -> dispatch(data));
    }

    @Test
    @DisplayName("An inner event cut short fails loudly")
    void truncatedInnerEventFailsLoudly() throws IOException {
        byte[] inner = concat(PayloadFixtures.tableMap(9), PayloadFixtures.writeRow(9, 1, new byte[] {1, 2, 3}));
        byte[] cut = Arrays.copyOf(inner, inner.length - 2);
        byte[] body = PayloadFixtures.body(Zstd.compress(cut), 0, (long) cut.length);
        TransactionPayloadEventData data = decode(body, new HashMap<>());
        assertThrows(PayloadEventDecodingException.class, () -> dispatch(data));
    }

    @Test
    @DisplayName("Several concatenated zstd frames decode as one stream")
    void concatenatedFramesDecode() throws IOException {
        byte[] a = concat(PayloadFixtures.tableMap(9), PayloadFixtures.writeRow(9, 1, new byte[] {1}));
        byte[] b = concat(PayloadFixtures.writeRow(9, 2, new byte[] {2}), PayloadFixtures.xid(5));
        byte[] compressed = concat(Zstd.compress(a), Zstd.compress(b));
        byte[] body = PayloadFixtures.body(compressed, 0, (long) (a.length + b.length));
        List<Event> events = dispatch(decode(body, new HashMap<>()));
        assertEquals(4, events.size());
        assertEquals(5L, ((XidEventData) events.get(3).getData()).getXid());
    }

    @Test
    @DisplayName("Compression type NONE is streamed raw; an unknown compression type is refused")
    void compressionTypes() throws IOException {
        byte[] inner = concat(PayloadFixtures.tableMap(9), PayloadFixtures.writeRow(9, 1, new byte[] {7}),
                PayloadFixtures.xid(6));
        List<Event> events = dispatch(decode(PayloadFixtures.body(inner, 255, null), new HashMap<>()));
        assertEquals(3, events.size());
        WriteRowsEventData rows = events.get(1).getData();
        assertArrayEquals(new byte[] {7}, (byte[]) rows.getRows().get(0)[1]);

        assertThrows(PayloadEventDecodingException.class,
                () -> decode(PayloadFixtures.body(inner, 1, (long) inner.length), new HashMap<>()));
    }

    @Test
    @DisplayName("A payload nested in a payload is refused")
    void nestedPayloadRefused() throws IOException {
        byte[] inner = PayloadFixtures.event(40, new byte[] {0});
        TransactionPayloadEventData data = decode(PayloadFixtures.zstdBody(inner), new HashMap<>());
        assertThrows(PayloadEventDecodingException.class, () -> dispatch(data));
    }

    @Test
    @DisplayName("A body that does not parse fails loudly on getData(), never skipped")
    void unparseableBodyFailsOnGetData() throws IOException {
        // A WRITE_ROWS whose table id has no TABLE_MAP: Debezium's row deserializer cannot resolve it.
        byte[] inner = concat(PayloadFixtures.writeRow(77, 1, new byte[] {1}), PayloadFixtures.xid(5));
        TransactionPayloadEventData data = decode(PayloadFixtures.zstdBody(inner), new HashMap<>());
        Iterator<Event> it = data.getUncompressedEvents().iterator();
        Event rowsEvent = it.next();
        PayloadEventDecodingException e = assertThrows(PayloadEventDecodingException.class, rowsEvent::getData);
        assertTrue(e.getMessage().contains("EXT_WRITE_ROWS"), e.getMessage());
    }

    @Test
    @DisplayName("The streamed list refuses indexing, sizing, copying and mutation instead of answering from an empty array")
    void listIsIterationOnly() throws IOException {
        ArrayList<Event> events = decode(PayloadFixtures.mysql80Fixture(), new HashMap<>()).getUncompressedEvents();
        assertThrows(UnsupportedOperationException.class, events::size);
        assertThrows(UnsupportedOperationException.class, events::isEmpty);
        assertThrows(UnsupportedOperationException.class, () -> events.get(0));
        assertThrows(UnsupportedOperationException.class, events::toArray);
        assertThrows(UnsupportedOperationException.class, () -> new ArrayList<>(events));
        assertThrows(UnsupportedOperationException.class, () -> events.add(null));
        assertThrows(UnsupportedOperationException.class, events::listIterator);
        assertEquals(12, events.stream().count());
        assertFalse(String.valueOf(events).contains("Event{"), "toString must not iterate");
    }

    @Test
    @DisplayName("The preflight reports this connector's streaming decoder as the one on the classpath")
    void preflightSeesTheStreamingDecoder() {
        assertEquals(TransactionPayloadDeserializer.STREAMING_DECODER,
                BinlogTransactionCompressionPreflightAccess.streamingDecoderMarker());
        assertTrue(BinlogTransactionCompressionPreflightAccess.decoderSelfTest());
    }

    // ------------------------------------------------------------------

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static String tail(String s) {
        return s.length() <= 2000 ? s : s.substring(s.length() - 2000);
    }

    /**
     * The test classpath for the child JVM: the loader's URLs when it is a URLClassLoader
     * (surefire in-process), else java.class.path (a forked surefire's manifest-only jar
     * carries the classpath in its manifest).
     */
    private static String testClasspath() {
        ClassLoader loader = StreamingTransactionPayloadTest.class.getClassLoader();
        if (loader instanceof URLClassLoader) {
            List<String> parts = new ArrayList<>();
            for (URL url : ((URLClassLoader) loader).getURLs()) {
                try {
                    parts.add(new File(url.toURI()).getPath());
                } catch (Exception e) {
                    parts.add(url.getPath());
                }
            }
            return String.join(File.pathSeparator, parts);
        }
        return System.getProperty("java.class.path");
    }
}

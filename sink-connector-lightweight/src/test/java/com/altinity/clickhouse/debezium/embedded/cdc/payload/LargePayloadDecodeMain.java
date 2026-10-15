package com.altinity.clickhouse.debezium.embedded.cdc.payload;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.event.WriteRowsEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.binlog.event.TransactionPayloadDeserializer;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Run in a child JVM with a small heap by {@code StreamingTransactionPayloadTest}
 * (spec 01.08 §5): builds a Transaction_payload whose uncompressed size is given on the
 * command line (above 4 GiB in the test: past both the int and the uint32 limit), decodes
 * it through the connector's TRANSACTION_PAYLOAD decoder the way the binlog client and
 * Debezium use it -- two registration passes that read no row data, then a dispatch pass
 * that parses every event -- and verifies every row, the blob checksum and the XID.
 *
 * <p>Prints one line starting with {@code OK} and exits 0, or prints {@code FAIL ...} and
 * exits 1. An OutOfMemoryError (the materializing decoder's failure mode) exits non-zero.</p>
 *
 * <p>Arguments: {@code <rows> <blobSize>}.</p>
 */
public final class LargePayloadDecodeMain {

    private LargePayloadDecodeMain() {
    }

    public static void main(String[] args) throws Exception {
        long rows = Long.parseLong(args[0]);
        int blobSize = Integer.parseInt(args[1]);
        long tableId = 4242;
        long xid = 0x0A0B0C0D0E0FL;

        PayloadFixtures.Written w = PayloadFixtures.writeLargeStream(tableId, rows, blobSize, xid);
        byte[] body = PayloadFixtures.body(w.compressed, StreamedPayloadEvents.COMPRESSION_ZSTD, w.uncompressedBytes);
        w.compressed = null;

        Map<Long, TableMapEventData> tableMaps = new HashMap<>();
        TransactionPayloadEventData data = new TransactionPayloadDeserializer(tableMaps,
                CommonConnectorConfig.EventProcessingFailureHandlingMode.FAIL).deserialize(new ByteArrayInputStream(body));
        if (!(data instanceof StreamingTransactionPayloadEventData)) {
            fail("decoder returned " + data.getClass().getName());
        }
        long declared = ((StreamingTransactionPayloadEventData) data).getUncompressedSizeLong();
        if (declared != w.uncompressedBytes) {
            fail("declared uncompressed size " + declared + " != written " + w.uncompressedBytes);
        }

        // Registration passes (the binlog client's and Debezium's TABLE_MAP loops): no row data read.
        for (int pass = 0; pass < 2; pass++) {
            long seen = 0;
            for (Event e : data.getUncompressedEvents()) {
                seen++;
                if (e instanceof LazyPayloadEvent && ((LazyPayloadEvent) e).isParsed()) {
                    fail("registration pass parsed a rows event");
                }
            }
            if (seen != rows + 2) {
                fail("registration pass " + pass + " saw " + seen + " events, expected " + (rows + 2));
            }
        }

        // Dispatch pass: parse and verify everything.
        long nextId = 1;
        long rowCount = 0;
        boolean sawTableMap = false;
        Long seenXid = null;
        CRC32 crc = new CRC32();
        for (Event e : data.getUncompressedEvents()) {
            EventType type = e.getHeader().getEventType();
            if (type == EventType.TABLE_MAP) {
                sawTableMap = ((TableMapEventData) e.getData()).getTableId() == tableId;
            } else if (type == EventType.EXT_WRITE_ROWS) {
                WriteRowsEventData rowsData = e.getData();
                for (Serializable[] row : rowsData.getRows()) {
                    int id = ((Number) row[0]).intValue();
                    if (id != nextId) {
                        fail("row id " + id + " out of order, expected " + nextId);
                    }
                    byte[] blob = (byte[]) row[1];
                    if (blob.length != blobSize) {
                        fail("row " + id + " blob length " + blob.length);
                    }
                    crc.update(blob);
                    nextId++;
                    rowCount++;
                }
            } else if (type == EventType.XID) {
                seenXid = ((XidEventData) e.getData()).getXid();
            } else {
                fail("unexpected inner event " + type);
            }
        }
        if (!sawTableMap || rowCount != rows || seenXid == null || seenXid != xid || crc.getValue() != w.blobCrc) {
            fail("dispatch: tableMap=" + sawTableMap + " rows=" + rowCount + "/" + rows + " xid=" + seenXid
                    + " crc=" + crc.getValue() + "/" + w.blobCrc);
        }
        Runtime rt = Runtime.getRuntime();
        System.out.println("OK uncompressedBytes=" + w.uncompressedBytes + " compressedBytes=" + body.length
                + " rows=" + rowCount + " maxHeapBytes=" + rt.maxMemory());
        System.exit(0);
    }

    private static void fail(String message) {
        System.out.println("FAIL " + message);
        System.exit(1);
    }
}

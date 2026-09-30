package com.altinity.clickhouse.debezium.embedded.cdc.payload;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.zip.CRC32;

/**
 * Builders for {@code Transaction_payload} event bodies used by the streaming-decoder tests
 * (spec 01.08 §5): the real MySQL 8.0 fixture, and synthetic payloads of any size built
 * from well-formed binlog v4 events (TABLE_MAP + WRITE_ROWS v2 of a table
 * {@code (id INT, b LONGBLOB)}, XID) compressed on the fly, so a payload far larger than
 * the test's heap never exists uncompressed in memory.
 */
final class PayloadFixtures {

    /** A real MySQL 8.0.46 Transaction_payload body: 12 inner events (see the resource's test). */
    static final String MYSQL80_FIXTURE = "/binlog/transaction_payload_mysql80.bin";

    static final int TYPE_XID = 16;
    static final int TYPE_TABLE_MAP = 19;
    static final int TYPE_WRITE_ROWS_V2 = 30;
    static final int MYSQL_TYPE_LONG = 3;
    static final int MYSQL_TYPE_BLOB = 252;
    static final int HEADER_LENGTH = 19;

    private PayloadFixtures() {
    }

    static byte[] mysql80Fixture() {
        try (InputStream in = PayloadFixtures.class.getResourceAsStream(MYSQL80_FIXTURE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + MYSQL80_FIXTURE);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // Payload bodies
    // ------------------------------------------------------------------

    /** TLV header (MySQL's field order: compression type, uncompressed size, payload size) + payload bytes. */
    static byte[] body(byte[] payload, int compressionType, Long uncompressedSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 32);
        writeField(out, 2, compressionType);
        if (uncompressedSize != null) {
            writeField(out, 3, uncompressedSize);
        }
        writeField(out, 1, payload.length);
        out.write(0); // end mark
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    static byte[] zstdBody(byte[] innerEvents) {
        return body(Zstd.compress(innerEvents, 3), StreamedPayloadEvents.COMPRESSION_ZSTD, (long) innerEvents.length);
    }

    private static void writeField(ByteArrayOutputStream out, int type, long value) {
        byte[] encoded = packed(value);
        writePacked(out, type);
        writePacked(out, encoded.length);
        out.write(encoded, 0, encoded.length);
    }

    private static void writePacked(ByteArrayOutputStream out, long value) {
        byte[] b = packed(value);
        out.write(b, 0, b.length);
    }

    /** MySQL length-encoded integer, all four forms (1, 0xFC+2, 0xFD+3, 0xFE+8). */
    static byte[] packed(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(9);
        if (value < 251) {
            out.write((int) value);
        } else if (value < (1L << 16)) {
            out.write(0xFC);
            le(out, value, 2);
        } else if (value < (1L << 24)) {
            out.write(0xFD);
            le(out, value, 3);
        } else {
            out.write(0xFE);
            le(out, value, 8);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // Binlog v4 events
    // ------------------------------------------------------------------

    static byte[] event(int type, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_LENGTH + body.length);
        le(out, 1_700_000_000L, 4);           // timestamp
        out.write(type);
        le(out, 7L, 4);                       // server id
        le(out, HEADER_LENGTH + body.length, 4); // event size
        le(out, 0L, 4);                       // log pos (0 inside a payload)
        le(out, 0L, 2);                       // flags
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    /** TABLE_MAP for {@code big.t (id INT NOT NULL, b LONGBLOB NULL)}. */
    static byte[] tableMap(long tableId) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        le(b, tableId, 6);
        le(b, 1L, 2);                  // flags
        b.write(3);
        b.writeBytes("big".getBytes());
        b.write(0);
        b.write(1);
        b.writeBytes("t".getBytes());
        b.write(0);
        b.write(2);                    // column count
        b.write(MYSQL_TYPE_LONG);
        b.write(MYSQL_TYPE_BLOB);
        b.write(1);                    // metadata length
        b.write(4);                    // BLOB: 4 length bytes (LONGBLOB)
        b.write(0b10);                 // nullability: b nullable
        return event(TYPE_TABLE_MAP, b.toByteArray());
    }

    /** WRITE_ROWS v2, one row {@code (id, blob)}. */
    static byte[] writeRow(long tableId, int id, byte[] blob) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(blob.length + 32);
        le(b, tableId, 6);
        le(b, 1L, 2);                  // flags: STMT_END_F
        le(b, 2L, 2);                  // extra-data length (2 = none)
        b.write(2);                    // column count
        b.write(0b11);                 // columns present
        b.write(0);                    // row null bitmap
        le(b, id, 4);
        le(b, blob.length, 4);
        b.write(blob, 0, blob.length);
        return event(TYPE_WRITE_ROWS_V2, b.toByteArray());
    }

    static byte[] xid(long xid) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(8);
        le(b, xid, 8);
        return event(TYPE_XID, b.toByteArray());
    }

    /** The blob of row {@code id}: deterministic, compressible, differs per row. */
    static byte[] blob(int id, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (i * 31 + id);
        }
        return b;
    }

    /** What {@link #writeLargeStream} wrote. */
    static final class Written {
        long uncompressedBytes;
        long rows;
        long blobCrc;
        byte[] compressed;
    }

    /**
     * A TABLE_MAP, {@code rows} WRITE_ROWS events of {@code blobSize}-byte blobs, and an XID,
     * zstd-compressed as they are generated (level 1): the uncompressed stream is never held.
     */
    static Written writeLargeStream(long tableId, long rows, int blobSize, long xid) throws IOException {
        Written w = new Written();
        ByteArrayOutputStream sink = new ByteArrayOutputStream(1 << 20);
        CRC32 crc = new CRC32();
        try (CountingOutputStream counted = new CountingOutputStream(new ZstdOutputStream(sink, 1))) {
            counted.write(tableMap(tableId));
            for (int id = 1; id <= rows; id++) {
                byte[] blob = blob(id, blobSize);
                crc.update(blob);
                counted.write(writeRow(tableId, id, blob));
            }
            counted.write(xid(xid));
            counted.flush();
            w.uncompressedBytes = counted.count;
        }
        w.rows = rows;
        w.blobCrc = crc.getValue();
        w.compressed = sink.toByteArray();
        return w;
    }

    private static void le(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static final class CountingOutputStream extends java.io.FilterOutputStream {
        long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }
    }
}

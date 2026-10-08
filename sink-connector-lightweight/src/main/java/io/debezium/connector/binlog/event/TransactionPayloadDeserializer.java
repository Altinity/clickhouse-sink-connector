/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modified by the ClickHouse Sink Connector maintainers: this file REPLACES Debezium
 * 3.7.0.Final's class of the same name (first written against 3.1.3.Final) inside the lightweight connector's jar (the
 * project's own classes win over dependency classes when the jar is shaded). The stock
 * class decompresses a whole Transaction_payload into one byte array sized by an int
 * field and materializes every inner event, so a transaction whose uncompressed payload
 * exceeds 2 GiB cannot be decoded and one that fits costs its whole uncompressed size in
 * heap. This version streams it. See spec 01.08 §3.2.
 */
package io.debezium.connector.binlog.event;

import java.io.IOException;
import java.util.Map;

import com.altinity.clickhouse.debezium.embedded.cdc.payload.PayloadEventDecodingException;
import com.altinity.clickhouse.debezium.embedded.cdc.payload.StreamedPayloadEvents;
import com.altinity.clickhouse.debezium.embedded.cdc.payload.StreamingTransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.TransactionPayloadEventDataDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import io.debezium.config.CommonConnectorConfig;

/**
 * Decodes a {@code TRANSACTION_PAYLOAD} event into a
 * {@link StreamingTransactionPayloadEventData} whose inner events are decompressed and
 * parsed while they are iterated, with no limit on the uncompressed size.
 *
 * <p>Same constructor and the same inner-event deserializers as Debezium's class
 * (Debezium's own {@link RowDeserializers} for the six rows event types, sharing the
 * connector's table-id map), so the events dispatched are the ones the stock decoder
 * produced; only when and how often they are held in memory differs.</p>
 *
 * @author Chris Cranford (original)
 */
public class TransactionPayloadDeserializer extends TransactionPayloadEventDataDeserializer {

    /**
     * Read at start by the connector's compression preflight to prove this class -- not the
     * stock one -- is the one on the runtime classpath (spec 01.08 §3.4). Changing the value
     * is a spec change.
     */
    public static final String STREAMING_DECODER = "streaming-unbounded-v1";

    private final Map<Long, TableMapEventData> tableMapEventByTableId;
    private final CommonConnectorConfig.EventProcessingFailureHandlingMode eventDeserializationFailureHandlingMode;
    /**
     * Debezium 3.7 passes its {@code preserveInvalidTemporalValues} setting through
     * to the row deserializers; the stock class gained a three-argument constructor
     * that {@code BinlogStreamingChangeEventSource} calls. Without the same
     * constructor here the task fails with {@code NoSuchMethodError} at start.
     */
    private final boolean preserveInvalidTemporalValues;

    public TransactionPayloadDeserializer(Map<Long, TableMapEventData> tableMapEventByTableId,
                                          CommonConnectorConfig.EventProcessingFailureHandlingMode eventDeserializationFailureHandlingMode) {
        this(tableMapEventByTableId, eventDeserializationFailureHandlingMode, false);
    }

    public TransactionPayloadDeserializer(Map<Long, TableMapEventData> tableMapEventByTableId,
                                          CommonConnectorConfig.EventProcessingFailureHandlingMode eventDeserializationFailureHandlingMode,
                                          boolean preserveInvalidTemporalValues) {
        this.tableMapEventByTableId = tableMapEventByTableId;
        this.eventDeserializationFailureHandlingMode = eventDeserializationFailureHandlingMode;
        this.preserveInvalidTemporalValues = preserveInvalidTemporalValues;
    }

    @Override
    public TransactionPayloadEventData deserialize(ByteArrayInputStream inputStream) throws IOException {
        long payloadSize = -1;
        int compressionType = StreamedPayloadEvents.COMPRESSION_ZSTD;
        long uncompressedSize = 0;
        boolean hasUncompressedSize = false;
        // Header: type-length-value fields, each value a length-encoded integer, until the end mark.
        while (inputStream.available() > 0) {
            int fieldType = inputStream.readPackedInteger();
            if (fieldType == OTW_PAYLOAD_HEADER_END_MARK) {
                break;
            }
            int fieldLength = inputStream.readPackedInteger();
            switch (fieldType) {
                case OTW_PAYLOAD_SIZE_FIELD:
                    payloadSize = readPackedLong(inputStream, "payload size");
                    break;
                case OTW_PAYLOAD_COMPRESSION_TYPE_FIELD:
                    compressionType = inputStream.readPackedInteger();
                    break;
                case OTW_PAYLOAD_UNCOMPRESSED_SIZE_FIELD:
                    // A 64-bit quantity: the stock decoder read it as an int and failed above 2 GiB.
                    uncompressedSize = readPackedLong(inputStream, "uncompressed size");
                    hasUncompressedSize = true;
                    break;
                default:
                    // Unknown field: skip its value, as the stock decoder does.
                    inputStream.read(fieldLength);
                    break;
            }
        }
        int remaining = inputStream.available();
        if (payloadSize < 0) {
            throw new PayloadEventDecodingException("Transaction_payload header carries no payload size field");
        }
        if (payloadSize > remaining) {
            // The compressed payload is bounded by the source's replication packet limit (1 GiB),
            // so it always fits one array; a larger declared size is a corrupt header.
            throw new PayloadEventDecodingException("Transaction_payload header declares " + payloadSize
                    + " payload bytes but the event carries " + remaining);
        }
        if (!hasUncompressedSize && compressionType == StreamedPayloadEvents.COMPRESSION_ZSTD) {
            // MySQL's encoder writes the uncompressed-size field for every compressed payload (it omits it
            // only for compression type NONE): libbinlogevents/src/codecs/binary.cpp,
            // Transaction_payload::encode. The end-of-pass byte count is checked against this field, and
            // without it a zstd stream cut at a frame boundary would decode "successfully" to a shorter
            // transaction, so a ZSTD payload without it is refused rather than trusted (spec 01.08 §3.2.1).
            throw new PayloadEventDecodingException("Transaction_payload header declares compression type ZSTD "
                    + "but carries no uncompressed-size field, which every MySQL writer emits for a compressed "
                    + "payload; the payload's integrity could not be verified");
        }
        byte[] payload = inputStream.read((int) payloadSize);
        if (!hasUncompressedSize) {
            // Compression type NONE: MySQL omits the field and the payload IS the uncompressed stream.
            uncompressedSize = payloadSize;
        }
        StreamedPayloadEvents events = new StreamedPayloadEvents(payload, compressionType, uncompressedSize,
                hasUncompressedSize, innerEventDeserializer(), tableMapEventByTableId);
        return new StreamingTransactionPayloadEventData(payloadSize, compressionType, uncompressedSize, payload, events);
    }

    /** The inner-event deserializer Debezium's class builds, unchanged. */
    private EventDeserializer innerEventDeserializer() {
        EventDeserializer deserializer = new EventDeserializer();
        deserializer.setEventDataDeserializer(EventType.WRITE_ROWS,
                new RowDeserializers.WriteRowsDeserializer(tableMapEventByTableId, eventDeserializationFailureHandlingMode, preserveInvalidTemporalValues));
        deserializer.setEventDataDeserializer(EventType.UPDATE_ROWS,
                new RowDeserializers.UpdateRowsDeserializer(tableMapEventByTableId, eventDeserializationFailureHandlingMode, preserveInvalidTemporalValues));
        deserializer.setEventDataDeserializer(EventType.DELETE_ROWS,
                new RowDeserializers.DeleteRowsDeserializer(tableMapEventByTableId, eventDeserializationFailureHandlingMode, preserveInvalidTemporalValues));
        deserializer.setEventDataDeserializer(EventType.EXT_WRITE_ROWS,
                new RowDeserializers.WriteRowsDeserializer(
                        tableMapEventByTableId, eventDeserializationFailureHandlingMode, preserveInvalidTemporalValues).setMayContainExtraInformation(true));
        deserializer.setEventDataDeserializer(EventType.EXT_UPDATE_ROWS,
                new RowDeserializers.UpdateRowsDeserializer(
                        tableMapEventByTableId, eventDeserializationFailureHandlingMode, preserveInvalidTemporalValues).setMayContainExtraInformation(true));
        deserializer.setEventDataDeserializer(EventType.EXT_DELETE_ROWS,
                new RowDeserializers.DeleteRowsDeserializer(
                        tableMapEventByTableId, eventDeserializationFailureHandlingMode, preserveInvalidTemporalValues).setMayContainExtraInformation(true));
        return deserializer;
    }

    private static long readPackedLong(ByteArrayInputStream inputStream, String field) throws IOException {
        Number value = inputStream.readPackedNumber();
        if (value == null) {
            throw new PayloadEventDecodingException("Transaction_payload header field '" + field + "' is NULL");
        }
        long v = value.longValue();
        if (v < 0) {
            // An 8-byte length-encoded integer above Long.MAX_VALUE: not a size any MySQL writes.
            throw new PayloadEventDecodingException("Transaction_payload header field '" + field
                    + "' is out of range: " + Long.toUnsignedString(v));
        }
        return v;
    }
}

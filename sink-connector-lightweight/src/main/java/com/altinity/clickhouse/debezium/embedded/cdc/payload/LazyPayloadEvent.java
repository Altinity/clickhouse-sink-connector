package com.altinity.clickhouse.debezium.embedded.cdc.payload;

import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeader;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDataDeserializationException;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDataDeserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import java.io.IOException;

/**
 * One event from inside a streamed {@code Transaction_payload}, whose body is
 * parsed the first time {@link #getData()} is called (spec 01.08 §3.2).
 *
 * <p>The payload's inner events are iterated more than once per transaction:
 * the binlog client and Debezium each walk them once to register the
 * {@code TABLE_MAP} events, then Debezium walks them again to dispatch. Only
 * the dispatch pass reads row data. Deferring the body parse to
 * {@code getData()} means the registration passes cost a decompression and a
 * header read per event, never a row parse; the dispatch pass parses each body
 * exactly once. {@code TABLE_MAP} events are never lazy (the iterator parses
 * them eagerly, in stream order, because the rows events after them are
 * resolved against them).</p>
 *
 * <p>A body that cannot be parsed fails loudly: {@link #getData()} throws
 * {@link PayloadEventDecodingException}, which Debezium treats as an error
 * processing the payload event and stops the connector. A compressed
 * transaction is never partially skipped.</p>
 */
public final class LazyPayloadEvent extends Event {

    private static final long serialVersionUID = 1L;

    private final transient EventDataDeserializer<?> deserializer;
    private transient byte[] body;
    private EventData parsed;
    private boolean isParsed;

    public LazyPayloadEvent(EventHeader header, byte[] body, EventDataDeserializer<?> deserializer) {
        super(header, null);
        this.body = body;
        this.deserializer = deserializer;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends EventData> T getData() {
        if (!isParsed) {
            parsed = parse();
            isParsed = true;
            body = null; // the body is not needed once parsed
        }
        return (T) parsed;
    }

    private EventData parse() {
        EventHeader header = getHeader();
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            // Same framing as EventDeserializer.deserializeEventData: the body is one block;
            // events inside a payload carry no checksum (MySQL writes one for the payload event).
            in.enterBlock(body.length);
            try {
                return deserializer.deserialize(in);
            } finally {
                in.skipToTheEndOfTheBlock();
            }
        } catch (IOException e) {
            throw new PayloadEventDecodingException("Could not decode the " + header.getEventType()
                    + " event (" + body.length + " body bytes) inside a Transaction_payload",
                    new EventDataDeserializationException(header, e));
        }
    }

    /** True once the body has been parsed (tests use it to prove registration passes stay lazy). */
    public boolean isParsed() {
        return isParsed;
    }

    /**
     * Refused: the retained state is {@code transient} (native streams, the shared table-id map), so a
     * serialized copy could not decode. Fail at write time with the reason instead of at read time with
     * a NullPointerException (spec 01.08 §3.2.1 item 8).
     */
    private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
        throw new java.io.NotSerializableException(getClass().getName()
                + ": streamed Transaction_payload state is not serializable (spec 01.08 §3.2.1)");
    }

    private void readObject(java.io.ObjectInputStream in) throws java.io.IOException {
        throw new java.io.InvalidObjectException(getClass().getName()
                + ": streamed Transaction_payload state is not serializable (spec 01.08 §3.2.1)");
    }

    private void readObjectNoData() throws java.io.ObjectStreamException {
        throw new java.io.InvalidObjectException(getClass().getName()
                + ": streamed Transaction_payload state is not serializable (spec 01.08 §3.2.1)");
    }

    @Override
    public String toString() {
        return "LazyPayloadEvent{header=" + getHeader() + ", parsed=" + isParsed + '}';
    }
}

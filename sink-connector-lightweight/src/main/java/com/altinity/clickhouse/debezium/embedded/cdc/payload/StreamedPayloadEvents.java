package com.altinity.clickhouse.debezium.embedded.cdc.payload;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventData;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TableMapEventData;
import com.github.shyiko.mysql.binlog.event.deserialization.EventDeserializer;
import com.github.shyiko.mysql.binlog.event.deserialization.EventHeaderV4Deserializer;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Cleaner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The inner events of one {@code Transaction_payload}, decoded while they are
 * iterated (spec 01.08 §3.2).
 *
 * <p>Every call to {@link #iterator()} starts a fresh pass over the retained
 * compressed bytes: a zstd stream (or the raw bytes for compression type
 * {@code NONE}) is opened, and each {@code next()} reads one binlog v4 event
 * header and its body. Nothing of an earlier event is retained by the list, so a
 * pass holds the compressed payload, the decompressor's window and one event,
 * whatever the uncompressed size -- there is no 2 GiB limit and no
 * whole-transaction buffer. At the end of each pass the number of bytes
 * decompressed is checked against the payload header's declared uncompressed
 * size; a mismatch, a truncated event or a corrupt frame throws
 * {@link PayloadEventDecodingException}.</p>
 *
 * <p>{@code TABLE_MAP} events are parsed eagerly and recorded in the shared
 * table-id map before they are returned, exactly as the materializing decoder
 * did, so the rows events that follow resolve against them. Every other event
 * is a {@link LazyPayloadEvent}: its body is parsed on {@code getData()}.</p>
 *
 * <p>The type is an {@link ArrayList} only because the binlog client's
 * {@code TransactionPayloadEventData} declares that type; the list is
 * iteration-only. Iterating it (for-each, {@link #forEach}, {@link #stream()})
 * is the supported access; indexing, sizing, copying and mutating it are refused
 * with {@link UnsupportedOperationException} -- loudly, because the inherited
 * {@code ArrayList} implementations would silently answer from an empty backing
 * array.</p>
 *
 * <p>Not thread-safe: a pass runs on the binlog reader thread, like the decoder
 * it replaces.</p>
 */
public final class StreamedPayloadEvents extends ArrayList<Event> {

    private static final long serialVersionUID = 1L;

    /** MySQL {@code binary_log::transaction::compression::type}. */
    public static final int COMPRESSION_ZSTD = 0;
    public static final int COMPRESSION_NONE = 255;

    /** Largest window a zstd frame may declare (ZSTD_WINDOWLOG_MAX on 64-bit): accept every level MySQL can write. */
    static final int ZSTD_WINDOW_LOG_MAX = 31;

    private static final int READ_BUFFER_BYTES = 1 << 16;
    private static final int V4_HEADER_LENGTH = 19;
    private static final Cleaner CLEANER = Cleaner.create();
    private static final String ITERATE_ONLY =
            "the inner events of a Transaction_payload are streamed (spec 01.08 §3.2): iterate them; "
                    + "indexing, sizing, copying or mutating would materialize the whole transaction";

    private final transient byte[] compressed;
    private final int compressionType;
    private final long declaredUncompressedSize;
    private final boolean hasDeclaredSize;
    private final transient EventDeserializer innerDeserializer;
    private final transient Map<Long, TableMapEventData> tableMapEventByTableId;

    /**
     * @param compressed               the payload bytes, as read from the event
     * @param compressionType          {@link #COMPRESSION_ZSTD} or {@link #COMPRESSION_NONE}
     * @param declaredUncompressedSize the uncompressed size from the payload header
     * @param hasDeclaredSize          whether the header carried the uncompressed-size field
     * @param innerDeserializer        supplies the data deserializer of each inner event type
     * @param tableMapEventByTableId   the shared table-id map the row deserializers read
     */
    public StreamedPayloadEvents(byte[] compressed, int compressionType, long declaredUncompressedSize,
                                 boolean hasDeclaredSize, EventDeserializer innerDeserializer,
                                 Map<Long, TableMapEventData> tableMapEventByTableId) {
        super(0);
        if (compressionType != COMPRESSION_ZSTD && compressionType != COMPRESSION_NONE) {
            throw new PayloadEventDecodingException("Unsupported Transaction_payload compression type "
                    + compressionType + " (MySQL writes " + COMPRESSION_ZSTD + " = ZSTD or "
                    + COMPRESSION_NONE + " = NONE)");
        }
        this.compressed = compressed;
        this.compressionType = compressionType;
        this.declaredUncompressedSize = declaredUncompressedSize;
        this.hasDeclaredSize = hasDeclaredSize;
        this.innerDeserializer = innerDeserializer;
        this.tableMapEventByTableId = tableMapEventByTableId;
    }

    // ------------------------------------------------------------------
    // Iteration: the supported access
    // ------------------------------------------------------------------

    @Override
    public Iterator<Event> iterator() {
        return new Pass();
    }

    @Override
    public void forEach(Consumer<? super Event> action) {
        Iterator<Event> it = iterator();
        while (it.hasNext()) {
            action.accept(it.next());
        }
    }

    @Override
    public Spliterator<Event> spliterator() {
        return Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED | Spliterator.NONNULL);
    }

    /** One decoding pass over the payload. */
    private final class Pass implements Iterator<Event> {
        private final CountingInputStream counted;
        private final ByteArrayInputStream in;
        private final Cleaner.Cleanable cleanable;
        private final EventHeaderV4Deserializer headerDeserializer = new EventHeaderV4Deserializer();
        private Event pending;
        private boolean finished;
        private long eventsRead;

        Pass() {
            InputStream raw = new java.io.ByteArrayInputStream(compressed);
            InputStream decoded;
            if (compressionType == COMPRESSION_ZSTD) {
                try {
                    decoded = new ZstdInputStreamNoFinalizer(raw).setLongMax(ZSTD_WINDOW_LOG_MAX);
                } catch (IOException e) {
                    throw new PayloadEventDecodingException("Could not open the zstd stream of a Transaction_payload", e);
                }
            } else {
                decoded = raw;
            }
            // The native zstd stream is closed at the end of the pass; the cleaner frees it if a
            // pass is abandoned half way (an exception in a handler).
            this.cleanable = CLEANER.register(this, new Closer(decoded));
            this.counted = new CountingInputStream(new BufferedInputStream(decoded, READ_BUFFER_BYTES));
            this.in = new ByteArrayInputStream(counted);
        }

        @Override
        public boolean hasNext() {
            if (pending == null && !finished) {
                pending = advance();
            }
            return pending != null;
        }

        @Override
        public Event next() {
            if (!hasNext()) {
                throw new NoSuchElementException("end of the Transaction_payload");
            }
            Event event = pending;
            pending = null;
            return event;
        }

        private Event advance() {
            try {
                if (in.peek() == -1) {
                    finish();
                    return null;
                }
                EventHeaderV4 header = headerDeserializer.deserialize(in);
                long bodyLength = header.getEventLength() - V4_HEADER_LENGTH;
                if (bodyLength < 0 || bodyLength > Integer.MAX_VALUE - 8) {
                    throw new PayloadEventDecodingException("Inner event #" + (eventsRead + 1) + " of a "
                            + "Transaction_payload declares an impossible length " + header.getEventLength()
                            + " (" + header + ")");
                }
                byte[] body = in.read((int) bodyLength);
                eventsRead++;
                EventType type = header.getEventType();
                if (type == EventType.TRANSACTION_PAYLOAD) {
                    throw new PayloadEventDecodingException("A Transaction_payload nested inside a "
                            + "Transaction_payload (inner event #" + eventsRead + ") is not a MySQL format");
                }
                if (type == EventType.TABLE_MAP) {
                    EventData data = innerDeserializer.deserializeTableMapEventData(new ByteArrayInputStream(body), header);
                    if (data instanceof TableMapEventData) {
                        TableMapEventData tableMap = (TableMapEventData) data;
                        tableMapEventByTableId.put(tableMap.getTableId(), tableMap);
                    }
                    return new Event(header, data);
                }
                return new LazyPayloadEvent(header, body, innerDeserializer.getEventDataDeserializer(type));
            } catch (IOException e) {
                close();
                throw new PayloadEventDecodingException("Could not decode inner event #" + (eventsRead + 1)
                        + " of a Transaction_payload after " + counted.count + " of "
                        + declaredUncompressedSize + " declared uncompressed bytes", e);
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }

        private void finish() {
            close();
            long expected = hasDeclaredSize ? declaredUncompressedSize
                    : (compressionType == COMPRESSION_NONE ? compressed.length : -1);
            if (expected >= 0 && counted.count != expected) {
                throw new PayloadEventDecodingException("A Transaction_payload decompressed to " + counted.count
                        + " bytes in " + eventsRead + " events, but its header declares " + expected
                        + " uncompressed bytes");
            }
        }

        private void close() {
            finished = true;
            cleanable.clean();
        }
    }

    /** Closes the decompressor; holds no reference to the pass so the cleaner can run. */
    private static final class Closer implements Runnable {
        private final InputStream stream;

        Closer(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try {
                stream.close();
            } catch (IOException ignored) {
                // closing an in-memory decompressor: nothing to recover and nothing lost
            }
        }
    }

    /** Counts the bytes the event reader consumed, in 64 bits. */
    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }

    // ------------------------------------------------------------------
    // Everything else is refused: the backing array is always empty
    // ------------------------------------------------------------------

    @Override
    public int size() {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public Event get(int index) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean contains(Object o) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public int indexOf(Object o) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public int lastIndexOf(Object o) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public Object[] toArray() {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public <T> T[] toArray(T[] a) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public ListIterator<Event> listIterator() {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public ListIterator<Event> listIterator(int index) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public List<Event> subList(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean add(Event e) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public void add(int index, Event element) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean addAll(Collection<? extends Event> c) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean addAll(int index, Collection<? extends Event> c) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public Event set(int index, Event element) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public Event remove(int index) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean removeIf(Predicate<? super Event> filter) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public void replaceAll(UnaryOperator<Event> operator) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public void sort(Comparator<? super Event> c) {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public Object clone() {
        throw new UnsupportedOperationException(ITERATE_ONLY);
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "StreamedPayloadEvents{compressionType=" + compressionType + ", compressedBytes="
                + compressed.length + ", declaredUncompressedBytes=" + declaredUncompressedSize + '}';
    }
}

package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.query.QuerySettings;
import com.clickhouse.data.ClickHouseFormat;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The spill-to-disk INSERT path (spec 03.06 section 3.4).
 *
 * <p><b>Why.</b> The V2 JDBC driver renders a chunk as SQL text in memory: every
 * bound value is encoded into a {@code String}, every row is substituted into a
 * {@code StringBuilder}, the whole chunk is concatenated into one more
 * {@code StringBuilder}, turned into a {@code String} and finally into a UTF-8
 * {@code byte[]} request body. A single value of {@code V} bytes therefore costs
 * five to six live copies of its text (twice its size again for the hex text of
 * a binary value), and a chunk of {@code buffer.max.bytes} of small rows several
 * copies of the chunk. Measured (JFR, one 64 MiB and one 256 MiB LONGBLOB row,
 * each inserted and updated in one transaction): 17.5 GiB of the 22.4 GiB
 * allocated were these driver copies, and a 4 GiB heap ran out of memory on the
 * 256 MiB row and crash-looped on every restart.</p>
 *
 * <p><b>What.</b> A {@link PreparedStatement} proxy that the executor uses for a
 * chunk whose rendered size bound reaches {@code insert.spill.threshold.bytes}.
 * It keeps the driver as the ENCODER -- every parameter is still bound through
 * the driver's own setter, which encodes it exactly as before into the driver's
 * {@code values} slot -- but it never lets the driver build the statement. On
 * {@code addBatch()} it writes the row straight to a spill file, substituting the
 * driver's encoded values into the driver's own row template at the driver's own
 * parameter positions. A value of {@link #LARGE_VALUE_CHARS} or more is not
 * handed to the driver at all: it is streamed from the row's own object into the
 * file with the driver's encoding ({@code '} + escaped text + {@code '} for a
 * string, lower-case hex for a binary value rendered as hex text,
 * {@code unhex('<HEX>')} for a binary value under {@code persist.raw.bytes}).
 * On {@code executeBatch()} the file is sent as the body of
 * {@code INSERT INTO <table>(<columns>) FORMAT Values} through the driver's own
 * {@link Client} with the statement's own settings -- the same rows, the same
 * literal text and the same parser ClickHouse uses for the data part of
 * {@code INSERT ... VALUES}, one INSERT per chunk as before. The heap then holds
 * one row's small values plus a 64 KiB write buffer instead of copies of the
 * chunk.</p>
 *
 * <p><b>Where the driver's internals are read.</b> The template, the parameter
 * positions, the encoded values and the statement settings are private fields
 * of {@code com.clickhouse.jdbc.PreparedStatementImpl} (jdbc-v2 0.9.8); they are
 * resolved once by reflection. If any is missing (a driver upgrade changed
 * them), the path refuses itself loudly ONCE at ERROR and every chunk keeps the
 * driver's in-memory path -- the pre-spill behaviour, still correct. The pin is
 * {@code SpillingInsertStatementTest}, which fails the build on such an
 * upgrade.</p>
 *
 * <p>Thread confinement: one instance per chunk, used by the one worker thread
 * that binds and executes the chunk (spec 03.01).</p>
 */
public final class SpillingInsertStatement implements InvocationHandler {

    private static final Logger log = LogManager.getLogger(SpillingInsertStatement.class);

    /** A value whose text is at least this many characters is streamed from its object (section 3.4 item 3). */
    public static final int LARGE_VALUE_CHARS = 64 * 1024;

    /** Prefix of every spill file; the stale-file sweep touches nothing else (section 3.4 item 6). */
    static final String FILE_PREFIX = "insert-";
    static final String FILE_SUFFIX = ".values";

    private static final char[] HEX_LOWER = "0123456789abcdef".toCharArray();
    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();
    private static final Pattern TABLE_SPEC =
            Pattern.compile("^\\s*INSERT\\s+INTO\\s+(.+?)\\s*VALUES\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Sends one finished spill file; the production sender is the driver's own {@link Client}. */
    @FunctionalInterface
    interface Sender {
        void send(String tableSpec, Path body, Map<String, Object> settings) throws SQLException;
    }

    // ------------------------------------------------------------------ driver internals (resolved once)
    private static final DriverAccess DRIVER = DriverAccess.resolve();
    private static final AtomicBoolean REFUSAL_LOGGED = new AtomicBoolean(false);
    private static final Map<Path, Boolean> SWEPT = Collections.synchronizedMap(new HashMap<>());

    static final class DriverAccess {
        final Class<?> psClass;
        final java.lang.reflect.Field values;
        final java.lang.reflect.Field template;
        final java.lang.reflect.Field positions;
        final java.lang.reflect.Field argCount;
        final java.lang.reflect.Field insertWithValues;
        final java.lang.reflect.Field originalSql;
        final java.lang.reflect.Field parsed;
        final java.lang.reflect.Field localSettings;
        final String failure;

        private DriverAccess(Class<?> psClass, java.lang.reflect.Field[] f, String failure) {
            this.psClass = psClass;
            this.values = f == null ? null : f[0];
            this.template = f == null ? null : f[1];
            this.positions = f == null ? null : f[2];
            this.argCount = f == null ? null : f[3];
            this.insertWithValues = f == null ? null : f[4];
            this.originalSql = f == null ? null : f[5];
            this.parsed = f == null ? null : f[6];
            this.localSettings = f == null ? null : f[7];
            this.failure = failure;
        }

        static DriverAccess resolve() {
            try {
                Class<?> c = Class.forName("com.clickhouse.jdbc.PreparedStatementImpl");
                String[] names = {"values", "valueListTmpl", "paramPositionsInDataClause", "argCount",
                        "insertStmtWithValues", "originalSql", "parsedPreparedStatement"};
                java.lang.reflect.Field[] f = new java.lang.reflect.Field[8];
                for (int i = 0; i < names.length; i++) {
                    f[i] = c.getDeclaredField(names[i]);
                    f[i].setAccessible(true);
                }
                f[7] = c.getSuperclass().getDeclaredField("localSettings");
                f[7].setAccessible(true);
                return new DriverAccess(c, f, null);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return new DriverAccess(null, null, e.toString());
            }
        }

        boolean available() {
            return failure == null;
        }
    }

    /** Whether the running driver exposes what the spill path needs (pinned by the unit test). */
    public static boolean driverSupported() {
        return DRIVER.available();
    }

    // ------------------------------------------------------------------ decision
    /**
     * An upper-bound estimate of the SQL text a record contributes, from the
     * images that may be bound for it (before and after): a string counts its
     * length plus quotes, a binary value twice its length plus the
     * {@code unhex('')} wrapper, a nested struct its fields, anything else 24.
     * Exact for the values that matter (strings and binaries) and cheap: one
     * pass over the top-level fields, no allocation.
     */
    public static long renderedSizeBound(ClickHouseStruct record) {
        if (record == null) {
            return 0L;
        }
        return structBound(record.getBeforeStruct(), 0) + structBound(record.getAfterStruct(), 0);
    }

    private static long structBound(Struct s, int depth) {
        if (s == null || depth > 4) {
            return 0L;
        }
        long total = 2L;
        for (Field f : s.schema().fields()) {
            total += valueBound(s.get(f), depth) + 1;
        }
        return total;
    }

    private static long valueBound(Object v, int depth) {
        if (v == null) {
            return 4L;
        }
        if (v instanceof CharSequence) {
            return ((CharSequence) v).length() + 2L;
        }
        if (v instanceof byte[]) {
            return 2L * ((byte[]) v).length + 9L;
        }
        if (v instanceof ByteBuffer) {
            return 2L * ((ByteBuffer) v).remaining() + 9L;
        }
        if (v instanceof Struct) {
            return structBound((Struct) v, depth + 1);
        }
        if (v instanceof Collection) {
            long t = 2L;
            for (Object o : (Collection<?>) v) {
                t += valueBound(o, depth + 1) + 1;
            }
            return t;
        }
        if (v instanceof Map) {
            long t = 2L;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                t += valueBound(e.getKey(), depth + 1) + valueBound(e.getValue(), depth + 1) + 2;
            }
            return t;
        }
        return 24L;
    }

    /** Sum of {@link #renderedSizeBound} over a chunk. */
    public static long renderedSizeBound(List<ClickHouseStruct> chunk) {
        long total = 0L;
        for (ClickHouseStruct r : chunk) {
            total += renderedSizeBound(r);
        }
        return total;
    }

    /**
     * Returns the statement the executor binds a chunk into: the driver's own
     * statement when the spill path is off ({@code threshold < 0}), when the
     * chunk's rendered size bound is under the threshold, or when the driver
     * does not expose what the path needs; otherwise a spilling proxy over it.
     *
     * @param real       the driver's statement for the chunk's INSERT template
     * @param conn       the connection it came from (its {@link Client} sends the file)
     * @param chunk      the chunk about to be bound
     * @param threshold  {@code insert.spill.threshold.bytes}; {@code 0} spills every chunk, negative never
     * @param directory  {@code insert.spill.directory} (the per-process subdirectory is added here)
     */
    public static PreparedStatement maybeWrap(PreparedStatement real, Connection conn, List<ClickHouseStruct> chunk,
                                              long threshold, String directory) throws SQLException {
        if (threshold < 0) {
            return real;
        }
        if (threshold > 0 && renderedSizeBound(chunk) < threshold) {
            return real;
        }
        if (!DRIVER.available()) {
            refuseOnce("the JDBC driver does not expose the statement internals the spill path reads ("
                    + DRIVER.failure + ")");
            return real;
        }
        Object impl;
        Client client;
        try {
            impl = real.isWrapperFor(DRIVER.psClass) ? real.unwrap(DRIVER.psClass) : null;
            com.clickhouse.jdbc.ConnectionImpl ci = conn.isWrapperFor(com.clickhouse.jdbc.ConnectionImpl.class)
                    ? conn.unwrap(com.clickhouse.jdbc.ConnectionImpl.class) : null;
            client = ci == null ? null : ci.getClient();
        } catch (SQLException | RuntimeException e) {
            impl = null;
            client = null;
        }
        if (impl == null || client == null) {
            refuseOnce("the statement or connection is not the V2 driver's ("
                    + real.getClass().getName() + ", " + conn.getClass().getName() + ")");
            return real;
        }
        final Client sendVia = client;
        Path dir;
        try {
            dir = processDirectory(directory);
        } catch (SQLException e) {
            // Every time, not once: a chunk that should have spilled is being rendered in memory.
            log.error("Spill-to-disk INSERT path unavailable for this chunk: {}. The chunk is rendered in memory "
                    + "by the JDBC driver (spec 03.06 section 3.4).", e.getMessage(), e);
            return real;
        }
        try {
            return wrapForClient((PreparedStatement) impl, real, dir, sendVia);
        } catch (SQLException e) {
            log.error("Spill-to-disk INSERT path unavailable for this chunk: {}. The chunk is rendered in memory "
                    + "by the JDBC driver (spec 03.06 section 3.4).", e.getMessage(), e);
            return real;
        }
    }

    private static PreparedStatement wrapForClient(PreparedStatement impl, PreparedStatement real, Path dir,
                                                   Client sendVia) throws SQLException {
        return wrap(impl, real, dir, (spec, body, settings) -> {
            try (InputStream in = Files.newInputStream(body);
                 InsertResponse ignored = sendVia.insert(spec, Collections.emptyList(), in,
                         ClickHouseFormat.Values, new InsertSettings(settings)).get()) {
                // the response carries nothing the executor uses; closing it releases the connection
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new SQLException(cause.getMessage(), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while sending the spilled INSERT for " + spec, e);
            } catch (IOException e) {
                throw new SQLException("Could not read the spilled INSERT body " + body + " for " + spec, e);
            } catch (RuntimeException e) {
                throw new SQLException(e.getMessage(), e);
            }
        });
    }

    /**
     * Builds the proxy over a driver statement (package-private for the tests,
     * which pass their own {@link Sender}).
     *
     * @param impl     the driver's {@code PreparedStatementImpl} (unwrapped)
     * @param outer    the statement the executor obtained (closed with the proxy)
     */
    static PreparedStatement wrap(PreparedStatement impl, PreparedStatement outer, Path directory, Sender sender)
            throws SQLException {
        try {
            if (!(Boolean) DRIVER.insertWithValues.get(impl)) {
                throw new SQLException("not an INSERT ... VALUES statement");
            }
            String sql = (String) DRIVER.originalSql.get(impl);
            Object parsed = DRIVER.parsed.get(impl);
            int start = (Integer) parsed.getClass().getMethod("getAssignValuesListStartPosition").invoke(parsed);
            Matcher m = TABLE_SPEC.matcher(sql.substring(0, start));
            if (!m.matches()) {
                throw new SQLException("cannot isolate the table and columns of: " + sql);
            }
            QuerySettings qs = (QuerySettings) DRIVER.localSettings.get(impl);
            Map<String, Object> settings = qs == null ? new HashMap<>() : new HashMap<>(qs.getAllSettings());
            SpillingInsertStatement h = new SpillingInsertStatement(impl, outer, m.group(1),
                    (String) DRIVER.template.get(impl), (int[]) DRIVER.positions.get(impl),
                    (Integer) DRIVER.argCount.get(impl), settings, directory, sender);
            return (PreparedStatement) Proxy.newProxyInstance(SpillingInsertStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, h);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new SQLException("Spill path cannot read the driver statement: " + e, e);
        }
    }

    private static void refuseOnce(String why) {
        if (REFUSAL_LOGGED.compareAndSet(false, true)) {
            log.error("Spill-to-disk INSERT path unavailable: {}. Chunks are rendered in memory by the JDBC driver "
                    + "as before (spec 03.06 section 3.4); a row too large for the heap will exhaust it "
                    + "(FM-03.06-5).", why);
        }
    }

    /**
     * The per-process spill directory {@code <directory>/<pid>}, created on
     * demand; on first use, spill files left by processes that are no longer
     * alive are removed from the sibling {@code <pid>} directories.
     */
    static Path processDirectory(String directory) throws SQLException {
        Path base = Paths.get(directory == null || directory.isEmpty()
                ? System.getProperty("java.io.tmpdir") + "/clickhouse-sink-connector-spill" : directory);
        Path own = base.resolve(Long.toString(ProcessHandle.current().pid()));
        try {
            Files.createDirectories(own);
        } catch (IOException e) {
            throw new SQLException("Cannot create the spill directory " + own + " (insert.spill.directory)", e);
        }
        if (SWEPT.putIfAbsent(base, Boolean.TRUE) == null) {
            sweepDeadProcesses(base);
        }
        return own;
    }

    private static void sweepDeadProcesses(Path base) {
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(base)) {
            for (Path d : dirs) {
                String n = d.getFileName().toString();
                if (!n.matches("\\d+") || !Files.isDirectory(d)) {
                    continue;
                }
                long pid = Long.parseLong(n);
                if (pid == ProcessHandle.current().pid()) {
                    // Our own pid: in a container the connector is pid 1 on every start, so this
                    // directory may hold a previous incarnation's leftovers -- only files older
                    // than this process can be those.
                    sweepOlderThanThisProcess(d);
                    continue;
                }
                if (ProcessHandle.of(pid).isPresent()) {
                    continue; // alive (or a reused pid): never touch another live process's files
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(d, FILE_PREFIX + "*" + FILE_SUFFIX)) {
                    for (Path f : files) {
                        // DESTRUCTIVE: removes a spill file left by a DEAD connector process (its
                        // INSERT never completed or was already sent; the batch is redelivered from
                        // the offset either way). Bounded to <base>/<dead pid>/insert-*.values.
                        Files.deleteIfExists(f);
                    }
                }
                try {
                    Files.deleteIfExists(d);
                } catch (IOException ignored) {
                    // not empty (a file we do not own): leave it
                }
            }
        } catch (IOException e) {
            log.warn("Spill directory sweep of {} failed: {}", base, e.toString());
        }
    }

    private static void sweepOlderThanThisProcess(Path own) throws IOException {
        java.util.Optional<java.time.Instant> started = ProcessHandle.current().info().startInstant();
        if (!started.isPresent()) {
            return; // start time unknown: keep everything rather than guess
        }
        long startMillis = started.get().toEpochMilli();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(own, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path f : files) {
                if (Files.getLastModifiedTime(f).toMillis() < startMillis) {
                    // DESTRUCTIVE: removes a spill file written BEFORE this process started, i.e. by a
                    // previous incarnation with the same pid (containers); its batch is redelivered from
                    // the offset. Bounded to <base>/<own pid>/insert-*.values older than our start.
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the proxy
    private final PreparedStatement impl;
    private final PreparedStatement outer;
    private final String tableSpec;
    private final String template;
    private final int[] positions;
    private final int argCount;
    private final Map<String, Object> settings;
    private final Path directory;
    private final Sender sender;
    /** Per parameter: null (the driver encoded it) or a streamed large value. */
    private final LargeValue[] large;
    private Path file;
    private Writer out;
    private int rows;
    private long rowsSent;

    private SpillingInsertStatement(PreparedStatement impl, PreparedStatement outer, String tableSpec, String template,
                                    int[] positions, int argCount, Map<String, Object> settings, Path directory,
                                    Sender sender) {
        this.impl = impl;
        this.outer = outer;
        this.tableSpec = tableSpec;
        this.template = template;
        this.positions = positions;
        this.argCount = argCount;
        this.settings = settings;
        this.directory = directory;
        this.sender = sender;
        this.large = new LargeValue[argCount];
    }

    private enum Kind { QUOTED_STRING, HEX_TEXT, UNHEX_EXPRESSION }

    private static final class LargeValue {
        final Kind kind;
        final Object value;

        LargeValue(Kind kind, Object value) {
            this.kind = kind;
            this.value = value;
        }
    }

    /**
     * Offers a binary value that the caller would bind as its lower-case hex
     * text ({@code setString(index, base16().lowerCase().encode(bytes))}).
     * Returns true when {@code ps} is a spilling statement and the value is
     * large: the hex is then streamed from the array and the caller must not
     * bind it. Otherwise false and nothing happened.
     */
    public static boolean offerHexText(PreparedStatement ps, int index, byte[] bytes) {
        return offer(ps, index, bytes, Kind.HEX_TEXT);
    }

    /** As {@link #offerHexText} for a value the caller would bind with {@code setBytes} ({@code unhex('<HEX>')}). */
    public static boolean offerUnhex(PreparedStatement ps, int index, byte[] bytes) {
        return offer(ps, index, bytes, Kind.UNHEX_EXPRESSION);
    }

    private static boolean offer(PreparedStatement ps, int index, byte[] bytes, Kind kind) {
        if (bytes == null || 2L * bytes.length < LARGE_VALUE_CHARS || ps == null
                || !Proxy.isProxyClass(ps.getClass())) {
            return false;
        }
        InvocationHandler h = Proxy.getInvocationHandler(ps);
        if (!(h instanceof SpillingInsertStatement)) {
            return false;
        }
        SpillingInsertStatement s = (SpillingInsertStatement) h;
        if (index < 1 || index > s.argCount) {
            return false; // let the driver raise its own out-of-range error
        }
        s.large[index - 1] = new LargeValue(kind, bytes);
        return true;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int n = args == null ? 0 : args.length;
        if (name.startsWith("set") && n >= 2 && args[0] instanceof Integer) {
            int index = (Integer) args[0];
            if (index >= 1 && index <= argCount) {
                Object v = args[1];
                // setObject only in its two-argument form: with a target type the driver may
                // convert the value, and that conversion stays the driver's.
                if (("setString".equals(name) || ("setObject".equals(name) && n == 2))
                        && v instanceof String && ((String) v).length() >= LARGE_VALUE_CHARS) {
                    large[index - 1] = new LargeValue(Kind.QUOTED_STRING, v);
                    return null;
                }
                if ("setBytes".equals(name) && n == 2 && v instanceof byte[]
                        && 2L * ((byte[]) v).length >= LARGE_VALUE_CHARS) {
                    large[index - 1] = new LargeValue(Kind.UNHEX_EXPRESSION, v);
                    return null;
                }
                large[index - 1] = null;
            }
            return delegate(impl, method, args);
        }
        switch (name) {
            case "addBatch":
                if (n == 0) {
                    writeRow();
                    return null;
                }
                throw new SQLException("addBatch(String) is not supported on the spill path");
            case "clearParameters":
                Arrays.fill(large, null);
                return delegate(impl, method, args);
            case "executeBatch":
                return executeBatch();
            case "executeLargeBatch":
                int[] r = executeBatch();
                long[] l = new long[r.length];
                Arrays.fill(l, 1L);
                return l;
            case "clearBatch":
                discardFile();
                return null;
            case "close":
                try {
                    discardFile();
                } finally {
                    outer.close();
                }
                return null;
            case "isClosed":
                return outer.isClosed();
            case "unwrap":
            case "isWrapperFor":
                return delegate(outer, method, args);
            case "toString":
                return "SpillingInsertStatement[" + tableSpec + ", rows staged " + rows + "]";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                if (name.startsWith("execute") || name.equals("addBatch")) {
                    throw new SQLException(name + " is not supported on the spill path");
                }
                return delegate(impl, method, args);
        }
    }

    private static Object delegate(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** Writes one row: the driver's template with each parameter substituted at the driver's position. */
    private void writeRow() throws SQLException {
        String[] encoded;
        try {
            encoded = (String[]) DRIVER.values.get(impl);
        } catch (IllegalAccessException e) {
            throw new SQLException("Spill path cannot read the bound values", e);
        }
        try {
            if (out == null) {
                file = Files.createTempFile(directory, FILE_PREFIX, FILE_SUFFIX);
                out = new BufferedWriter(new OutputStreamWriter(
                        new BufferedOutputStream(Files.newOutputStream(file), 1 << 16), StandardCharsets.UTF_8), 1 << 16);
            }
            if (rows > 0) {
                out.write(',');
            }
            writeRowText(out, template, positions, argCount, encoded, large);
            rows++;
        } catch (IOException e) {
            throw new SQLException("Cannot write the spilled INSERT row to " + file + " (disk full or "
                    + "insert.spill.directory not writable?)", e);
        }
    }

    /** The row text, exactly as the driver's addBatch builds it (package-private for the tests). */
    static void writeRowText(Writer out, String template, int[] positions, int argCount, String[] encoded,
                             LargeValue[] large) throws IOException, SQLException {
        int cursor = 0;
        for (int i = 0; i < argCount; i++) {
            int p = positions[i];
            out.write(template, cursor, p - cursor);
            LargeValue lv = large == null ? null : large[i];
            if (lv != null) {
                writeLarge(out, lv);
            } else if (encoded[i] == null) {
                throw new SQLException("Parameter " + (i + 1) + " was not bound for the spilled row");
            } else {
                out.write(encoded[i]);
            }
            cursor = p + 1;
        }
        out.write(template, cursor, template.length() - cursor);
    }

    private static void writeLarge(Writer out, LargeValue lv) throws IOException {
        switch (lv.kind) {
            case QUOTED_STRING:
                writeQuotedEscaped(out, (String) lv.value);
                return;
            case HEX_TEXT:
                out.write('\'');
                writeHex(out, (byte[]) lv.value, HEX_LOWER);
                out.write('\'');
                return;
            case UNHEX_EXPRESSION:
                writeUnhex(out, (byte[]) lv.value);
                return;
            default:
                throw new IllegalStateException(lv.kind.name());
        }
    }

    /**
     * {@code '} + the text with every {@code \} doubled and every {@code '}
     * escaped as {@code \'} + {@code '}: the driver's
     * {@code SQLUtils.escapeSingleQuotes} inside {@code encodeObject(String)}.
     */
    static void writeQuotedEscaped(Writer out, String s) throws IOException {
        out.write('\'');
        int start = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'') {
                out.write(s, start, i - start);
                out.write('\\');
                out.write(c);
                start = i + 1;
            }
        }
        out.write(s, start, len - start);
        out.write('\'');
    }

    /** The driver's {@code JdbcUtils.convertToUnhexExpression}: {@code ''} for empty, else {@code unhex('<UPPER HEX>')}. */
    static void writeUnhex(Writer out, byte[] b) throws IOException {
        if (b.length == 0) {
            out.write("''");
            return;
        }
        out.write("unhex('");
        writeHex(out, b, HEX_UPPER);
        out.write("')");
    }

    static void writeHex(Writer out, byte[] b, char[] digits) throws IOException {
        char[] buf = new char[8192];
        int k = 0;
        for (byte x : b) {
            buf[k++] = digits[(x >> 4) & 0xF];
            buf[k++] = digits[x & 0xF];
            if (k == buf.length) {
                out.write(buf, 0, k);
                k = 0;
            }
        }
        out.write(buf, 0, k);
    }

    private int[] executeBatch() throws SQLException {
        if (rows == 0) {
            return new int[0];
        }
        int n = rows;
        try {
            out.close();
        } catch (IOException e) {
            discardFile();
            throw new SQLException("Cannot finish the spilled INSERT file " + file, e);
        }
        out = null;
        try {
            sender.send(tableSpec, file, settings);
        } finally {
            discardFile();
        }
        rowsSent += n;
        int[] result = new int[n];
        Arrays.fill(result, 1);
        return result;
    }

    private void discardFile() {
        rows = 0;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // closing an abandoned spill file; it is deleted next
            }
            out = null;
        }
        if (file != null) {
            try {
                // DESTRUCTIVE: deletes this statement's OWN spill file after it was sent (or abandoned on
                // failure/close); bounded to the one temp file this instance created.
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete spill file {}: {}", file, e.toString());
            }
            file = null;
        }
    }

    /** Rows this statement has sent (for tests and the progress line). */
    long rowsSent() {
        return rowsSent;
    }

    /** Whether {@code ps} is a spilling statement (the executor marks its progress line with it). */
    public static boolean isSpilling(PreparedStatement ps) {
        return ps != null && Proxy.isProxyClass(ps.getClass())
                && Proxy.getInvocationHandler(ps) instanceof SpillingInsertStatement;
    }

    static SpillingInsertStatement handlerOf(PreparedStatement ps) {
        return (SpillingInsertStatement) Proxy.getInvocationHandler(ps);
    }
}

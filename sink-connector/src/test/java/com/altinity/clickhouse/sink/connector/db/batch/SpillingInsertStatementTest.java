package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.client.api.sql.SQLUtils;
import com.clickhouse.jdbc.ConnectionImpl;
import com.clickhouse.jdbc.internal.JdbcUtils;
import com.google.common.io.BaseEncoding;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spill-to-disk INSERT path (spec 03.06 section 3.4).
 *
 * <p>The driver statements here are real {@code PreparedStatementImpl}s from the
 * bundled jdbc-v2 driver over a connection to a closed port: preparing and
 * binding never touch the network, only sending would, and the tests replace
 * the sender. Every parity test binds the same values into a second driver
 * statement, lets the DRIVER build the row ({@code addBatch}), and compares the
 * driver's own row text with the spilled file byte for byte.</p>
 */
public class SpillingInsertStatementTest {

    private static final String SQL = "INSERT INTO db.t(`id`,`s`,`n`,`d`,`b`,`f`,`ts`,`dt`,`x`,`flag`) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?)";
    private static Connection conn;

    @TempDir
    Path tmp;

    @BeforeAll
    static void connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "default");
        conn = new ConnectionImpl("jdbc:clickhouse://127.0.0.1:1/default", p);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        conn.close();
    }

    /** A spill statement whose sender captures the table spec, the body text and the settings. */
    private static final class Capture {
        final AtomicReference<String> spec = new AtomicReference<>();
        final List<String> bodies = new ArrayList<>();
        final AtomicReference<Map<String, Object>> settings = new AtomicReference<>();
        final List<Path> files = new ArrayList<>();
        SQLException failWith;

        SpillingInsertStatement.Sender sender() {
            return (spec, body, s) -> {
                this.spec.set(spec);
                this.files.add(body);
                try {
                    bodies.add(new String(Files.readAllBytes(body), StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new SQLException(e);
                }
                this.settings.set(s);
                if (failWith != null) {
                    throw failWith;
                }
            };
        }
    }

    private PreparedStatement spill(Capture c) throws SQLException {
        PreparedStatement impl = conn.prepareStatement(SQL);
        return SpillingInsertStatement.wrap(impl, impl, tmp, c.sender());
    }

    @SuppressWarnings("unchecked")
    private static String driverRows(PreparedStatement driver) throws Exception {
        Field f = driver.getClass().getDeclaredField("batchValues");
        f.setAccessible(true);
        List<StringBuilder> rows = (List<StringBuilder>) f.get(driver);
        StringBuilder sb = new StringBuilder();
        for (StringBuilder r : rows) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(r);
        }
        return sb.toString();
    }

    /** One row's binds, applied identically to the spill proxy and to a plain driver statement. */
    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private static final String NASTY = "a'b\\c\\'d''\n\té中😀 ? $1 \\\\ end'";

    private static Binder smallRow(long id) {
        return ps -> {
            ps.setLong(1, id);
            ps.setString(2, NASTY + id);
            ps.setNull(3, Types.VARCHAR);
            ps.setBigDecimal(4, new BigDecimal("-12345678901234567890.123456789"));
            ps.setBytes(5, new byte[]{0, 1, (byte) 0x7f, (byte) 0x80, (byte) 0xff});
            ps.setFloat(6, 1.5f);
            ps.setTimestamp(7, Timestamp.valueOf("2026-11-01 01:30:00.123456"));
            ps.setDate(8, Date.valueOf(LocalDate.of(1970, 1, 1)));
            ps.setObject(9, 42);
            ps.setBoolean(10, true);
        };
    }

    private static String bigString(int chars, long seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder(chars);
        String alphabet = "abcXYZ019'\\\"\né";
        for (int i = 0; i < chars; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static byte[] bigBytes(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private String spilledVersusDriver(Binder... rows) throws Exception {
        Capture c = new Capture();
        String expected;
        try (PreparedStatement driver = conn.prepareStatement(SQL); PreparedStatement ps = spill(c)) {
            for (Binder row : rows) {
                row.bind(driver);
                driver.addBatch();
                driver.clearParameters();
                row.bind(ps);
                ps.addBatch();
                ps.clearParameters();
            }
            expected = driverRows(driver);
            int[] result = ps.executeBatch();
            assertEquals(rows.length, result.length);
        }
        assertEquals(1, c.bodies.size());
        assertEquals(expected, c.bodies.get(0));
        return c.bodies.get(0);
    }

    @Test
    @DisplayName("the driver internals the spill path reads exist in the bundled driver (upgrade pin)")
    void driverIsSupported() {
        assertTrue(SpillingInsertStatement.driverSupported(),
                "jdbc-v2 changed PreparedStatementImpl's internals: the spill path would refuse itself");
    }

    @Test
    @DisplayName("small values: the spilled rows are the driver's own row text, byte for byte")
    void smallRowsMatchTheDriver() throws Exception {
        spilledVersusDriver(smallRow(1), smallRow(2), smallRow(3));
    }

    @Test
    @DisplayName("a large string is streamed with the driver's escaping")
    void largeStringMatchesTheDriver() throws Exception {
        String big = bigString(3 * SpillingInsertStatement.LARGE_VALUE_CHARS + 7, 1);
        String body = spilledVersusDriver(smallRow(1), ps -> {
            smallRow(2).bind(ps);
            ps.setString(2, big);
        }, ps -> {
            smallRow(3).bind(ps);
            ps.setObject(2, big + "tail");
        });
        assertTrue(body.contains("'" + SQLUtils.escapeSingleQuotes(big) + "'"));
    }

    @Test
    @DisplayName("a large setBytes value is streamed as the driver's unhex expression")
    void largeBytesMatchTheDriver() throws Exception {
        byte[] big = bigBytes(SpillingInsertStatement.LARGE_VALUE_CHARS, 2);
        String body = spilledVersusDriver(ps -> {
            smallRow(1).bind(ps);
            ps.setBytes(5, big);
        });
        assertTrue(body.contains(JdbcUtils.convertToUnhexExpression(big)));
    }

    @Test
    @DisplayName("a large binary offered as hex text is streamed as the lower-case hex the binder would set")
    void offeredHexTextMatchesTheDriver() throws Exception {
        byte[] big = bigBytes(SpillingInsertStatement.LARGE_VALUE_CHARS, 3);
        byte[] bigRaw = bigBytes(SpillingInsertStatement.LARGE_VALUE_CHARS, 4);
        Capture c = new Capture();
        String expected;
        try (PreparedStatement driver = conn.prepareStatement(SQL); PreparedStatement ps = spill(c)) {
            smallRow(1).bind(driver);
            driver.setString(2, BaseEncoding.base16().lowerCase().encode(big));
            driver.setBytes(5, bigRaw);
            driver.addBatch();
            expected = driverRows(driver);
            smallRow(1).bind(ps);
            assertTrue(SpillingInsertStatement.offerHexText(ps, 2, big));
            assertTrue(SpillingInsertStatement.offerUnhex(ps, 5, bigRaw));
            ps.addBatch();
            ps.executeBatch();
        }
        assertEquals(expected, c.bodies.get(0));
    }

    @Test
    @DisplayName("offer* declines small values and plain driver statements, so the caller binds as before")
    void offerDeclines() throws Exception {
        try (PreparedStatement driver = conn.prepareStatement(SQL); PreparedStatement ps = spill(new Capture())) {
            assertFalse(SpillingInsertStatement.offerHexText(driver, 2, bigBytes(1 << 20, 5)));
            assertFalse(SpillingInsertStatement.offerHexText(ps, 2, new byte[16]));
            assertFalse(SpillingInsertStatement.offerUnhex(ps, 5, null));
            assertFalse(SpillingInsertStatement.offerUnhex(ps, 99, bigBytes(1 << 20, 6)));
        }
    }

    @Test
    @DisplayName("a smaller value bound after a large one in the same slot replaces it")
    void rebindReplacesALargeValue() throws Exception {
        String big = bigString(SpillingInsertStatement.LARGE_VALUE_CHARS, 7);
        Capture c = new Capture();
        try (PreparedStatement ps = spill(c)) {
            smallRow(1).bind(ps);
            ps.setString(2, big);
            ps.setString(2, "small");
            ps.addBatch();
            ps.executeBatch();
        }
        assertFalse(c.bodies.get(0).contains(big.substring(0, 100)));
        assertTrue(c.bodies.get(0).contains("'small'"));
    }

    @Test
    @DisplayName("the INSERT is sent to the statement's table and columns with the statement's settings, then the file is gone")
    void sendsTableColumnsAndSettings() throws Exception {
        Capture c = new Capture();
        try (PreparedStatement ps = spill(c)) {
            smallRow(1).bind(ps);
            ps.addBatch();
            assertArrayEquals(new int[]{1}, ps.executeBatch());
            assertEquals(0, ps.executeBatch().length, "nothing staged: nothing is sent");
        }
        assertEquals("db.t(`id`,`s`,`n`,`d`,`b`,`f`,`ts`,`dt`,`x`,`flag`)", c.spec.get());
        assertEquals("0", String.valueOf(c.settings.get().get("clickhouse_setting_async_insert")));
        assertFalse(Files.exists(c.files.get(0)), "the spill file is deleted after the send");
        try (Stream<Path> left = Files.list(tmp)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    @DisplayName("a failed send fails the batch with the server's text and leaves no file behind")
    void failedSendPropagates() throws Exception {
        Capture c = new Capture();
        c.failWith = new SQLException("Code: 252. DB::Exception: Too many parts");
        try (PreparedStatement ps = spill(c)) {
            smallRow(1).bind(ps);
            ps.addBatch();
            SQLException e = assertThrows(SQLException.class, ps::executeBatch);
            assertTrue(e.getMessage().contains("Code: 252"));
            assertEquals(0, ps.executeBatch().length, "the failed rows are not resent by this statement");
        }
        try (Stream<Path> left = Files.list(tmp)) {
            assertEquals(0, left.count());
        }
    }

    @Test
    @DisplayName("a parameter left unbound fails the row loudly instead of writing a stale value")
    void unboundParameterFails() throws Exception {
        try (PreparedStatement ps = spill(new Capture())) {
            smallRow(1).bind(ps);
            ps.addBatch();
            ps.clearParameters();
            ps.setLong(1, 2L);
            assertThrows(SQLException.class, ps::addBatch);
        }
    }

    @Test
    @DisplayName("execute paths that would bypass the staged rows are refused")
    void otherExecutePathsRefused() throws Exception {
        try (PreparedStatement ps = spill(new Capture())) {
            assertThrows(SQLException.class, ps::executeUpdate);
            assertThrows(SQLException.class, () -> ps.addBatch("INSERT INTO x VALUES (1)"));
        }
    }

    @Test
    @DisplayName("maybeWrap: off when negative, below the threshold, or for a non-driver statement; on at 0")
    void wrapDecision() throws Exception {
        List<ClickHouseStruct> chunk = Collections.singletonList(recordWithBlob(1000));
        try (PreparedStatement real = conn.prepareStatement(SQL)) {
            assertSame(real, SpillingInsertStatement.maybeWrap(real, conn, chunk, -1, tmp.toString()));
            assertSame(real, SpillingInsertStatement.maybeWrap(real, conn, chunk, 1L << 20, tmp.toString()));
            PreparedStatement wrapped = SpillingInsertStatement.maybeWrap(real, conn, chunk, 0, tmp.toString());
            assertNotSame(real, wrapped);
            assertTrue(SpillingInsertStatement.maybeWrap(real, conn, chunk, 2000, tmp.toString()) != real,
                    "a 1000-byte blob renders as 2000+ hex characters: at the threshold");
        }
        RecordingJdbc rec = new RecordingJdbc();
        PreparedStatement recorded = rec.connection().prepareStatement(SQL);
        assertSame(recorded, SpillingInsertStatement.maybeWrap(recorded, rec.connection(), chunk, 0, tmp.toString()),
                "not the V2 driver: the driver path is kept");
    }

    private static ClickHouseStruct recordWithBlob(int bytes) {
        Schema schema = SchemaBuilder.struct().field("id", Schema.INT64_SCHEMA).field("b", Schema.BYTES_SCHEMA)
                .field("s", Schema.OPTIONAL_STRING_SCHEMA).build();
        Struct after = new Struct(schema).put("id", 1L).put("b", new byte[bytes]).put("s", "hello");
        ClickHouseStruct r = new ClickHouseStruct();
        r.setAfterStruct(after);
        return r;
    }

    @Test
    @DisplayName("the rendered size bound counts strings at their length and binaries at twice theirs, both images")
    void renderedSizeBound() {
        ClickHouseStruct r = recordWithBlob(1000);
        long bound = SpillingInsertStatement.renderedSizeBound(r);
        assertTrue(bound >= 2000 + 5 && bound < 2000 + 5 + 100, "bound " + bound);
        r.setBeforeStruct(r.getAfterStruct());
        assertEquals(2 * bound, SpillingInsertStatement.renderedSizeBound(r));
        assertEquals(0L, SpillingInsertStatement.renderedSizeBound((ClickHouseStruct) null));
    }

    @Test
    @DisplayName("spill files left by a dead process are removed; a live process's and foreign files are kept")
    void deadProcessSweep() throws Exception {
        Path base = tmp.resolve("base");
        Path dead = Files.createDirectories(base.resolve("2147483646"));
        Path deadFile = Files.createFile(dead.resolve(SpillingInsertStatement.FILE_PREFIX + "1"
                + SpillingInsertStatement.FILE_SUFFIX));
        Path foreign = Files.createFile(dead.resolve("keep.txt"));
        Path live = Files.createDirectories(base.resolve("1"));
        Path liveFile = Files.createFile(live.resolve(SpillingInsertStatement.FILE_PREFIX + "2"
                + SpillingInsertStatement.FILE_SUFFIX));
        // A previous incarnation with OUR pid (a container's pid 1): only files older than this process go.
        Path ownDir = Files.createDirectories(base.resolve(Long.toString(ProcessHandle.current().pid())));
        Path stale = Files.createFile(ownDir.resolve(SpillingInsertStatement.FILE_PREFIX + "3"
                + SpillingInsertStatement.FILE_SUFFIX));
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.fromMillis(0L));
        Path fresh = Files.createFile(ownDir.resolve(SpillingInsertStatement.FILE_PREFIX + "4"
                + SpillingInsertStatement.FILE_SUFFIX));
        Path own = SpillingInsertStatement.processDirectory(base.toString());
        assertEquals(ownDir, own);
        assertTrue(Files.isDirectory(own));
        assertFalse(Files.exists(deadFile));
        assertTrue(Files.exists(foreign));
        assertTrue(Files.exists(liveFile), "pid 1 is alive: its files are never touched");
        assertFalse(Files.exists(stale), "written before this process started: a previous incarnation's");
        assertTrue(Files.exists(fresh), "written after this process started: possibly in flight, kept");
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.Metrics;
import com.github.luben.zstd.Zstd;
import com.github.shyiko.mysql.binlog.event.Event;
import com.github.shyiko.mysql.binlog.event.EventHeaderV4;
import com.github.shyiko.mysql.binlog.event.EventType;
import com.github.shyiko.mysql.binlog.event.TransactionPayloadEventData;
import com.altinity.clickhouse.debezium.embedded.cdc.payload.StreamingTransactionPayloadEventData;
import com.github.shyiko.mysql.binlog.event.XidEventData;
import com.github.shyiko.mysql.binlog.io.ByteArrayInputStream;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.binlog.event.TransactionPayloadDeserializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verifies at start that the connector can read a MySQL source whose
 * {@code binlog_transaction_compression} is {@code ON} (spec 01.08).
 *
 * <p><b>What the source does.</b> From MySQL 8.0.20, {@code
 * binlog_transaction_compression=ON} writes every transaction to the binlog as
 * ONE {@code Transaction_payload} event whose body is the zstd-compressed
 * stream of the transaction's ordinary events (TABLE_MAP, ROWS, XID). The
 * binlog client the connector embeds ({@code mysql-binlog-connector-java},
 * io.debezium fork) hands the event to the decoder Debezium registers for it,
 * which in this connector is its own streaming {@link
 * TransactionPayloadDeserializer}: it inflates the body with zstd-jni one inner
 * event at a time while the events are dispatched, with no limit on the
 * uncompressed size. Nothing else in the pipeline sees a difference --
 * PROVIDED the decoder works on this platform. zstd-jni is a native library;
 * on a platform it has no binary for, or with the class missing from a
 * repackaged jar, every compressed transaction fails to deserialize and the
 * engine cannot make progress past the first one.</p>
 *
 * <p><b>Why refuse vs. warn.</b> Refusal is reserved for the one combination
 * that is certain to break: the source is ON and the decoder self-test fails.
 * Everything short of that is a warning, so that the probe itself never takes
 * down a healthy pipeline: a source that cannot be asked (unreachable,
 * permission denied, or older than 8.0.20 where the variable does not exist)
 * is UNKNOWN and logged at WARN; a source that is OFF with a broken decoder is
 * a WARN banner, because the variable is dynamic and a later {@code SET GLOBAL}
 * -- or even a session-level {@code SET} -- would start producing payloads. In
 * {@code require} mode the operator has said the pipeline depends on
 * compression, and anything other than ON-and-decodable refuses -- including
 * a source older than 8.0.34, which can write a compressed transaction no
 * reader can receive (see {@link #OVERSIZED_PAYLOAD_HAZARD}; in {@code auto}
 * mode that is a WARN banner).</p>
 *
 * <p>The check is read-only against the source, on the same footing as the
 * keyless-table and row-image checks: one {@code SELECT} on a connection
 * opened read-only, passed through {@link KeylessTablePreflight#assertReadOnlySql}
 * first, never a {@code SET}.</p>
 *
 * <p>MySQL only. MariaDB's {@code log_bin_compress} is a different, unsupported
 * event format ({@code Compressed} row events, not a zstd
 * {@code Transaction_payload}) and PostgreSQL has no binlog; for anything but a
 * MySQL connector this class returns without opening a connection, running the
 * self-test or logging.</p>
 */
public final class BinlogTransactionCompressionPreflight {

    private static final Logger log = LogManager.getLogger(BinlogTransactionCompressionPreflight.class);

    /** The mode property: {@code auto} (default), {@code require} or {@code skip}. */
    static final String PROPERTY = "binlog.transaction.compression.check";

    /** Single source of the default, shared with the config definition. */
    static final String DEFAULT_MODE = ClickHouseSinkConnectorConfig.DEFAULT_BINLOG_TRANSACTION_COMPRESSION_CHECK;

    static final String MODE_AUTO = "auto";
    static final String MODE_REQUIRE = "require";
    static final String MODE_SKIP = "skip";

    /**
     * The one statement this class issues on the happy path. {@code SELECT
     * @@GLOBAL.x} reads the same values as {@code SHOW GLOBAL VARIABLES} and
     * passes {@link KeylessTablePreflight#assertReadOnlySql}'s SELECT-only
     * allowlist.
     */
    static final String QUERY = "SELECT @@GLOBAL.binlog_transaction_compression, "
            + "@@GLOBAL.binlog_transaction_compression_level_zstd, @@GLOBAL.version";

    /**
     * Issued only when {@link #QUERY} fails, to name the server version in the
     * warning: before 8.0.20 the variable does not exist and the statement is
     * rejected with "Unknown system variable".
     */
    static final String VERSION_QUERY = "SELECT @@GLOBAL.version";

    private static final String BANNER_RULE =
            "========================================================================";

    /** The fix quoted in every message that asks the operator to turn compression on. */
    private static final String ENABLE_HINT =
            "    SET PERSIST binlog_transaction_compression = ON;\n"
                    + "    # or in my.cnf:\n"
                    + "    binlog_transaction_compression = ON";

    /** XID event type number in the binlog v4 format. */
    private static final int XID_EVENT_TYPE = 16;
    /** Binlog v4 event header length. */
    private static final int V4_HEADER_LENGTH = 19;
    /** The XID event body: one little-endian u64. */
    private static final int XID_BODY_LENGTH = 8;

    /** Transaction_payload TLV field types (mysql/libbinlogevents/include/control_events.h). */
    private static final int OTW_PAYLOAD_HEADER_END_MARK = 0;
    private static final int OTW_PAYLOAD_SIZE_FIELD = 1;
    private static final int OTW_PAYLOAD_COMPRESSION_TYPE_FIELD = 2;
    private static final int OTW_PAYLOAD_UNCOMPRESSED_SIZE_FIELD = 3;
    private static final int COMPRESSION_TYPE_ZSTD = 0;

    /** The XID the self-test encodes and expects back. */
    static final long SELF_TEST_XID = 0x0102030405060708L;

    /** The class Debezium instantiates for TRANSACTION_PAYLOAD events. */
    static final String PAYLOAD_DECODER_CLASS = "io.debezium.connector.binlog.event.TransactionPayloadDeserializer";

    /** What the source reported. */
    public enum SourceState {
        ON, OFF, UNKNOWN, SKIPPED
    }

    /** The result of one check, for the caller and for the tests. Immutable. */
    public static final class Outcome {
        public final SourceState sourceState;
        /** {@code binlog_transaction_compression_level_zstd}, or null when not read. */
        public final Integer zstdLevel;
        /** The source's {@code @@GLOBAL.version}, or null when not read. */
        public final String serverVersion;
        /** Whether the decoder self-test passed; false when it did not run. */
        public final boolean decoderOk;
        /** The normalized mode the check ran in. */
        public final String mode;

        Outcome(SourceState sourceState, Integer zstdLevel, String serverVersion, boolean decoderOk, String mode) {
            this.sourceState = sourceState;
            this.zstdLevel = zstdLevel;
            this.serverVersion = serverVersion;
            this.decoderOk = decoderOk;
            this.mode = mode;
        }

        @Override
        public String toString() {
            return "Outcome{sourceState=" + sourceState + ", zstdLevel=" + zstdLevel
                    + ", serverVersion=" + serverVersion + ", decoderOk=" + decoderOk
                    + ", mode=" + mode + "}";
        }
    }

    /** What one probe of the source found; UNKNOWN when it could not be read. */
    private static final class Probe {
        final SourceState state;
        final Integer level;
        final String version;

        Probe(SourceState state, Integer level, String version) {
            this.state = state;
            this.level = level;
            this.version = version;
        }

        static Probe unknown(String version) {
            return new Probe(SourceState.UNKNOWN, null, version);
        }
    }

    private BinlogTransactionCompressionPreflight() {
    }

    /**
     * Runs the check against the configured MySQL source.
     *
     * <p>Only MySQL is checked; other connectors pass through untouched. A
     * connection or permission failure is logged at WARN and treated as
     * source state UNKNOWN, which refuses only in {@code require} mode or when
     * the decoder self-test also failed.</p>
     *
     * @param props the connector properties.
     * @return what was found.
     * @throws IllegalStateException when the mode is unknown, or the decision
     *                               table says the connector must not start.
     */
    public static Outcome check(Properties props) {
        return run(props, () -> probeSource(props));
    }

    /**
     * The check against an open connection to the source.
     *
     * @param props the connector properties.
     * @param conn  an open connection to the source; it is set read-only.
     * @return what was found.
     * @throws IllegalStateException when the mode is unknown, or the decision
     *                               table says the connector must not start.
     */
    static Outcome check(Properties props, Connection conn) {
        return run(props, () -> probe(conn));
    }

    private static Outcome run(Properties props, Supplier<Probe> source) {
        String connector = props.getProperty("connector.class", "");
        if (!connector.toLowerCase(Locale.ROOT).contains("mysql")) {
            // MariaDB's log_bin_compress is not this format; PostgreSQL has no binlog.
            return new Outcome(SourceState.SKIPPED, null, null, false,
                    props.getProperty(PROPERTY, DEFAULT_MODE).trim().toLowerCase(Locale.ROOT));
        }
        String mode = mode(props);
        if (MODE_SKIP.equals(mode)) {
            log.info("{}=skip: binlog_transaction_compression not read from the source and the "
                    + "Transaction_payload decoder not self-tested. A source that is ON with a "
                    + "decoder that does not work fails on the first compressed transaction.", PROPERTY);
            return new Outcome(SourceState.SKIPPED, null, null, false, mode);
        }
        // Self-test first: its result decides how a source answer is handled.
        boolean decoderOk = decoderSelfTest();
        Probe probe = source.get();
        Metrics.updateBinlogTransactionCompression(
                metricValue(probe.state), probe.level == null ? -1 : probe.level, decoderOk ? 1 : 0);
        return decide(mode, decoderOk, probe);
    }

    /**
     * The normalized mode, or a refusal naming the allowed values: a typo in a
     * safety setting must not silently select the default.
     */
    static String mode(Properties props) {
        String raw = props.getProperty(PROPERTY, DEFAULT_MODE);
        String mode = raw == null ? DEFAULT_MODE : raw.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty()) {
            return DEFAULT_MODE;
        }
        switch (mode) {
            case MODE_AUTO:
            case MODE_REQUIRE:
            case MODE_SKIP:
                return mode;
            default:
                String message = String.format("%s=%s is not a valid value; allowed values are %s, %s, %s "
                                + "(case-insensitive). Fix the configuration and restart.",
                        PROPERTY, raw.trim(), MODE_AUTO, MODE_REQUIRE, MODE_SKIP);
                log.error("\n{}\n  !!  REFUSING TO START: {}\n{}", BANNER_RULE, message, BANNER_RULE);
                throw new IllegalStateException("Refusing to start: " + message);
        }
    }

    /** Opens the read-only connection and probes; never throws. */
    private static Probe probeSource(Properties props) {
        String host = props.getProperty("database.hostname");
        String port = props.getProperty("database.port", "3306");
        String user = props.getProperty("database.user");
        String password = props.getProperty("database.password");
        if (host == null || user == null) {
            log.warn("binlog_transaction_compression could not be read: no MySQL host/user in the configuration.");
            return Probe.unknown(null);
        }
        String url = KeylessTablePreflight.jdbcUrl(host, port, props);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            return probe(conn);
        } catch (Exception e) {
            // Never block a healthy pipeline on this check itself.
            log.warn("binlog_transaction_compression could not be read: the check could not connect to the "
                    + "MySQL source ({}).", e.toString());
            return Probe.unknown(null);
        }
    }

    /**
     * Reads the three variables on a read-only connection. A failure of the
     * statement -- typically a server older than 8.0.20, where the variable does
     * not exist -- is UNKNOWN, with the server version in the warning when it
     * can still be obtained.
     */
    private static Probe probe(Connection conn) {
        try {
            conn.setReadOnly(true);
        } catch (Exception e) {
            log.warn("Could not set the binlog_transaction_compression check's connection read-only ({}). "
                    + "The check still issues only a SELECT, enforced client-side.", e.toString());
        }
        String compression;
        Integer level;
        String version;
        try {
            KeylessTablePreflight.assertReadOnlySql(QUERY);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(QUERY)) {
                if (!rs.next()) {
                    log.warn("binlog_transaction_compression could not be read: the source returned no row for {}.",
                            QUERY);
                    return Probe.unknown(null);
                }
                compression = rs.getString(1);
                level = rs.getInt(2);
                version = rs.getString(3);
            }
        } catch (Exception e) {
            String version2 = serverVersion(conn);
            log.warn("binlog_transaction_compression could not be read from the MySQL source{} ({}). "
                            + "The variable exists from MySQL 8.0.20; an older server never writes "
                            + "Transaction_payload events.",
                    version2 == null ? "" : " (version " + version2 + ")", e.toString());
            return Probe.unknown(version2);
        }
        SourceState state = parseState(compression);
        if (state == SourceState.UNKNOWN) {
            log.warn("binlog_transaction_compression={} on MySQL {} is neither ON nor OFF; treating it as unknown.",
                    compression, version);
            return Probe.unknown(version);
        }
        return new Probe(state, level, version);
    }

    /** MySQL reports the boolean as 1/0 through JDBC, ON/OFF as text; accept both. */
    static SourceState parseState(String value) {
        if (value == null) {
            return SourceState.UNKNOWN;
        }
        switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "1":
            case "ON":
            case "TRUE":
                return SourceState.ON;
            case "0":
            case "OFF":
            case "FALSE":
                return SourceState.OFF;
            default:
                return SourceState.UNKNOWN;
        }
    }

    /** {@code @@GLOBAL.version} for the failure-branch warning, or null. */
    private static String serverVersion(Connection conn) {
        try {
            KeylessTablePreflight.assertReadOnlySql(VERSION_QUERY);
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(VERSION_QUERY)) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static int metricValue(SourceState state) {
        switch (state) {
            case ON:
                return 1;
            case OFF:
                return 0;
            default:
                return -1;
        }
    }

    /** The decision table (spec 01.08). */
    private static Outcome decide(String mode, boolean decoderOk, Probe probe) {
        boolean require = MODE_REQUIRE.equals(mode);
        Outcome outcome = new Outcome(probe.state, probe.level, probe.version, decoderOk, mode);
        String version = probe.version == null ? "(version unknown)" : probe.version;
        switch (probe.state) {
            case ON:
                if (decoderOk && writesOversizedPayloads(probe.version)) {
                    if (require) {
                        refuse(String.format(
                                "%s=%s and binlog_transaction_compression=ON, but the source is MySQL %s. %s "
                                        + "Upgrade the source to 8.0.34 or later before relying on compression, or "
                                        + "turn it off:\n    SET PERSIST binlog_transaction_compression = OFF;\n"
                                        + "To start anyway, set %s=%s.",
                                PROPERTY, MODE_REQUIRE, version, OVERSIZED_PAYLOAD_HAZARD, PROPERTY, MODE_AUTO));
                        return outcome; // unreachable: refuse throws
                    }
                    log.warn("\n{}\n  !!  binlog_transaction_compression=ON on MySQL {}, a server older than 8.0.34  "
                                    + "!!\n{}\n  {} The connector decodes every compressed transaction the source can "
                                    + "send, of any uncompressed size; this limit is the source's own. Upgrade the "
                                    + "source to 8.0.34 or later, or turn compression off "
                                    + "(SET PERSIST binlog_transaction_compression = OFF).\n{}",
                            BANNER_RULE, version, BANNER_RULE, OVERSIZED_PAYLOAD_HAZARD, BANNER_RULE);
                    return outcome;
                }
                if (decoderOk) {
                    log.info("binlog_transaction_compression=ON (zstd level {}) on MySQL {}: compressed "
                                    + "transactions are decoded transparently (Transaction_payload decoder "
                                    + "self-test passed).", probe.level, version);
                    return outcome;
                }
                refuse(String.format(
                        "binlog_transaction_compression=ON (zstd level %s) on MySQL %s, but this build's "
                                + "Transaction_payload decoder self-test FAILED (see the ERROR above). The source "
                                + "sends every transaction as one zstd-compressed Transaction_payload event that "
                                + "this build cannot decode, so the connector could not read a single committed "
                                + "transaction. Either turn compression off on the source and restart:\n"
                                + "    SET PERSIST binlog_transaction_compression = OFF;\n"
                                + "    # or in my.cnf: binlog_transaction_compression = OFF\n"
                                + "or fix the platform (zstd-jni needs a native library for this OS/arch; the "
                                + "connector's jar must carry com.github.luben.zstd and this connector's streaming "
                                + "io.debezium.connector.binlog.event.TransactionPayloadDeserializer ahead of "
                                + "Debezium's). To start anyway, set %s=%s.",
                        probe.level, version, PROPERTY, MODE_SKIP));
                return outcome; // unreachable: refuse throws
            case OFF:
                if (require) {
                    refuse(String.format(
                            "%s=%s but binlog_transaction_compression=OFF on MySQL %s. Turn it on and restart:\n"
                                    + "%s\n(it is a dynamic variable; transactions already in the binlog stay "
                                    + "uncompressed and are read as before)%s. To start without it, set %s=%s.",
                            PROPERTY, MODE_REQUIRE, version, ENABLE_HINT,
                            decoderOk ? "" : ". The Transaction_payload decoder self-test also FAILED (see "
                                    + "the ERROR above); fix the platform before enabling compression",
                            PROPERTY, MODE_AUTO));
                    return outcome; // unreachable: refuse throws
                }
                if (decoderOk && writesOversizedPayloads(probe.version)) {
                    log.warn("binlog_transaction_compression=OFF on MySQL {}: the source writes uncompressed "
                            + "transactions and the Transaction_payload decoder self-test passed. Do not turn "
                            + "compression on for this server: {}", version, OVERSIZED_PAYLOAD_HAZARD);
                    return outcome;
                }
                if (decoderOk) {
                    log.info("binlog_transaction_compression=OFF on MySQL {}: the source writes uncompressed "
                            + "transactions. The Transaction_payload decoder self-test passed, so the "
                            + "connector is ready should the source enable it (a dynamic variable; a "
                            + "session-level SET also produces compressed payloads).", version);
                    return outcome;
                }
                log.warn("\n{}\n  !!  binlog_transaction_compression=OFF on MySQL {}, but the Transaction_payload "
                                + "decoder self-test FAILED  !!\n{}\n  Replication works while the source stays OFF. "
                                + "The variable is dynamic: a SET GLOBAL, a SET PERSIST or even a session-level SET "
                                + "on the source would start writing zstd-compressed Transaction_payload events this "
                                + "build cannot decode, and the connector would stop at the first one. Fix the "
                                + "platform (zstd-jni native library for this OS/arch, this connector's streaming "
                                + "TransactionPayloadDeserializer ahead of Debezium's on the classpath) or keep compression "
                                + "off on the source. See the ERROR above for the cause.\n{}",
                        BANNER_RULE, version, BANNER_RULE, BANNER_RULE);
                return outcome;
            default:
                if (require) {
                    refuse(String.format(
                            "%s=%s but binlog_transaction_compression could not be read from the MySQL source%s "
                                    + "(see the WARN above). The variable exists from MySQL 8.0.20. Make the source "
                                    + "reachable with a user that may read global variables, confirm it is ON:\n"
                                    + "%s\nand restart%s. To start without verifying it, set %s=%s.",
                            PROPERTY, MODE_REQUIRE, probe.version == null ? "" : " (version " + probe.version + ")",
                            ENABLE_HINT,
                            decoderOk ? "" : ". The Transaction_payload decoder self-test also FAILED (see the "
                                    + "ERROR above)",
                            PROPERTY, MODE_AUTO));
                    return outcome; // unreachable: refuse throws
                }
                if (!decoderOk) {
                    refuse(String.format(
                            "binlog_transaction_compression could not be read from the MySQL source%s (see the "
                                    + "WARN above) AND this build's Transaction_payload decoder self-test FAILED "
                                    + "(see the ERROR above). If the source is ON (MySQL 8.0.20+), every "
                                    + "transaction arrives as a zstd-compressed Transaction_payload event this "
                                    + "build cannot decode. Make the source readable so the check can decide, "
                                    + "turn compression off on the source, or fix the platform (zstd-jni native "
                                    + "library for this OS/arch). To start anyway, set %s=%s.",
                            probe.version == null ? "" : " (version " + probe.version + ")", PROPERTY, MODE_SKIP));
                    return outcome; // unreachable: refuse throws
                }
                log.warn("binlog_transaction_compression could not be read from the MySQL source{}. Continuing: "
                                + "the Transaction_payload decoder self-test passed, so compressed transactions "
                                + "are decoded if the source sends them. Set {}={} to have this refuse instead.",
                        probe.version == null ? "" : " (version " + probe.version + ")", PROPERTY, MODE_REQUIRE);
                return outcome;
        }
    }

    /**
     * The hazard of a source older than 8.0.34 (spec 01.08 §3.1 item 4). Until MySQL 8.0.34 (Bug
     * #33588473) a server writes a transaction whose COMPRESSED payload exceeds 1 GiB as one
     * Transaction_payload anyway; the dump thread cannot send an event above the 1 GiB replication
     * packet limit, so no reader -- this connector or a MySQL replica -- can receive it. From 8.0.34 the
     * server writes such a transaction uncompressed instead.
     */
    static final String OVERSIZED_PAYLOAD_HAZARD = "Before MySQL 8.0.34 (Bug #33588473) a transaction whose "
            + "COMPRESSED payload exceeds 1 GiB is still written as one Transaction_payload event larger than the "
            + "1 GiB replication packet limit: no reader can receive it (the source's dump thread fails with error "
            + "1236 'log event entry exceeded max_allowed_packet'; SHOW BINLOG EVENTS fails with 'Event too big'), "
            + "so replication stops at it for this connector and for every MySQL replica. From 8.0.34 the server "
            + "writes such a transaction uncompressed instead.";

    /** major.minor.patch at the start of {@code @@version} ("8.0.41-32", "8.4.3", "9.1.0"). */
    private static final Pattern SERVER_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * True for a MySQL server that can write a Transaction_payload larger than the 1 GiB replication
     * packet limit: 8.0.20 (where compression exists) up to 8.0.33. 8.0.34+, 8.1+ and later series fall
     * back to an uncompressed transaction. An unparseable or missing version is not classified (false):
     * the probe never refuses on a guess.
     */
    static boolean writesOversizedPayloads(String version) {
        if (version == null) {
            return false;
        }
        Matcher m = SERVER_VERSION.matcher(version.trim());
        if (!m.find()) {
            return false;
        }
        try {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            int patch = Integer.parseInt(m.group(3));
            return major == 8 && minor == 0 && patch < 34;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void refuse(String message) {
        log.error("\n{}\n  !!  REFUSING TO START: {}\n{}", BANNER_RULE, message, BANNER_RULE);
        throw new IllegalStateException("Refusing to start: " + message);
    }

    // ------------------------------------------------------------------
    // Decoder self-test
    // ------------------------------------------------------------------

    /**
     * Decodes a synthetic Transaction_payload -- one zstd-compressed XID event
     * -- through the real binlog-client deserializer, exercising zstd-jni's
     * native library and the deserializer class on this platform.
     *
     * @return true when the payload decodes to exactly one XID event carrying
     *         {@link #SELF_TEST_XID}; false on any exception, error or mismatch
     *         (logged at ERROR with the cause).
     */
    static boolean decoderSelfTest() {
        byte[] body;
        try {
            body = syntheticPayload(SELF_TEST_XID);
        } catch (Throwable t) {
            // A missing zstd-jni native library surfaces here as an Error, not an Exception.
            log.error("Transaction_payload decoder self-test FAILED building the synthetic payload: {}",
                    t.toString(), t);
            return false;
        }
        return decoderSelfTest(body, SELF_TEST_XID);
    }

    /**
     * The decoding half of the self-test, on caller-supplied bytes so a test
     * can hand it a corrupted payload.
     *
     * @param body        a Transaction_payload event body (TLV header + payload).
     * @param expectedXid the XID the single inner event must carry.
     * @return true when the body decodes to exactly one XID event with that xid.
     */
    static boolean decoderSelfTest(byte[] body, long expectedXid) {
        try {
            String streaming = streamingDecoderMarker();
            if (!TransactionPayloadDeserializer.STREAMING_DECODER.equals(streaming)) {
                log.error("Transaction_payload decoder self-test FAILED: the TRANSACTION_PAYLOAD decoder on the "
                                + "runtime classpath ({}) is not this connector's streaming decoder (marker {}, "
                                + "expected {}). The stock Debezium decoder cannot decode a transaction whose "
                                + "uncompressed payload exceeds 2 GiB and holds every transaction's whole "
                                + "uncompressed payload in heap (spec 01.08 section 3.2). The connector jar must "
                                + "carry its own io.debezium.connector.binlog.event.TransactionPayloadDeserializer "
                                + "ahead of Debezium's.",
                        decoderLocation(), streaming, TransactionPayloadDeserializer.STREAMING_DECODER);
                return false;
            }
            TransactionPayloadEventData data = decodePayload(body);
            if (!(data instanceof StreamingTransactionPayloadEventData)) {
                log.error("Transaction_payload decoder self-test FAILED: the decoder returned {}, not a streamed "
                        + "payload (spec 01.08 section 3.2).", data == null ? "null" : data.getClass().getName());
                return false;
            }
            // Iterate, never index or size: the inner events are streamed (spec 01.08 section 3.2).
            List<Event> events = new ArrayList<>(2);
            for (Event inner : data.getUncompressedEvents()) {
                events.add(inner);
                if (events.size() > 1) {
                    break;
                }
            }
            if (events.size() != 1) {
                log.error("Transaction_payload decoder self-test FAILED: expected 1 inner event, decoded {} ({}).",
                        events.size() > 1 ? "more than 1" : "0", data);
                return false;
            }
            Event event = events.get(0);
            EventHeaderV4 header = event.getHeader();
            if (header.getEventType() != EventType.XID || !(event.getData() instanceof XidEventData)) {
                log.error("Transaction_payload decoder self-test FAILED: expected an XID event, decoded {} ({}).",
                        header.getEventType(), event);
                return false;
            }
            long xid = ((XidEventData) event.getData()).getXid();
            if (xid != expectedXid) {
                log.error("Transaction_payload decoder self-test FAILED: expected xid {}, decoded {}.",
                        expectedXid, xid);
                return false;
            }
            log.info("Transaction_payload decoder self-test passed: zstd-jni + streaming Transaction_payload "
                            + "decoder {} available, no uncompressed-size limit (decoded 1 XID event, xid={}, "
                            + "{} compressed bytes -> {} uncompressed).",
                    TransactionPayloadDeserializer.STREAMING_DECODER, xid,
                    ((StreamingTransactionPayloadEventData) data).getPayloadSizeLong(),
                    ((StreamingTransactionPayloadEventData) data).getUncompressedSizeLong());
            return true;
        } catch (Throwable t) {
            // UnsatisfiedLinkError / NoClassDefFoundError are exactly the failures this test exists to catch.
            log.error("Transaction_payload decoder self-test FAILED: {}", t.toString(), t);
            return false;
        }
    }

    /**
     * The decoder Debezium registers for TRANSACTION_PAYLOAD
     * ({@code BinlogStreamingChangeEventSource.createEventDeserializer}), constructed the
     * way Debezium constructs it.
     */
    static TransactionPayloadEventData decodePayload(byte[] body) throws Exception {
        return new TransactionPayloadDeserializer(new HashMap<>(),
                CommonConnectorConfig.EventProcessingFailureHandlingMode.FAIL)
                .deserialize(new ByteArrayInputStream(body));
    }

    /**
     * The {@code STREAMING_DECODER} marker of the TRANSACTION_PAYLOAD decoder class that
     * the runtime actually loaded, read reflectively so that a classpath on which
     * Debezium's stock class shadows this connector's is detected (the stock class has no
     * such field); null when the field is absent.
     */
    static String streamingDecoderMarker() {
        try {
            Object value = Class.forName(PAYLOAD_DECODER_CLASS).getField("STREAMING_DECODER").get(null);
            return value instanceof String ? (String) value : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String decoderLocation() {
        try {
            java.security.CodeSource source = Class.forName(PAYLOAD_DECODER_CLASS).getProtectionDomain().getCodeSource();
            return source == null || source.getLocation() == null ? PAYLOAD_DECODER_CLASS
                    : PAYLOAD_DECODER_CLASS + " from " + source.getLocation();
        } catch (ReflectiveOperationException | SecurityException e) {
            return PAYLOAD_DECODER_CLASS + " (" + e + ")";
        }
    }

    /**
     * A Transaction_payload event body wrapping one XID event, in the on-the-wire
     * format the deserializer reads: TLV fields (packed type, packed length,
     * packed value) for payload size, compression type and uncompressed size, an
     * end mark, then the zstd frame.
     */
    static byte[] syntheticPayload(long xid) {
        byte[] inner = xidEvent(xid);
        byte[] compressed = Zstd.compress(inner);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePackedInteger(out, OTW_PAYLOAD_SIZE_FIELD);
        writePackedInteger(out, 1);
        writePackedInteger(out, compressed.length);
        writePackedInteger(out, OTW_PAYLOAD_COMPRESSION_TYPE_FIELD);
        writePackedInteger(out, 1);
        writePackedInteger(out, COMPRESSION_TYPE_ZSTD);
        writePackedInteger(out, OTW_PAYLOAD_UNCOMPRESSED_SIZE_FIELD);
        writePackedInteger(out, 1);
        writePackedInteger(out, inner.length);
        writePackedInteger(out, OTW_PAYLOAD_HEADER_END_MARK);
        out.write(compressed, 0, compressed.length);
        return out.toByteArray();
    }

    /**
     * One binlog v4 XID event: the 19-byte header (timestamp, type, server id,
     * event size, next position, flags -- all little-endian) and the 8-byte xid.
     */
    static byte[] xidEvent(long xid) {
        int eventSize = V4_HEADER_LENGTH + XID_BODY_LENGTH;
        ByteArrayOutputStream out = new ByteArrayOutputStream(eventSize);
        writeLittleEndian(out, 1_700_000_000L, 4); // timestamp (seconds)
        out.write(XID_EVENT_TYPE);
        writeLittleEndian(out, 1L, 4);             // server_id
        writeLittleEndian(out, eventSize, 4);      // event_size
        writeLittleEndian(out, 0L, 4);             // log_pos (unused inside a payload)
        writeLittleEndian(out, 0L, 2);             // flags
        writeLittleEndian(out, xid, XID_BODY_LENGTH);
        return out.toByteArray();
    }

    /**
     * MySQL length-encoded integer, the two forms the self-test can need: one
     * byte below 251, {@code 0xFC} + u16 LE up to 65535.
     */
    static void writePackedInteger(ByteArrayOutputStream out, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException("packed integer out of the self-test's range: " + value);
        }
        if (value < 251) {
            out.write(value);
            return;
        }
        out.write(0xFC);
        writeLittleEndian(out, value, 2);
    }

    private static void writeLittleEndian(ByteArrayOutputStream out, long value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * The binlog client identity, {@code database.server.id}.
 *
 * <ul>
 *   <li>Config time: a set value must be an integer in {@code 1..4294967295} (MySQL's unsigned 32-bit
 *       server_id). Anything else refuses the start, naming the key and the value. An unset or blank value
 *       is left to Debezium.</li>
 *   <li>Start: the effective value is logged once.</li>
 *   <li>Error 1236 ("A slave with the same server_uuid/server_id ... has connected to the master"): one ERROR
 *       per JVM says another binlog client holds this server_id and names the key to change. The underlying
 *       exception is still logged by the caller; nothing is suppressed.</li>
 * </ul>
 */
public final class BinlogServerIdPolicy {

    public static final String KEY = "database.server.id";

    private static final Logger log = LogManager.getLogger(BinlogServerIdPolicy.class);

    static final long MAX_SERVER_ID = 4294967295L;
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,10}");
    private static final String COLLISION_TEXT = "same server_uuid/server_id";

    private static final AtomicBoolean EFFECTIVE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean COLLISION_LOGGED = new AtomicBoolean(false);

    private BinlogServerIdPolicy() {
    }

    /**
     * The configured server_id, or null when the property is unset or blank.
     *
     * @throws IllegalArgumentException when the value is set and is not an integer in 1..4294967295
     */
    public static Long serverIdOf(Properties props) {
        String raw = props == null ? null : props.getProperty(KEY);
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        if (!DIGITS.matcher(raw).matches()) {
            throw refuse(raw);
        }
        long value = Long.parseLong(raw);
        if (value < 1 || value > MAX_SERVER_ID) {
            throw refuse(raw);
        }
        return value;
    }

    /** Config-time check at engine start, and a one-time INFO naming the effective value. */
    public static void check(Properties props) {
        Long value = serverIdOf(props);
        if (EFFECTIVE_LOGGED.compareAndSet(false, true)) {
            log.info("{} = {} (the binlog client server_id this connector presents to the source)",
                    KEY, value == null ? "<unset: Debezium default>" : value);
        }
    }

    /**
     * Notes error 1236 when it appears in the failure's cause chain. Logs one ERROR per JVM.
     * The caller keeps logging the underlying exception.
     */
    public static void noteCollision(Throwable failure) {
        if (!isSameServerIdCollision(failure)) {
            return;
        }
        if (COLLISION_LOGGED.compareAndSet(false, true)) {
            log.error("Binlog error 1236: another binlog client is connected to the source with this connector's "
                    + "server_id. The source refuses a second client with the same server_uuid/server_id, so the "
                    + "engine cannot start while the other client holds it (a legacy connector that was replaced "
                    + "but is still running is the usual cause). Stop the other client, or set {} to an id that no "
                    + "other binlog client on the source uses (1..4294967295), and restart.", KEY);
        }
    }

    /**
     * True when the failure or any cause is the MySQL same-server-id refusal, matched by its text
     * ("same server_uuid/server_id"). The error code alone is not enough: MySQL 1236 is also the code for a
     * purged binlog ("Could not find first log file name in binary log index file"), which is not a
     * server_id problem.
     */
    public static boolean isSameServerIdCollision(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(COLLISION_TEXT)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static IllegalArgumentException refuse(String raw) {
        return new IllegalArgumentException("Refusing to start: " + KEY + "='" + raw + "' is not a valid binlog "
                + "client server_id: it must be an integer from 1 to " + MAX_SERVER_ID + " (MySQL's unsigned "
                + "32-bit server_id), and unique among the binlog clients of the source.");
    }
}

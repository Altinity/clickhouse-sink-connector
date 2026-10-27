package com.altinity.clickhouse.sink.connector.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The startup policy over the two zone properties (spec 07.03 section 3.1.2).
 *
 * <ul>
 *   <li>Both properties are mandatory. An unset one refuses the start, naming the key and a suggested value.
 *       There is no default zone and the MySQL session zone is never read to choose one.</li>
 *   <li>A set value resolves through {@link TimeZoneResolver}; an invalid one refuses the start with the key
 *       and the value.</li>
 *   <li>The effective pair is logged once per JVM.</li>
 * </ul>
 *
 * <p>This class is pure policy and does not connect to anything. The embedded engine refuses before it
 * opens a connection ({@code ConnectionTimeZonePreflight}); the Kafka Connect task applies the same rule in
 * {@code ClickHouseSinkConnectorConfig}.</p>
 */
public final class StartupZonePolicy {

    private static final Logger log = LogManager.getLogger(StartupZonePolicy.class);
    static final String SOURCE_KEY = TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE;
    static final String CLICKHOUSE_KEY = TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE;

    private static final AtomicBoolean LOGGED = new AtomicBoolean(false);

    /** The effective pair of one startup. */
    public record Plan(TimeZoneResolver.Effective clickHouse, TimeZoneResolver.Effective source) {
    }

    private StartupZonePolicy() {
    }

    /**
     * Resolves both zone properties from the raw property map.
     *
     * <p>Order: misspelled keys, then surrounding whitespace, then each set value, then the mandatory check.
     * A set but invalid value is reported before a missing key, so the message names the value the operator
     * actually wrote.</p>
     *
     * @param raw the raw property map.
     * @return the effective pair.
     * @throws TimeZoneResolver.InvalidTimeZoneException for a misspelled or untrimmed key, an invalid set value,
     *                                                   or an unset mandatory key.
     */
    public static Plan plan(Map<?, ?> raw) {
        TimeZoneResolver.rejectMisspelledKeys(raw);
        TimeZoneResolver.rejectUntrimmed(raw);
        String source = valueOf(raw, SOURCE_KEY);
        String clickHouse = valueOf(raw, CLICKHOUSE_KEY);
        if (!TimeZoneResolver.isUnset(source)) {
            TimeZoneResolver.resolve(SOURCE_KEY, source, TimeZoneResolver.Kind.SOURCE);
        }
        if (!TimeZoneResolver.isUnset(clickHouse)) {
            TimeZoneResolver.resolve(CLICKHOUSE_KEY, clickHouse, TimeZoneResolver.Kind.CLICKHOUSE);
        }
        TimeZoneResolver.requireConfigured(raw);
        return new Plan(
                TimeZoneResolver.effective(CLICKHOUSE_KEY, clickHouse, TimeZoneResolver.Kind.CLICKHOUSE),
                TimeZoneResolver.effective(SOURCE_KEY, source, TimeZoneResolver.Kind.SOURCE));
    }

    /** Logs the effective pair once per JVM. */
    public static void logEffective(String clickHouseZone, String sourceZone) {
        if (LOGGED.compareAndSet(false, true)) {
            log.info("Effective time zones: {}={}; {}={}", CLICKHOUSE_KEY, clickHouseZone, SOURCE_KEY, sourceZone);
        }
    }

    private static String valueOf(Map<?, ?> raw, String key) {
        Object value = raw.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

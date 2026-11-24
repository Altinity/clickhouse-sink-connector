package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver.InvalidTimeZoneException;
import com.altinity.clickhouse.sink.connector.validators.TimeZoneValidator;
import org.apache.kafka.common.config.ConfigException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Properties;

/**
 * Refuses to start when {@code database.connectionTimeZone} or {@code clickhouse.datetime.timezone} is unset or
 * does not resolve (spec 07.03 sections 3.1.2 and 3.1.5).
 *
 * <p>Both zone settings are mandatory and have no default. The rule applies to every source connector, MySQL and
 * PostgreSQL alike, and the check runs before any connection to the source or to ClickHouse. An unset, blank or
 * {@code SERVER} value, a misspelled key, surrounding whitespace, or a value that is not a zone for its key
 * refuses the start, with a banner that names the key and a suggested value.</p>
 *
 * <p>No session zone is read. The driver's session-derived zone (unset, or {@code SERVER}) is the implicit zone the
 * rule removes, so there is nothing to probe: the operator states the zone and the sink and the driver use it.</p>
 */
public final class ConnectionTimeZonePreflight {

    private static final Logger log = LogManager.getLogger(ConnectionTimeZonePreflight.class);

    static final String PROPERTY = TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE;
    static final String CLICKHOUSE_TIMEZONE_PROPERTY = TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE;

    private static final String BANNER_RULE =
            "========================================================================";

    private ConnectionTimeZonePreflight() {
    }

    /**
     * Checks both zone settings of a connector configuration before any connection is opened.
     *
     * @param props the connector properties.
     * @throws IllegalStateException when a zone setting is unset, misspelled, untrimmed, or not a zone for its key.
     *                               The message starts with {@code Refusing to start: }.
     */
    public static void check(Properties props) {
        try {
            TimeZoneValidator.validateRawProperties(props);
            TimeZoneResolver.resolve(PROPERTY, props.getProperty(PROPERTY), TimeZoneResolver.Kind.SOURCE);
            TimeZoneResolver.resolve(CLICKHOUSE_TIMEZONE_PROPERTY, props.getProperty(CLICKHOUSE_TIMEZONE_PROPERTY),
                    TimeZoneResolver.Kind.CLICKHOUSE);
        } catch (InvalidTimeZoneException | ConfigException e) {
            refuse(e.getMessage());
        }
    }

    private static void refuse(String message) {
        log.error("\n{}\n  !!  REFUSING TO START: {}\n{}", BANNER_RULE, message, BANNER_RULE);
        throw new IllegalStateException("Refusing to start: " + message);
    }
}

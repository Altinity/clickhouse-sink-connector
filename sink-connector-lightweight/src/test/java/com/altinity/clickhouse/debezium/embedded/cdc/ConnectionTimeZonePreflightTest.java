package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;
import com.mysql.cj.util.TimeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup refusal for the two mandatory zone settings (spec 07.03 sections 3.1.2 and 3.1.5).
 *
 * <p>This class does not probe the source's session zone over JDBC: the session zone is not a source of truth,
 * so there is no probe and no test for one. {@link ConnectionTimeZonePreflight#check} takes the
 * properties only and opens no connection. The refusal therefore happens before any connection, and the
 * unreachable-host test below pins that.</p>
 */
public class ConnectionTimeZonePreflightTest {

    private static final String MYSQL_CONNECTOR = "io.debezium.connector.mysql.MySqlConnector";
    private static final String POSTGRES_CONNECTOR = "io.debezium.connector.postgresql.PostgresConnector";
    private static final String SOURCE = TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE;
    private static final String CLICKHOUSE = TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE;

    /** A MySQL connector config; a null zone argument leaves that key out of the properties entirely. */
    private static Properties mysqlProps(String sourceZone, String clickHouseZone) {
        Properties props = new Properties();
        props.setProperty("connector.class", MYSQL_CONNECTOR);
        if (sourceZone != null) {
            props.setProperty(SOURCE, sourceZone);
        }
        if (clickHouseZone != null) {
            props.setProperty(CLICKHOUSE, clickHouseZone);
        }
        return props;
    }

    /** Runs the check, expects a refusal, and returns the message. */
    private static String refusal(Properties props) {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ConnectionTimeZonePreflight.check(props));
        assertTrue(refused.getMessage().startsWith("Refusing to start: "), refused.getMessage());
        return refused.getMessage();
    }

    @Test
    @DisplayName("Both settings explicit as UTC: a MySQL source passes the check")
    public void explicitUtcPasses() {
        ConnectionTimeZonePreflight.check(mysqlProps("UTC", "UTC"));
    }

    @Test
    @DisplayName("An IANA id in both settings resolves and passes the check")
    public void namedZoneResolves() {
        ConnectionTimeZonePreflight.check(mysqlProps("America/Chicago", "America/Chicago"));
    }

    @Test
    @DisplayName("Unset database.connectionTimeZone is refused before any connection, naming the key and UTC")
    public void unsetSourceIsRefusedBeforeConnection() {
        Properties props = mysqlProps(null, "UTC");
        // An unreachable host: a check that tried to connect would fail with a SQL error, not this refusal.
        props.setProperty("database.hostname", "unreachable.invalid");
        props.setProperty("database.port", "3306");

        String message = refusal(props);
        assertTrue(message.contains("database.connectionTimeZone is not set"), message);
        assertTrue(message.contains("database.connectionTimeZone=UTC"), message);
    }

    @Test
    @DisplayName("Unset clickhouse.datetime.timezone is refused, naming the key and UTC")
    public void unsetClickHouseIsRefused() {
        String message = refusal(mysqlProps("UTC", null));
        assertTrue(message.contains("clickhouse.datetime.timezone is not set"), message);
        assertTrue(message.contains("clickhouse.datetime.timezone=UTC"), message);
    }

    @Test
    @DisplayName("A blank database.connectionTimeZone is refused as unset")
    public void blankSourceIsRefused() {
        String message = refusal(mysqlProps("   ", "UTC"));
        assertTrue(message.contains("database.connectionTimeZone is not set"), message);
    }

    @Test
    @DisplayName("An empty clickhouse.datetime.timezone is refused as unset")
    public void emptyClickHouseIsRefused() {
        String message = refusal(mysqlProps("UTC", ""));
        assertTrue(message.contains("clickhouse.datetime.timezone is not set"), message);
    }

    @Test
    @DisplayName("database.connectionTimeZone=SERVER is refused, with the session reason")
    public void serverKeywordIsRefused() {
        String message = refusal(mysqlProps("SERVER", "UTC"));
        assertTrue(message.contains("database.connectionTimeZone"), message);
        assertTrue(message.contains("session"), message);
    }

    @Test
    @DisplayName("A misspelled database.connectionTimezone is refused, naming the canonical key")
    public void misspelledSourceKeyIsRefused() {
        Properties props = mysqlProps(null, "UTC");
        props.setProperty("database.connectionTimezone", "UTC");

        String message = refusal(props);
        assertTrue(message.contains("misspelled"), message);
        assertTrue(message.contains("database.connectionTimeZone"), message);
    }

    @Test
    @DisplayName("A ClickHouse zone with surrounding whitespace is refused")
    public void untrimmedValueIsRefused() {
        String message = refusal(mysqlProps("UTC", " America/Chicago"));
        assertTrue(message.contains("whitespace"), message);
        assertTrue(message.contains("clickhouse.datetime.timezone"), message);
    }

    @Test
    @DisplayName("clickhouse.datetime.timezone=EST is refused, with the region ids it could mean")
    public void abbreviationIsRefusedWithCandidates() {
        String message = refusal(mysqlProps("UTC", "EST"));
        assertTrue(message.contains("America/New_York"), message);
    }

    @Test
    @DisplayName("A PostgreSQL connector with both zones unset is refused too: the mandatory-zone rule covers the PostgreSQL path")
    public void postgresWithoutZonesIsRefused() {
        Properties props = new Properties();
        props.setProperty("connector.class", POSTGRES_CONNECTOR);
        // The empty map is refused on the first missing key in check order (the ClickHouse key); either key is correct.
        String message = refusal(props);
        assertTrue(message.contains(" is not set"), message);
        assertTrue(message.contains(SOURCE) || message.contains(CLICKHOUSE), message);
    }

    @Test
    @DisplayName("A PostgreSQL connector with both zones explicit as UTC passes the check")
    public void postgresWithZonesPasses() {
        Properties props = new Properties();
        props.setProperty("connector.class", POSTGRES_CONNECTOR);
        props.setProperty(SOURCE, "UTC");
        props.setProperty(CLICKHOUSE, "UTC");
        ConnectionTimeZonePreflight.check(props);
    }

    @Test
    @DisplayName("Connector/J rejects the abbreviation CDT and resolves America/Chicago: the basis of the refusal")
    public void driverAgreesWithThePreflight() {
        RuntimeException cdt = assertThrows(RuntimeException.class,
                () -> TimeUtil.getCanonicalTimeZone("CDT", null),
                "Connector/J cannot map the abbreviation CDT to one zone; if this ever passes, the "
                        + "preflight's refusal is no longer grounded in the driver");
        assertTrue(cdt.getMessage().contains("CDT"), cdt.getMessage());
        assertEquals("America/Chicago", TimeUtil.getCanonicalTimeZone("America/Chicago", null));
        assertNotNull(TimeUtil.getCanonicalTimeZone("UTC", null));
    }
}

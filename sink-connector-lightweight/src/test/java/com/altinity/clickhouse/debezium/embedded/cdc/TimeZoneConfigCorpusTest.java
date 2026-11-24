package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.StartupZonePolicy;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver.InvalidTimeZoneException;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-corpus test for the startup zone policy (spec 07.03 sections 3.1.2 and 3.1.6) and the
 * binlog server_id validator (section 3.1.6). Each corpus entry either starts with the documented effective zones or
 * is refused with the documented key and value. There is no default zone and no session-derived zone, so no entry
 * depends on the JVM, host, ClickHouse server or MySQL session.
 *
 * <p>spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/StartupZonePolicy.java::plan
 * and sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogServerIdPolicy.java::serverIdOf</p>
 */
public class TimeZoneConfigCorpusTest {

    private static final String SOURCE = TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE;
    private static final String CLICKHOUSE = TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE;

    private static Map<String, String> raw(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** Case 1: neither key set. Refused; the ClickHouse key is the first missing key in definition order. */
    @Test
    @DisplayName("corpus 1: neither key set is refused, naming the ClickHouse key first with a suggested value")
    public void neitherKeySetIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw()));
        assertEquals(CLICKHOUSE, refused.getProperty());
        assertTrue(refused.getMessage().contains(CLICKHOUSE + " is not set"), refused.getMessage());
        assertTrue(refused.getMessage().contains(CLICKHOUSE + "=UTC"), refused.getMessage());
    }

    /** Case 2: only the ClickHouse key set. Refused; the source key is named, and the explicit ClickHouse value is kept out of it. */
    @Test
    @DisplayName("corpus 2: only clickhouse.datetime.timezone set is refused, naming database.connectionTimeZone")
    public void onlyClickHouseKeySetIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(CLICKHOUSE, "Europe/Berlin")));
        assertEquals(SOURCE, refused.getProperty());
        assertTrue(refused.getMessage().contains(SOURCE + " is not set"), refused.getMessage());
        assertTrue(refused.getMessage().contains(SOURCE + "=UTC"), refused.getMessage());
    }

    /** Case 3: only the source key set. Refused; the ClickHouse key is named. */
    @Test
    @DisplayName("corpus 3: only database.connectionTimeZone set is refused, naming clickhouse.datetime.timezone")
    public void onlySourceKeySetIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(SOURCE, "UTC")));
        assertEquals(CLICKHOUSE, refused.getProperty());
        assertTrue(refused.getMessage().contains(CLICKHOUSE + " is not set"), refused.getMessage());
    }

    /** Case 4: both UTC, as the ansible role renders it. Both explicit. */
    @Test
    @DisplayName("corpus 4: both keys UTC start with both explicit")
    public void bothUtcStartsExplicit() {
        StartupZonePolicy.Plan plan = StartupZonePolicy.plan(raw(SOURCE, "UTC", CLICKHOUSE, "UTC"));
        assertEquals(SOURCE, plan.source().property());
        assertEquals(ZoneId.of("UTC"), plan.source().zone());
        assertEquals(CLICKHOUSE, plan.clickHouse().property());
        assertEquals(ZoneId.of("UTC"), plan.clickHouse().zone());
    }

    /** Case 5: both America/Chicago. Both explicit; the two keys are not compared with each other. */
    @Test
    @DisplayName("corpus 5: both keys America/Chicago start with both explicit")
    public void bothChicagoStartsExplicit() {
        StartupZonePolicy.Plan plan = StartupZonePolicy.plan(raw(SOURCE, "America/Chicago", CLICKHOUSE, "America/Chicago"));
        assertEquals(ZoneId.of("America/Chicago"), plan.source().zone());
        assertEquals(ZoneId.of("America/Chicago"), plan.clickHouse().zone());
    }

    /** Case 6: a blank value is unset, not a zone. */
    @Test
    @DisplayName("corpus 6: a blank database.connectionTimeZone is refused as unset, whatever the ClickHouse key is")
    public void blankSourceIsRefusedAsUnset() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(SOURCE, "   ", CLICKHOUSE, "UTC")));
        assertEquals(SOURCE, refused.getProperty());
        assertTrue(refused.getMessage().contains(SOURCE + " is not set"), refused.getMessage());
    }

    /** Case 7: a misspelled key is refused before any zone is resolved, naming the canonical key. */
    @Test
    @DisplayName("corpus 7: misspelled database.connectionTimezone is refused, naming the canonical key")
    public void misspelledSourceKeyIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw("database.connectionTimezone", "UTC", CLICKHOUSE, "UTC")));
        assertEquals(SOURCE, refused.getProperty());
        assertTrue(refused.getMessage().contains("misspelled"), refused.getMessage());
        assertTrue(refused.getMessage().contains("database.connectionTimezone"), refused.getMessage());
    }

    @Test
    @DisplayName("corpus 7: misspelled clickhouse.datetime.timeZone is refused, naming the canonical key")
    public void misspelledClickHouseKeyIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(SOURCE, "UTC", "clickhouse.datetime.timeZone", "UTC")));
        assertEquals(CLICKHOUSE, refused.getProperty());
    }

    /** Case 8: surrounding whitespace on a set value is refused; Kafka would trim it silently. */
    @Test
    @DisplayName("corpus 8: a ClickHouse zone with a leading space is refused, naming the key")
    public void untrimmedValueIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(SOURCE, "UTC", CLICKHOUSE, " America/Chicago")));
        assertEquals(CLICKHOUSE, refused.getProperty());
    }

    /** Case 9: SERVER is refused. It makes the zone depend on the MySQL session. */
    @Test
    @DisplayName("corpus 9: database.connectionTimeZone=SERVER is refused, naming the key and the reason")
    public void serverKeywordIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(SOURCE, "SERVER", CLICKHOUSE, "UTC")));
        assertEquals(SOURCE, refused.getProperty());
        assertEquals("SERVER", refused.getRawValue());
        assertTrue(refused.getMessage().contains("session"), refused.getMessage());
    }

    /** Case 10: a bad zone value is refused with the same key and value. */
    @Test
    @DisplayName("corpus 10: an unknown source zone is refused with the same key and value")
    public void badSourceZoneIsRefused() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(SOURCE, "Europe/Nowhere", CLICKHOUSE, "UTC")));
        assertEquals(SOURCE, refused.getProperty());
        assertEquals("Europe/Nowhere", refused.getRawValue());
    }

    /** Case 11: an abbreviation is refused with the region ids it could mean. */
    @Test
    @DisplayName("corpus 11: clickhouse.datetime.timezone=EST is refused, naming the key and the region ids")
    public void abbreviationIsRefusedWithCandidates() {
        InvalidTimeZoneException refused = assertThrows(InvalidTimeZoneException.class,
                () -> StartupZonePolicy.plan(raw(CLICKHOUSE, "EST", SOURCE, "UTC")));
        assertEquals(CLICKHOUSE, refused.getProperty());
        assertTrue(refused.getMessage().contains("America/New_York"), refused.getMessage());
    }

    /** The Kafka Connect path applies the same rule at construction. */
    @Test
    @DisplayName("corpus 11: clickhouse.datetime.timezone=EST is a ConfigException at construction, naming key and value")
    public void badClickHouseZoneIsRefusedAtConstruction() {
        ConfigException refused = assertThrows(ConfigException.class,
                () -> new ClickHouseSinkConnectorConfig(raw(CLICKHOUSE, "EST", SOURCE, "UTC")));
        assertTrue(refused.getMessage().contains(CLICKHOUSE), refused.getMessage());
        assertTrue(refused.getMessage().contains("EST"), refused.getMessage());
    }

    /** Case 12: out-of-range server_id. Refused with the key and the value; the boundaries are accepted. */
    @Test
    @DisplayName("corpus 12: database.server.id above 4294967295 is refused, naming key and value")
    public void outOfRangeServerIdIsRefused() {
        Properties props = new Properties();
        props.setProperty(BinlogServerIdPolicy.KEY, "4294967296");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> BinlogServerIdPolicy.serverIdOf(props));
        assertTrue(refused.getMessage().contains("database.server.id='4294967296'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("1 to 4294967295"), refused.getMessage());
    }

    @Test
    @DisplayName("corpus 12: database.server.id 0 is refused; 1 and 4294967295 are accepted")
    public void serverIdBoundsAreInclusive() {
        Properties zero = new Properties();
        zero.setProperty(BinlogServerIdPolicy.KEY, "0");
        assertThrows(IllegalArgumentException.class, () -> BinlogServerIdPolicy.serverIdOf(zero));

        Properties one = new Properties();
        one.setProperty(BinlogServerIdPolicy.KEY, "1");
        assertEquals(Long.valueOf(1L), BinlogServerIdPolicy.serverIdOf(one));

        Properties max = new Properties();
        max.setProperty(BinlogServerIdPolicy.KEY, "4294967295");
        assertEquals(Long.valueOf(4294967295L), BinlogServerIdPolicy.serverIdOf(max));
    }

    @Test
    @DisplayName("corpus 12: an unset database.server.id is null (the caller keeps its own default)")
    public void unsetServerIdIsNull() {
        assertNull(BinlogServerIdPolicy.serverIdOf(new Properties()));
    }
}

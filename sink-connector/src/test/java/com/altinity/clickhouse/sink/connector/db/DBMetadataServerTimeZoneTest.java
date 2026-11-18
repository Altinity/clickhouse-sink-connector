package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.ZoneFixtures;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.common.ServerTimeZoneUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 07.03 §3.1.2 / §3.1.7 (B2): the ClickHouse server zone is read or the read fails loudly.
 * There is no silent UTC fallback for a missing connection, a failed query, an empty result or a zone
 * the JVM cannot resolve.
 */
public class DBMetadataServerTimeZoneTest {

    private static DBMetadata metadata() {
        return new DBMetadata(new ClickHouseSinkConnectorConfig(ZoneFixtures.withZones(new HashMap<>())));
    }

    /** A connection whose SELECT timezone() returns {@code zone} (null = no row) or throws {@code failure}. */
    private static Connection connection(String zone, SQLException failure) {
        ResultSet rs = (ResultSet) Proxy.newProxyInstance(DBMetadataServerTimeZoneTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "next":
                            return zone != null;
                        case "getString":
                            return zone;
                        default:
                            return null;
                    }
                });
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                DBMetadataServerTimeZoneTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeQuery")) {
                        if (failure != null) {
                            throw failure;
                        }
                        return rs;
                    }
                    return null;
                });
        return (Connection) Proxy.newProxyInstance(DBMetadataServerTimeZoneTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        return ps;
                    }
                    return null;
                });
    }

    @Test
    @DisplayName("The server zone is returned as reported by SELECT timezone()")
    public void resolvedZoneIsReturned() {
        assertEquals(ZoneId.of("America/Chicago"), metadata().getServerTimeZone(connection("America/Chicago", null)));
        assertEquals(ZoneId.of("UTC"), metadata().getServerTimeZone(connection("UTC", null)));
    }

    @Test
    @DisplayName("No connection is a loud failure, not UTC")
    public void nullConnectionFailsLoudly() {
        assertThrows(ServerTimeZoneUnavailableException.class, () -> metadata().getServerTimeZone(null));
    }

    @Test
    @DisplayName("A failed SELECT timezone() is a loud failure, not UTC")
    public void queryFailureFailsLoudly() {
        assertThrows(ServerTimeZoneUnavailableException.class,
                () -> metadata().getServerTimeZone(connection(null, new SQLException("boom"))));
    }

    @Test
    @DisplayName("An empty SELECT timezone() result is a loud failure, not UTC")
    public void emptyResultFailsLoudly() {
        assertThrows(ServerTimeZoneUnavailableException.class,
                () -> metadata().getServerTimeZone(connection(null, null)));
    }

    @Test
    @DisplayName("A zone the JVM cannot resolve (ClickHouse accepts EST, Java does not) is a loud failure")
    public void unresolvableZoneFailsLoudly() {
        assertThrows(ServerTimeZoneUnavailableException.class,
                () -> metadata().getServerTimeZone(connection("EST", null)));
    }
}

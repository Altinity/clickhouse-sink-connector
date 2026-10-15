package com.altinity.clickhouse.debezium.embedded.cdc;

import com.mysql.cj.util.TimeUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GPL MySQL JDBC driver is supplied at run time, not shaded into the
 * Apache-2.0 jar (doc/licensing.md). A MySQL pipeline without it is refused
 * at start with the ways to supply it; the zone resolver that
 * {@link ConnectionTimeZonePreflight} uses is the driver's own, reached by
 * reflection, and must behave exactly like a direct call.
 *
 * <p>The driver is on the test class path ({@code provided} scope), so the
 * "missing" case uses a class loader that cannot see it: the platform loader
 * plus this module's own classes would still see the driver, so the loader
 * here has NO parent class path at all.</p>
 */
public class MySqlJdbcDriverTest {

    private static Properties connector(String connectorClass) {
        Properties p = new Properties();
        p.setProperty("connector.class", connectorClass);
        return p;
    }

    /** A loader that sees the JDK only: the driver class is not visible through it. */
    private static ClassLoader withoutDriver() {
        return new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
    }

    @Test
    @DisplayName("MySQL connector with the driver on the class path starts")
    void mysqlWithDriverPasses() {
        assertDoesNotThrow(() -> MySqlJdbcDriver.check(connector("io.debezium.connector.mysql.MySqlConnector")));
    }

    @Test
    @DisplayName("MySQL connector without the driver is refused, naming every way to supply it")
    void mysqlWithoutDriverIsRefused() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MySqlJdbcDriver.check(connector("io.debezium.connector.mysql.MySqlConnector"), withoutDriver()));
        String m = e.getMessage();
        assertTrue(m.startsWith("Refusing to start:"), m);
        assertTrue(m.contains(MySqlJdbcDriver.DRIVER_CLASS), m);
        assertTrue(m.contains("mysql-connector-j.jar"), m);
        assertTrue(m.contains("lib/mysql-connector-j.jar"), m);
        assertTrue(m.contains("java -cp"), m);
        assertTrue(m.contains("-Pbundle-mysql-driver"), m);
        assertTrue(e.getCause() instanceof ClassNotFoundException, String.valueOf(e.getCause()));
    }

    @Test
    @DisplayName("PostgreSQL connector needs no MySQL driver and is never refused")
    void postgresWithoutDriverPasses() {
        assertDoesNotThrow(() -> MySqlJdbcDriver.check(
                connector("io.debezium.connector.postgresql.PostgresConnector"), withoutDriver()));
        assertDoesNotThrow(() -> MySqlJdbcDriver.check(new Properties(), withoutDriver()));
    }

    @Test
    @DisplayName("the reflective resolver returns exactly what the driver's TimeUtil returns")
    void reflectiveResolverAgreesWithDriver() {
        for (String zone : new String[] {"UTC", "+00:00", "America/Chicago", "Europe/London", "-05:00"}) {
            assertEquals(TimeUtil.getCanonicalTimeZone(zone, null), MySqlJdbcDriver.canonicalTimeZone(zone), zone);
        }
    }

    @Test
    @DisplayName("an ambiguous abbreviation throws the driver's own exception, unwrapped")
    void reflectiveResolverRethrowsDriverRefusal() {
        RuntimeException direct = assertThrows(RuntimeException.class, () -> TimeUtil.getCanonicalTimeZone("CDT", null));
        RuntimeException reflective = assertThrows(RuntimeException.class, () -> MySqlJdbcDriver.canonicalTimeZone("CDT"));
        assertEquals(direct.getClass(), reflective.getClass());
        assertEquals(direct.getMessage(), reflective.getMessage());
    }

    @Test
    @DisplayName("the resolver lookup fails as an installation problem when the driver is absent")
    void resolverLookupWithoutDriver() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MySqlJdbcDriver.resolver(withoutDriver()));
        assertTrue(e.getMessage().contains(MySqlJdbcDriver.TIME_UTIL_CLASS), e.getMessage());
        assertNotNull(MySqlJdbcDriver.resolver(MySqlJdbcDriver.class.getClassLoader()));
    }
}

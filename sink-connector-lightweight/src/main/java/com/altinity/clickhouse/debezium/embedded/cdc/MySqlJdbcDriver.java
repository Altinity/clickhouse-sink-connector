package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Properties;

/**
 * The MySQL JDBC driver (MySQL Connector/J) is supplied at run time, not
 * shipped inside the connector jar.
 *
 * <p><b>Why.</b> This project is licensed under the Apache License 2.0.
 * MySQL Connector/J is licensed under the GNU GPL v2 (with the Universal FOSS
 * Exception), a license the Apache Software Foundation classifies as
 * Category X: it may not be bundled into an Apache-licensed distribution.
 * The default build therefore declares the driver {@code provided}: it is on
 * the compile and test class path, and it is NOT copied into the shaded jar.
 * The project's own sources never link against driver classes either; the
 * one driver helper the connector uses is reached through reflection
 * ({@link #canonicalTimeZone}).</p>
 *
 * <p><b>How the driver gets on the class path.</b> Any of:</p>
 * <ul>
 *   <li>place (or symlink) the driver jar as {@code mysql-connector-j.jar},
 *       or {@code lib/mysql-connector-j.jar}, next to the connector jar --
 *       the connector jar's manifest {@code Class-Path} names both, so
 *       {@code java -jar} picks it up with no other change;</li>
 *   <li>start with an explicit class path:
 *       {@code java -cp clickhouse-debezium-embedded.jar:mysql-connector-j-<v>.jar
 *       com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication ...};</li>
 *   <li>for a build that is used internally and never redistributed, build
 *       with {@code -Pbundle-mysql-driver}, which shades the driver into the
 *       jar exactly as earlier releases did.</li>
 * </ul>
 *
 * <p>Debezium's MySQL connector cannot run without the driver, so a MySQL
 * pipeline started without it is refused at start by {@link #check} with the
 * three options above, instead of failing later with a
 * {@code ClassNotFoundException} or "No suitable driver" from inside the
 * engine's retry loop. Non-MySQL connectors (PostgreSQL) are never touched
 * and need no driver.</p>
 */
public final class MySqlJdbcDriver {

    private static final Logger log = LogManager.getLogger(MySqlJdbcDriver.class);

    /** The JDBC driver class Debezium's MySQL connector loads. */
    static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    /** Connector/J's zone resolver, used by {@link ConnectionTimeZonePreflight}. */
    static final String TIME_UTIL_CLASS = "com.mysql.cj.util.TimeUtil";

    /** The file name the connector jar's manifest {@code Class-Path} looks for. */
    static final String SIDE_BY_SIDE_JAR = "mysql-connector-j.jar";

    private static final String BANNER_RULE =
            "========================================================================";

    private MySqlJdbcDriver() {
    }

    /** True when the configured connector reads a MySQL binlog. */
    static boolean isMySql(Properties props) {
        return props.getProperty("connector.class", "").toLowerCase(Locale.ROOT).contains("mysql");
    }

    /**
     * Refuses to start a MySQL pipeline when the MySQL JDBC driver is not on
     * the class path. Does nothing for other connectors.
     *
     * @param props the connector properties.
     * @throws IllegalStateException when the connector is MySQL and the driver is missing.
     */
    public static void check(Properties props) {
        check(props, MySqlJdbcDriver.class.getClassLoader());
    }

    /**
     * {@link #check(Properties)} against a given class loader, for the tests.
     */
    static void check(Properties props, ClassLoader loader) {
        if (!isMySql(props)) {
            return;
        }
        try {
            Class<?> driver = Class.forName(DRIVER_CLASS, false, loader);
            log.info("MySQL JDBC driver {} found ({}).", DRIVER_CLASS, location(driver));
        } catch (ClassNotFoundException | LinkageError e) {
            String message = String.format(
                    "the connector is a MySQL connector (%s) but the MySQL JDBC driver (%s) is not on the class "
                            + "path. It is not bundled in this jar because MySQL Connector/J is GPL-licensed and "
                            + "this project is Apache-2.0. Supply it in one of these ways and restart: (1) place "
                            + "or symlink the driver jar as %s (or lib/%s) in the same directory as the connector "
                            + "jar -- the jar's manifest Class-Path loads it from there; (2) start with "
                            + "java -cp <connector jar>:<mysql-connector-j jar> "
                            + "com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication ...; "
                            + "(3) for an internal, non-redistributed build, build with -Pbundle-mysql-driver.",
                    props.getProperty("connector.class"), DRIVER_CLASS, SIDE_BY_SIDE_JAR, SIDE_BY_SIDE_JAR);
            log.error("\n{}\n  !!  REFUSING TO START: {}\n{}", BANNER_RULE, message, BANNER_RULE);
            throw new IllegalStateException("Refusing to start: " + message, e);
        }
    }

    /**
     * Connector/J's own {@code TimeUtil.getCanonicalTimeZone(zone, null)},
     * called reflectively so this project's sources do not link against the
     * GPL-licensed driver.
     *
     * @param zone the zone string the driver would resolve.
     * @return what the driver resolves it to.
     * @throws RuntimeException whatever the driver throws for a zone it
     *                          cannot resolve (unwrapped from the reflective call).
     * @throws IllegalStateException when the driver, or the method, is not
     *                               available (the driver check runs first, so
     *                               this means an incompatible driver version).
     */
    static String canonicalTimeZone(String zone) {
        Method resolver = resolver(MySqlJdbcDriver.class.getClassLoader());
        try {
            return (String) resolver.invoke(null, zone, null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(TIME_UTIL_CLASS + ".getCanonicalTimeZone failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot call " + TIME_UTIL_CLASS + ".getCanonicalTimeZone: " + e, e);
        }
    }

    /**
     * Finds {@code public static String getCanonicalTimeZone(String, <interceptor>)}.
     * The second parameter is Connector/J's {@code ExceptionInterceptor}; it is
     * matched by arity rather than by type so no driver type is named here.
     */
    static Method resolver(ClassLoader loader) {
        Class<?> timeUtil;
        try {
            timeUtil = Class.forName(TIME_UTIL_CLASS, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("The MySQL JDBC driver is not on the class path (" + TIME_UTIL_CLASS
                    + " not found); see " + MySqlJdbcDriver.class.getName() + ".", e);
        }
        for (Method m : timeUtil.getMethods()) {
            if (m.getName().equals("getCanonicalTimeZone")
                    && Modifier.isStatic(m.getModifiers())
                    && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == String.class
                    && !m.getParameterTypes()[1].isPrimitive()
                    && m.getReturnType() == String.class) {
                return m;
            }
        }
        throw new IllegalStateException(TIME_UTIL_CLASS + " has no static String getCanonicalTimeZone(String, "
                + "ExceptionInterceptor) in this MySQL JDBC driver version (" + location(timeUtil) + ").");
    }

    private static String location(Class<?> c) {
        try {
            return String.valueOf(c.getProtectionDomain().getCodeSource().getLocation());
        } catch (RuntimeException e) {
            return "unknown location";
        }
    }
}

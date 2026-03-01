package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DDLParserFactory}.
 *
 * <p>Verifies that the factory correctly routes to {@link PostgreSQLDDLParserService}
 * for PostgreSQL connectors and to {@link MySQLDDLParserService} for MySQL/MariaDB
 * connectors, regardless of which overload is used.</p>
 */
@DisplayName("DDLParserFactory - Unit Tests")
class DDLParserFactoryTest {

    // -----------------------------------------------------------------------
    // String-based overload: getParser(String, BaseDbWriter, config, dbName)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Factory returns PostgreSQLDDLParserService for PostgresConnector class name")
    void testFactoryReturnsPostgresParserForPostgresConnector() {
        DDLParserService parser = DDLParserFactory.getParser(
                DDLParserFactory.POSTGRES_CONNECTOR_CLASS,
                null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(PostgreSQLDDLParserService.class, parser,
                "Expected PostgreSQLDDLParserService for Postgres connector");
    }

    @Test
    @DisplayName("Factory returns MySQLDDLParserService for MySqlConnector class name")
    void testFactoryReturnsMySQLParserForMySQLConnector() {
        DDLParserService parser = DDLParserFactory.getParser(
                DDLParserFactory.MYSQL_CONNECTOR_CLASS,
                null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService for MySQL connector");
    }

    @Test
    @DisplayName("Factory returns MySQLDDLParserService for MariaDbConnector class name")
    void testFactoryReturnsMySQLParserForMariaDbConnector() {
        DDLParserService parser = DDLParserFactory.getParser(
                DDLParserFactory.MARIADB_CONNECTOR_CLASS,
                null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService for MariaDB connector");
    }

    @Test
    @DisplayName("Factory returns MySQLDDLParserService for null connector class (safe default)")
    void testFactoryReturnsMySQLParserForNullConnectorClass() {
        DDLParserService parser = DDLParserFactory.getParser(
                (String) null,
                null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService as safe default for null connector class");
    }

    @Test
    @DisplayName("Factory returns MySQLDDLParserService for empty connector class string")
    void testFactoryReturnsMySQLParserForEmptyConnectorClass() {
        DDLParserService parser = DDLParserFactory.getParser(
                "",
                null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService as safe default for empty connector class");
    }

    @Test
    @DisplayName("Factory routing is case-insensitive for postgres connector class")
    void testFactoryRoutingIsCaseInsensitiveForPostgres() {
        // Mixed-case connector class name
        DDLParserService parser = DDLParserFactory.getParser(
                "io.debezium.connector.POSTGRESQL.PostgresConnector",
                null, null, "db");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(PostgreSQLDDLParserService.class, parser,
                "Postgres routing should be case-insensitive");
    }

    // -----------------------------------------------------------------------
    // Properties-based overload: getParser(Properties, BaseDbWriter, config, dbName)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Factory (Properties overload) returns PostgreSQLDDLParserService for PostgresConnector")
    void testFactoryPropertiesOverloadReturnsPostgresParser() {
        Properties props = new Properties();
        props.setProperty("connector.class", DDLParserFactory.POSTGRES_CONNECTOR_CLASS);

        DDLParserService parser = DDLParserFactory.getParser(props, null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(PostgreSQLDDLParserService.class, parser,
                "Expected PostgreSQLDDLParserService for Postgres connector via Properties");
    }

    @Test
    @DisplayName("Factory (Properties overload) returns MySQLDDLParserService for MySqlConnector")
    void testFactoryPropertiesOverloadReturnsMySQLParser() {
        Properties props = new Properties();
        props.setProperty("connector.class", DDLParserFactory.MYSQL_CONNECTOR_CLASS);

        DDLParserService parser = DDLParserFactory.getParser(props, null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService for MySQL connector via Properties");
    }

    @Test
    @DisplayName("Factory (Properties overload) returns MySQLDDLParserService for MariaDbConnector")
    void testFactoryPropertiesOverloadReturnsMySQLParserForMariaDb() {
        Properties props = new Properties();
        props.setProperty("connector.class", DDLParserFactory.MARIADB_CONNECTOR_CLASS);

        DDLParserService parser = DDLParserFactory.getParser(props, null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService for MariaDB connector via Properties");
    }

    @Test
    @DisplayName("Factory (Properties overload) returns MySQLDDLParserService for null Properties")
    void testFactoryPropertiesOverloadHandlesNullProps() {
        DDLParserService parser = DDLParserFactory.getParser(
                (Properties) null, null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService as safe default when Properties is null");
    }

    @Test
    @DisplayName("Factory (Properties overload) returns MySQLDDLParserService when connector.class key is absent")
    void testFactoryPropertiesOverloadHandlesMissingKey() {
        Properties props = new Properties();
        // no connector.class key set

        DDLParserService parser = DDLParserFactory.getParser(props, null, null, "testdb");

        assertNotNull(parser, "Parser must not be null");
        assertInstanceOf(MySQLDDLParserService.class, parser,
                "Expected MySQLDDLParserService as safe default when connector.class is absent");
    }

    // -----------------------------------------------------------------------
    // isPostgresConnector helper method tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("isPostgresConnector - returns true for official PostgresConnector class")
    void testIsPostgresConnectorTrueForOfficialClass() {
        assertTrue(DDLParserFactory.isPostgresConnector(DDLParserFactory.POSTGRES_CONNECTOR_CLASS));
    }

    @Test
    @DisplayName("isPostgresConnector - returns false for MySqlConnector class")
    void testIsPostgresConnectorFalseForMySQL() {
        assertFalse(DDLParserFactory.isPostgresConnector(DDLParserFactory.MYSQL_CONNECTOR_CLASS));
    }

    @Test
    @DisplayName("isPostgresConnector - returns false for MariaDbConnector class")
    void testIsPostgresConnectorFalseForMariaDb() {
        assertFalse(DDLParserFactory.isPostgresConnector(DDLParserFactory.MARIADB_CONNECTOR_CLASS));
    }

    @Test
    @DisplayName("isPostgresConnector - returns false for null")
    void testIsPostgresConnectorFalseForNull() {
        assertFalse(DDLParserFactory.isPostgresConnector(null));
    }

    @Test
    @DisplayName("isPostgresConnector - returns false for empty string")
    void testIsPostgresConnectorFalseForEmpty() {
        assertFalse(DDLParserFactory.isPostgresConnector(""));
    }

    @Test
    @DisplayName("isPostgresConnector - returns true for any string containing 'postgres' (case-insensitive)")
    void testIsPostgresConnectorCaseInsensitive() {
        assertTrue(DDLParserFactory.isPostgresConnector("io.debezium.connector.POSTGRESQL.SomeConnector"));
        assertTrue(DDLParserFactory.isPostgresConnector("com.example.PostgresConnector"));
        assertTrue(DDLParserFactory.isPostgresConnector("POSTGRES"));
    }

    // -----------------------------------------------------------------------
    // Constant value tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POSTGRES_CONNECTOR_CLASS constant has expected value")
    void testPostgresConnectorClassConstant() {
        assertEquals("io.debezium.connector.postgresql.PostgresConnector",
                DDLParserFactory.POSTGRES_CONNECTOR_CLASS);
    }

    @Test
    @DisplayName("MYSQL_CONNECTOR_CLASS constant has expected value")
    void testMySQLConnectorClassConstant() {
        assertEquals("io.debezium.connector.mysql.MySqlConnector",
                DDLParserFactory.MYSQL_CONNECTOR_CLASS);
    }

    @Test
    @DisplayName("MARIADB_CONNECTOR_CLASS constant has expected value")
    void testMariaDbConnectorClassConstant() {
        assertEquals("io.debezium.connector.mariadb.MariaDbConnector",
                DDLParserFactory.MARIADB_CONNECTOR_CLASS);
    }
}

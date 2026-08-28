package com.altinity.clickhouse.debezium.embedded.cdc;

import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for issue #1288 -- the offset storage statements were built
 * with String.format, so a single quote anywhere in the connector name broke
 * out of the SQL string literal.
 *
 * <p>The connector name is operator- and, in the REST-API deployment model,
 * request-supplied: DebeziumEmbeddedRestApi accepts a configuration payload
 * and its "name" value flows straight into getOffsetKey(), which wraps it in
 * a JSON string that was then interpolated into DELETE and SELECT statements.
 *
 * <p>Assertions are made against the statement text that reaches the JDBC
 * driver, captured through a recording Connection. That is the boundary the
 * defect lives at, so it can be proven without a live ClickHouse.
 */
public class DebeziumOffsetStorageSqlInjectionTest {

    /**
     * A connector name that closes the SQL string literal and appends a
     * second statement. Plausible enough to be a mistake, hostile enough to
     * be an attack.
     */
    private static final String HOSTILE_NAME =
            "engine'; drop table altinity_sink_connector.replica_source_info; --";

    private static final String OFFSET_TABLE = "altinity_sink_connector.replica_source_info";

    private static final String OFFSET_TABLE_KEY =
            JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX
                    + JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name();

    private static Properties propsWithName(String connectorName) {
        Properties props = new Properties();
        props.setProperty(OFFSET_TABLE_KEY, OFFSET_TABLE);
        props.setProperty("name", connectorName);
        return props;
    }

    @Test
    @DisplayName("#1288 delete statement binds the offset key instead of interpolating it")
    void deleteOffsetStorageRow_bindsOffsetKey() throws SQLException {
        Recorder recorder = new Recorder();
        Properties props = propsWithName(HOSTILE_NAME);
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();

        storage.deleteOffsetStorageRow(storage.getOffsetKey(props), props,
                recorder.connection());

        assertEquals(1, recorder.statements.size(), "exactly one statement prepared");
        RecordedStatement stmt = recorder.statements.get(0);

        assertNoInjection(stmt.sql, HOSTILE_NAME);
        assertTrue(stmt.sql.contains("offset_key=?"),
                "offset key must be a bind parameter, was: " + stmt.sql);
        assertEquals(1, stmt.boundValues.size(),
                "the offset key is the single bound value");
        assertTrue(stmt.boundValues.get(0).contains(HOSTILE_NAME),
                "the hostile name must survive intact as DATA, was: "
                        + stmt.boundValues.get(0));
    }

    @Test
    @DisplayName("#1288 select statement binds the offset key instead of interpolating it")
    void getDebeziumStorageStatusQuery_bindsOffsetKey() throws SQLException {
        Recorder recorder = new Recorder();
        Properties props = propsWithName(HOSTILE_NAME);

        new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(
                props, recorder.connection());

        assertEquals(1, recorder.statements.size(), "exactly one statement prepared");
        RecordedStatement stmt = recorder.statements.get(0);

        assertNoInjection(stmt.sql, HOSTILE_NAME);
        assertTrue(stmt.sql.contains("offset_key=?"),
                "offset key must be a bind parameter, was: " + stmt.sql);
        assertEquals(1, stmt.boundValues.size());
        assertTrue(stmt.boundValues.get(0).contains(HOSTILE_NAME));
    }

    @Test
    @DisplayName("#1288 schema history delete binds the server name instead of interpolating it")
    void deleteSchemaHistoryTable_bindsServerName() throws SQLException {
        Recorder recorder = new Recorder();
        Properties props = propsWithName("engine");

        new DebeziumOffsetStorage().deleteSchemaHistoryTable(
                HOSTILE_NAME,
                "altinity_sink_connector.replicate_schema_history",
                recorder.connection(), props);

        assertEquals(1, recorder.statements.size());
        RecordedStatement stmt = recorder.statements.get(0);

        assertNoInjection(stmt.sql, HOSTILE_NAME);
        assertEquals(1, stmt.boundValues.size());
        assertEquals(HOSTILE_NAME, stmt.boundValues.get(0));
    }

    @Test
    @DisplayName("#1288 a qualified table name is quoted per part, not as one identifier")
    void quoteTableName_quotesEachPartSeparately() {
        // Whole-string quoting would produce
        // `altinity_sink_connector.replica_source_info`, a single identifier
        // naming a non-existent table in the connection's default database.
        assertEquals("`altinity_sink_connector`.`replica_source_info`",
                DebeziumOffsetStorage.quoteTableName(OFFSET_TABLE));
        assertEquals("`replica_source_info`",
                DebeziumOffsetStorage.quoteTableName("replica_source_info"));
        // An operator who already quoted the name in the config file must not
        // end up with a doubly quoted identifier.
        assertEquals("`altinity_sink_connector`.`replica_source_info`",
                DebeziumOffsetStorage.quoteTableName(
                        "\"altinity_sink_connector\".\"replica_source_info\""));
        // A backtick inside the name is escaped by doubling, so it cannot
        // terminate the quoted identifier either.
        assertEquals("`we``ird`",
                DebeziumOffsetStorage.quoteTableName("we`ird"));
    }

    /**
     * Control: an ordinary connector name must still produce exactly the
     * statement the connector has always sent, carrying the same offset key,
     * so the fix cannot be passing by breaking normal operation.
     */
    @Test
    @DisplayName("#1288 control -- an ordinary connector name is unaffected")
    void ordinaryConnectorName_producesExpectedStatement() throws SQLException {
        Recorder recorder = new Recorder();
        Properties props = propsWithName("engine");
        DebeziumOffsetStorage storage = new DebeziumOffsetStorage();

        storage.deleteOffsetStorageRow(storage.getOffsetKey(props), props,
                recorder.connection());

        RecordedStatement stmt = recorder.statements.get(0);
        assertEquals("delete from `altinity_sink_connector`."
                        + "`replica_source_info` where offset_key=?",
                stmt.sql);
        assertEquals("[\"engine\",{\"server\":\"embeddedconnector\"}]",
                stmt.boundValues.get(0));
    }

    /**
     * Control: the INSERT path was already parameterized and must stay so,
     * while now also quoting the table identifier per part.
     */
    @Test
    @DisplayName("#1288 control -- the insert path stays parameterized and quotes the table")
    void updateDebeziumStorageRow_staysParameterized() throws SQLException {
        Recorder recorder = new Recorder();

        new DebeziumOffsetStorage().updateDebeziumStorageRow(
                recorder.connection(), OFFSET_TABLE,
                "[\"engine\",{\"server\":\"embeddedconnector\"}]",
                "{\"lsn\":1}", 1700000000000L);

        RecordedStatement stmt = recorder.statements.get(0);
        assertTrue(stmt.sql.startsWith("INSERT INTO `altinity_sink_connector`."
                        + "`replica_source_info`("),
                "table identifier must be quoted per part, was: " + stmt.sql);
        assertTrue(stmt.sql.contains("VALUES ( ?, ?, ?, ?, ? )"),
                "values stay bound, was: " + stmt.sql);
    }

    /**
     * Fails if the rendered statement shows any sign of the injected payload
     * having been treated as SQL rather than as data.
     *
     * <p>The check is that the payload appears nowhere in the statement text
     * and that the comparison is made against a bind marker. It deliberately
     * does not forbid single quotes outright: the schema history statement
     * legitimately contains the constant literals {@code 'source'} and
     * {@code 'server'} as arguments to JSONExtractRaw, which are part of the
     * query the connector author wrote, not values flowing in from
     * configuration.
     *
     * @param sql     the statement text handed to the driver.
     * @param payload the hostile value that must not appear in that text.
     */
    private static void assertNoInjection(String sql, String payload) {
        assertFalse(sql.contains("drop table"),
                "injected DDL reached the driver as SQL: " + sql);
        assertFalse(sql.contains("--"),
                "injected comment reached the driver as SQL: " + sql);
        assertFalse(sql.contains(payload),
                "the payload must not appear in the statement text: " + sql);
        assertTrue(sql.contains("=?"),
                "the value must be compared against a bind marker, was: " + sql);
    }

    /**
     * One captured prepared statement: its text and the values bound to it.
     */
    static final class RecordedStatement {
        private final String sql;
        private final List<String> boundValues = new ArrayList<>();

        RecordedStatement(String sql) {
            this.sql = sql;
        }
    }

    /**
     * Captures every statement prepared on a Connection, and every value
     * bound to those statements.
     *
     * <p>Implemented with a dynamic proxy rather than a hand-written stub:
     * java.sql.Connection and PreparedStatement declare well over a hundred
     * methods between them, and the module has no mocking framework on the
     * test classpath. The proxy answers only what the code under test
     * actually calls and returns type-appropriate defaults elsewhere.
     */
    static final class Recorder implements InvocationHandler {

        private final List<RecordedStatement> statements = new ArrayList<>();

        Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("prepareStatement".equals(method.getName())) {
                RecordedStatement recorded =
                        new RecordedStatement((String) args[0]);
                statements.add(recorded);
                return statementProxy(recorded);
            }
            return defaultValue(method.getReturnType());
        }

        private PreparedStatement statementProxy(RecordedStatement recorded) {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> {
                        if ("setString".equals(method.getName())) {
                            int index = (Integer) args[0];
                            while (recorded.boundValues.size() < index) {
                                recorded.boundValues.add(null);
                            }
                            recorded.boundValues.set(index - 1,
                                    (String) args[1]);
                            return null;
                        }
                        if ("executeUpdate".equals(method.getName())) {
                            return 1;
                        }
                        // execute() returning false means "no result set",
                        // which is what a DELETE produces.
                        return defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == void.class) {
                return null;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == char.class) {
                return (char) 0;
            }
            return 0;
        }
    }
}

package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.db.ColumnMetadataUnavailableException;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.DbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A column-metadata read that is refused must not produce a writer that is
 * then cached (Spec 08.06 section 3.5). The writer built without its column
 * map, engine and sorting key would otherwise be served for the rest of the
 * table version. These tests need no ClickHouse: the connection refuses every
 * statement with ClickHouse code 202, and the attempts are counted.
 */
public class FailedMetadataBuildNotCachedTest {

    private static final Schema ROW = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .build();

    /** A connection whose every statement is refused, counting the statements opened. */
    private static final class RefusingConnection {
        int statements = 0;

        Connection connection() {
            InvocationHandler statement = (proxy, method, args) -> {
                if ("createStatement".equals(method.getName())) {
                    statements++;
                    throw new SQLException("Code: 202. DB::Exception: Too many simultaneous queries");
                }
                return null;
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, statement);
        }
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.server.url", "127.0.0.1");
        props.put("clickhouse.server.port", "1");
        props.put("clickhouse.server.user", "default");
        props.put("clickhouse.server.password", "");
        props.put("clickhouse.server.database", "testdb");
        props.put("auto.create.tables", "true");
        props.put("connection.pool.disable", "true");
        props.put("errors.max.retries", "0");
        props.put("database.connectionTimeZone", "UTC");
        props.put("clickhouse.datetime.timezone", "UTC");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct record() {
        ClickHouseStruct chStruct = new ClickHouseStruct(1L, "SERVER5432.testdb.orders", null, 0,
                System.currentTimeMillis(), null, new Struct(ROW).put("id", 1), null,
                ClickHouseConverter.CDC_OPERATION.CREATE);
        chStruct.setDatabase("testdb");
        return chStruct;
    }

    @BeforeEach
    public void setUp() {
        DBMetadata.setMaxRetries(1);
        HikariDbSource.close();
        CacheInvalidationManager.getInstance().clearAll();
    }

    @AfterEach
    public void tearDown() {
        DBMetadata.setMaxRetries(10);
        HikariDbSource.close();
        CacheInvalidationManager.getInstance().clearAll();
    }

    @Test
    @DisplayName("a writer whose column read is refused is not returned: the constructor raises")
    public void constructorRaisesUnreadableColumnList() {
        RefusingConnection refusing = new RefusingConnection();

        assertThrows(ColumnMetadataUnavailableException.class,
                () -> new DbWriter("127.0.0.1", 1, "testdb", "orders", "default", "",
                        config(), record(), refusing.connection()),
                "a half-built writer must not be handed back to the caller");
        assertTrue(refusing.statements > 0, "the column read was attempted");
    }

    @Test
    @DisplayName("a failed first build is not cached: the next lookup reads ClickHouse again")
    public void failedBuildIsNotServedFromTheCache() {
        ClickHouseBatchRunnable runnable =
                new ClickHouseBatchRunnable(new LinkedBlockingQueue<>(), config(), new HashMap<>());
        RefusingConnection refusing = new RefusingConnection();

        assertThrows(ColumnMetadataUnavailableException.class,
                () -> runnable.getDbWriterForTable("SERVER5432.testdb.orders", "orders", "testdb",
                        record(), refusing.connection()));
        int afterFirstLookup = refusing.statements;

        assertThrows(ColumnMetadataUnavailableException.class,
                () -> runnable.getDbWriterForTable("SERVER5432.testdb.orders", "orders", "testdb",
                        record(), refusing.connection()),
                "the second lookup must build again, not return a writer cached from the failed build");
        assertTrue(refusing.statements > afterFirstLookup,
                "the second lookup read ClickHouse again instead of being served a cached writer");
    }
}

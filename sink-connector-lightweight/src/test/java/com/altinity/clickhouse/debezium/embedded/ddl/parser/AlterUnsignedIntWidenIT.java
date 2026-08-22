package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;

/**
 * Reproduces UInt64 replication issue when MySQL widens unsigned integer columns:
 * SMALLINT UNSIGNED -> BIGINT UNSIGNED (and INT UNSIGNED -> BIGINT UNSIGNED).
 * <p>
 * Production symptom: ClickHouse column becomes UInt64 after ALTER, but values above
 * the original type range are truncated if Debezium schema history still uses jdbcType 5.
 */
@Testcontainers
@DisplayName("ALTER TABLE widen unsigned integers (SMALLINT/INT UNSIGNED -> BIGINT UNSIGNED)")
public class AlterUnsignedIntWidenIT extends DDLBaseIT {

    private static final String TABLE = "unsigned_int_widen_test";

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees")
                .withUsername("root")
                .withPassword("adminpass")
                .withInitScript("alter_unsigned_int_widen.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    @Test
    @DisplayName("MODIFY pos_agg_id SMALLINT UNSIGNED to BIGINT UNSIGNED preserves values above 65535")
    public void testUnsignedIntWidenAfterAlter() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(25000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        Map<String, String> columnsBeforeAlter = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), TABLE, "employees");

        assertColumnType(columnsBeforeAlter, "pos_agg_id", "UInt16");
        assertColumnType(columnsBeforeAlter, "counter", "UInt32");

        assertRowValue(writer, "row_before_alter_1", "100", "1");
        assertRowValue(writer, "row_before_alter_2", "65535", "2");

        Connection conn = connectToMySQL();
        conn.prepareStatement(
                "ALTER TABLE unsigned_int_widen_test "
                        + "MODIFY COLUMN pos_agg_id BIGINT UNSIGNED NOT NULL"
        ).execute();
        Thread.sleep(15000);

        Map<String, String> columnsAfterPosAggAlter = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), TABLE, "employees");
        assertColumnType(columnsAfterPosAggAlter, "pos_agg_id", "UInt64");

        conn.prepareStatement(
                "INSERT INTO unsigned_int_widen_test (name, pos_agg_id, counter) VALUES "
                        + "('row_after_smallint_to_bigint', 100000, 3)"
        ).execute();
        conn.prepareStatement(
                "INSERT INTO unsigned_int_widen_test (name, pos_agg_id, counter) VALUES "
                        + "('row_after_smallint_to_bigint_max', 4294967296, 4)"
        ).execute();
        Thread.sleep(15000);

        assertRowValue(writer, "row_after_smallint_to_bigint", "100000", "3");
        assertRowValue(writer, "row_after_smallint_to_bigint_max", "4294967296", "4");

        conn.prepareStatement(
                "ALTER TABLE unsigned_int_widen_test "
                        + "MODIFY COLUMN counter BIGINT UNSIGNED NOT NULL DEFAULT 0"
        ).execute();
        Thread.sleep(15000);

        Map<String, String> columnsAfterCounterAlter = dbMetadata.getColumnsDataTypesForTable(
                writer.getConnection(), TABLE, "employees");
        assertColumnType(columnsAfterCounterAlter, "counter", "UInt64");

        conn.prepareStatement(
                "INSERT INTO unsigned_int_widen_test (name, pos_agg_id, counter) VALUES "
                        + "('row_int_to_bigint', 500, 5000000000)"
        ).execute();
        Thread.sleep(15000);

        assertRowValue(writer, "row_int_to_bigint", "500", "5000000000");

        assertSchemaHistoryContainsBigintPosAggId(writer);

        conn.close();
        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    private static void assertColumnType(Map<String, String> columns, String columnName, String expectedType) {
        String actual = columns.get(columnName);
        Assert.assertNotNull(columnName + " column missing in ClickHouse", actual);
        Assert.assertTrue(
                columnName + " expected " + expectedType + " but was " + actual,
                actual.equalsIgnoreCase(expectedType)
                        || actual.equalsIgnoreCase("Nullable(" + expectedType + ")")
        );
    }

    private void assertRowValue(BaseDbWriter writer, String name, String expectedPosAggId, String expectedCounter)
            throws Exception {
        String query = String.format(
                "SELECT pos_agg_id, counter FROM employees.%s FINAL WHERE name = '%s'",
                TABLE, name
        );
        try (ResultSet rs = ITCommon.executeQueryWithResultSet(query, writer.getConnection())) {
            Assert.assertTrue("Row not found in ClickHouse: " + name, rs.next());
            Assert.assertEquals(expectedPosAggId, rs.getString("pos_agg_id"));
            Assert.assertEquals(expectedCounter, rs.getString("counter"));
        }
    }

    /**
     * After ALTER, schema history should record pos_agg_id as BIGINT UNSIGNED (jdbcType -5).
     * If this fails while data assertions pass, Debezium history may be stale but CH DDL is correct.
     */
    private void assertSchemaHistoryContainsBigintPosAggId(BaseDbWriter writer) throws Exception {
        String query = "SELECT history_data FROM altinity_sink_connector.replicate_schema_history FINAL "
                + "WHERE history_data LIKE '%pos_agg_id%' "
                + "AND history_data LIKE '%BIGINT UNSIGNED%' "
                + "ORDER BY record_insert_ts DESC LIMIT 1";
        try (Statement stmt = writer.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            Assert.assertTrue(
                    "Expected schema history entry with pos_agg_id as BIGINT UNSIGNED after ALTER",
                    rs.next()
            );
        }
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;

/**
 * End-to-end regression coverage for issue #1262: a MySQL DOUBLE column was
 * auto-created in ClickHouse as Float32, silently truncating every replicated
 * value from ~15 significant decimal digits to ~7.
 *
 * <p>{@code ClickHouseDataTypeMapper.dataTypesMap} mapped BOTH FLOAT32 and
 * FLOAT64 to {@code ClickHouseDataType.Float32}. That map is what
 * {@code ClickHouseTableOperationsBase.getColumnNameToCHDataTypeMapping} uses to
 * pick the column type in the CREATE TABLE statement emitted by
 * {@code ClickHouseAutoCreateTable.createNewTable}, so the destination column
 * was born too narrow. No error was logged and the row counts still matched, so
 * count-based checksum jobs reported the table clean.
 *
 * <p>This test deliberately reads the column type back from the LIVE ClickHouse
 * server via {@code system.columns} and compares the replicated value against
 * the value MySQL holds. The existing auto-create suite missed the defect
 * because {@code ClickHouseAutoCreateTableBase.getExpectedColumnToDataTypesMap}
 * hand-builds its expected map with the CORRECT Float64 entries instead of
 * deriving it from the mapper, so the wrong lookup was never exercised. An
 * assertion against a hand-built map cannot catch a defect in the map itself;
 * only the server can be trusted to say what was actually created.
 */
@Testcontainers
@DisplayName("Test that a MySQL DOUBLE is auto-created as ClickHouse Float64 and round-trips without precision loss")
public class AutoCreateDoubleColumnPrecisionIT {

    /**
     * A MySQL DOUBLE carries ~15-17 significant decimal digits; Float32 keeps
     * ~7. This value differs from its own float round-trip, so it can only
     * survive replication if the destination column is genuinely 64-bit.
     */
    private static final double PRECISE_VALUE = 0.1234567890123456d;

    /**
     * The largest finite DOUBLE. A Float32 column cannot hold it at all -- it
     * overflows to infinity -- so this catches a narrow column even when the
     * comparison tolerance is generous.
     */
    private static final double MAX_VALUE = 1.7976931348623157E308d;

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }

    @AfterEach
    public void stopContainers() {
        if (mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();
        }
        if (clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }
    }

    @DisplayName("Test that a MySQL DOUBLE is auto-created as ClickHouse Float64 and round-trips without precision loss")
    @Test
    public void testDoubleColumnIsAutoCreatedAsFloat64() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "no_data");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");
        props.setProperty("auto.create.tables", "true");
        // DDL replication is switched OFF on purpose. With it on, the CREATE
        // TABLE is translated from the MySQL DDL by the DDL parser, which has
        // its own type table and never consults ClickHouseDataTypeMapper -- the
        // defect would be invisible. Turning it off routes table creation
        // through DbWriter.autoCreateTable -> ClickHouseAutoCreateTable
        // .createNewTable -> getColumnNameToCHDataTypeMapping, which is the
        // consumer of the broken map and the path issue #1262 reports.
        props.setProperty("disable.ddl", "true");

        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication =
                new ClickHouseDebeziumEmbeddedApplication();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(
                        injector.getInstance(DebeziumRecordParserService.class), props, false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(25000);

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `double_precision`(col1 varchar(255) not null, "
                + "amount_double double, amount_float float, primary key(col1))").execute();

        conn.prepareStatement("insert into double_precision values('precise', "
                + PRECISE_VALUE + ", 1.5)").execute();
        conn.prepareStatement("insert into double_precision values('max', "
                + MAX_VALUE + ", 2.5)").execute();

        Thread.sleep(20000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

        // Ask the live server what it actually created. A hand-built expected
        // map would have agreed with itself and missed the defect entirely.
        String actualDoubleType = null;
        String actualFloatType = null;
        ResultSet columnsRs = ITCommon.executeQueryWithResultSet(
                "select name, type from system.columns where database = 'employees' "
                        + "and table = 'double_precision'", writer.getConnection());
        while (columnsRs.next()) {
            String name = columnsRs.getString("name");
            if ("amount_double".equals(name)) {
                actualDoubleType = columnsRs.getString("type");
            } else if ("amount_float".equals(name)) {
                actualFloatType = columnsRs.getString("type");
            }
        }

        Assert.assertNotNull("the table was never auto-created in ClickHouse", actualDoubleType);

        // Nullable(Float64) is expected; the assertion is on the underlying
        // type so a change to column nullability does not break the test.
        Assert.assertTrue("MySQL DOUBLE must be auto-created as a 64-bit ClickHouse column, "
                        + "otherwise every replicated value is truncated from ~15 significant "
                        + "decimal digits to ~7 with no error logged. Server reported: "
                        + actualDoubleType,
                actualDoubleType.contains("Float64"));
        Assert.assertFalse("MySQL DOUBLE must not be auto-created as Float32. Server reported: "
                        + actualDoubleType,
                actualDoubleType.contains("Float32"));

        // The MySQL FLOAT column is present as a control: Debezium's MySQL
        // connector emits FLOAT as a FLOAT64 Kafka Connect schema too (it widens
        // single precision at the source), so this column is EXPECTED to be
        // Float64 as well. It is asserted only to prove the auto-create actually
        // ran over both columns. The FLOAT32 -> Float32 half of the mapping is a
        // mapper-level property with no MySQL DDL that reaches it, so it is
        // pinned by ClickHouseDataTypeMapperFloat64Test rather than here.
        Assert.assertNotNull("the float column was never auto-created", actualFloatType);

        // The type is only half the story -- prove the value itself survived.
        // Compared with delta 0.0 because a Float64 column must reproduce the
        // source DOUBLE bit-exactly, not merely approximately.
        double replicatedPrecise = selectDouble(writer.getConnection(), "precise");
        Assert.assertEquals("a high-precision MySQL DOUBLE must round-trip byte-exactly; "
                        + "a Float32 destination column silently rounds it",
                PRECISE_VALUE, replicatedPrecise, 0.0d);

        double replicatedMax = selectDouble(writer.getConnection(), "max");
        Assert.assertFalse("the largest finite MySQL DOUBLE must not overflow to infinity, "
                        + "which is what a Float32 destination column does to it",
                Double.isInfinite(replicatedMax));
        Assert.assertEquals("the largest finite MySQL DOUBLE must round-trip byte-exactly",
                MAX_VALUE, replicatedMax, 0.0d);

        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().engine.close();

        conn.close();
        executorService.shutdown();

        HikariDbSource.close();
    }

    /** Reads one replicated DOUBLE back from ClickHouse. */
    private static double selectDouble(Connection chConn, String key) throws Exception {
        double value = Double.NaN;
        ResultSet rs = ITCommon.executeQueryWithResultSet(
                "select amount_double from employees.double_precision final where col1 = '"
                        + key + "'", chConn);
        while (rs.next()) {
            value = rs.getDouble("amount_double");
        }
        Assert.assertFalse("no row replicated to ClickHouse for key '" + key + "'",
                Double.isNaN(value));
        return value;
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.AppInjector;
import com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication;
import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.api.DebeziumEmbeddedRestApi;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLBaseIT;
import com.altinity.clickhouse.debezium.embedded.ddl.parser.DDLParserService;
import com.altinity.clickhouse.debezium.embedded.parser.DebeziumRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.model.DBCredentials;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.common.collect.Maps;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.debezium.storage.jdbc.offset.JdbcOffsetBackingStoreConfig;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.getDebeziumProperties;
import static org.junit.Assert.assertTrue;

import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Testcontainers
public class DebeziumChangeEventCaptureIT{

    private static final Logger log = LoggerFactory.getLogger(DebeziumChangeEventCaptureIT.class);
    @Test
    public void testDeleteOffsetStorageRow2()  {
        //System.out.println("Delete offset");
        DebeziumChangeEventCapture dec = new DebeziumChangeEventCapture();
        try {
            Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
            props.setProperty("name", "altinity_sink_connector");
            Map<String, String> propertiesMap = Maps.newHashMap(Maps.fromProperties(props));
            ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(propertiesMap);
            String tableName = props.getProperty(JdbcOffsetBackingStoreConfig.OFFSET_STORAGE_PREFIX +
                    JdbcOffsetBackingStoreConfig.PROP_TABLE_NAME.name());
            DBCredentials dbCredentials = dec.parseDBConfiguration(config);

            String jdbcUrl = BaseDbWriter.getConnectionString(dbCredentials.getHostName(), dbCredentials.getPort(),
                    dbCredentials.getDatabase());
            ClickHouseConnection connection = BaseDbWriter.createConnection(jdbcUrl, "Client_1", dbCredentials.getUserName(),
                    dbCredentials.getPassword(), config);

            BaseDbWriter writer = new BaseDbWriter(dbCredentials.getHostName(), dbCredentials.getPort(),
                    dbCredentials.getDatabase(), dbCredentials.getUserName(),
                    dbCredentials.getPassword(), config, connection);
            String offsetValue = new DebeziumOffsetStorage().getDebeziumStorageStatusQuery(props, writer);
            //String offsetKey = new DebeziumOffsetStorage().getOffsetKey(props);

            String updateOffsetValue = new DebeziumOffsetStorage().updateBinLogInformation(offsetValue, "mysql-bin.001", "2333", null);

            //new DebeziumOffsetStorage().deleteOffsetStorageRow(offsetKey, props, writer);
            //new DebeziumOffsetStorage().updateDebeziumStorageRow(writer, tableName, offsetKey, updateOffsetValue, System.currentTimeMillis());

            System.out.print("Test");
        } catch(Exception e) {
            log.error("Exception in testDeleteOffsetStorageRow2", e);
        }
    }

    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_schema_only_column_timezone.sql")
            //   .withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);
    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
//                .withInitScript("15k_tables_mysql.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(35000);
    }

    @Test
    @DisplayName("Test that validates that the sequence number that is created in non-gtid mode is incremented correctly.")
    public void testIncrementingSequenceNumbers() throws Exception {

        Injector injector = Guice.createInjector(new AppInjector());

        Properties props = getDebeziumProperties(mySqlContainer, clickHouseContainer);
        props.setProperty("snapshot.mode", "schema_only");
        props.setProperty("schema.history.internal.store.only.captured.tables.ddl", "true");
        props.setProperty("schema.history.internal.store.only.captured.databases.ddl", "true");

        // Override clickhouse server timezone.
        ClickHouseDebeziumEmbeddedApplication clickHouseDebeziumEmbeddedApplication = new ClickHouseDebeziumEmbeddedApplication();


        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                clickHouseDebeziumEmbeddedApplication.start(injector.getInstance(DebeziumRecordParserService.class),
                        injector.getInstance(DDLParserService.class), props, false);
                DebeziumEmbeddedRestApi.startRestApi(props, injector, clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture()
                        , new Properties());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });

        Thread.sleep(25000);

        // Using MySQL.
        // 1. Insert multiple records.
        // Get connection to MySQL.

        Connection conn = ITCommon.connectToMySQL(mySqlContainer);
        conn.prepareStatement("create table `newtable`(col1 varchar(255) not null, col2 int, col3 int, primary key(col1))").execute();

        // Insert multiple rows.
        conn.prepareStatement("insert into newtable values('a', 1, 1)").execute();
        conn.prepareStatement("insert into newtable values('b', 2, 2)").execute();
        conn.prepareStatement("insert into newtable values('c', 3, 3)").execute();
        conn.prepareStatement("insert into newtable values('d', 4, 4)").execute();

        Thread.sleep(20000);

        // Create connection to ClickHouse and get the version numbers.
        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
                clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

        long version1 = 1L;
        long version2 = 1L;
        long version3 = 1L;
        long version4 = 1L;

        ResultSet version1Result = writer.executeQueryWithResultSet("select _version from newtable final where col1 = 'a'");
        while(version1Result.next()) {
            version1 = version1Result.getLong("_version");
        }

        ResultSet version2Result = writer.executeQueryWithResultSet("select _version from newtable final where col1 = 'b'");
        while(version2Result.next()) {
            version2 = version2Result.getLong("_version");
        }

        ResultSet version3Result = writer.executeQueryWithResultSet("select _version from newtable final where col1 = 'c'");
        while(version3Result.next()) {
            version3 = version3Result.getLong("_version");
        }

        ResultSet version4Result = writer.executeQueryWithResultSet("select _version from newtable final where col1 = 'd'");
        while(version4Result.next()) {
            version4 = version4Result.getLong("_version");
        }
        System.out.println("Version 1" + version1);
        System.out.println("Version 2" + version2);
        System.out.println("Version 3" + version3);
        System.out.println("Version 4" + version4);


        // Check if version 4 is greater than version 3
        assertTrue(version4 > version3);
        // Check if version 3 is greater than version 2
        assertTrue(version3 > version2);
        // Check if version 2 is greater than version 1
        assertTrue(version2 > version1);

        clickHouseDebeziumEmbeddedApplication.getDebeziumEventCapture().engine.close();
        conn.close();
        executorService.shutdown();
    }

}

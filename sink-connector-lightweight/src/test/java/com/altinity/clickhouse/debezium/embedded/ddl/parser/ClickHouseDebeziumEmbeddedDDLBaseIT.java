package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import com.altinity.clickhouse.debezium.embedded.config.EnvironmentConfigurationService;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

@Testcontainers
public class ClickHouseDebeziumEmbeddedDDLBaseIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("init_clickhouse.sql")
            .withExposedPorts(8123);


    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        Thread.sleep(15000);
    }

    @AfterEach
    public void stopContainers() {
        if(mySqlContainer != null && mySqlContainer.isRunning()) {
            mySqlContainer.stop();;
        }
        if(clickHouseContainer != null && clickHouseContainer.isRunning()) {
            clickHouseContainer.stop();
        }

    }

    Connection connectToMySQL() {
        Connection conn = null;
        try {

            String connectionUrl = String.format("jdbc:mysql://%s:%s/%s?user=%s&password=%s", mySqlContainer.getHost(), mySqlContainer.getFirstMappedPort(),
                    mySqlContainer.getDatabaseName(), mySqlContainer.getUsername(), mySqlContainer.getPassword());
            conn = DriverManager.getConnection(connectionUrl);


        } catch (SQLException ex) {
            // handle any errors

        }

        return conn;
    }

    protected Properties getDebeziumProperties() throws Exception {

        Properties props = new ConfigLoader().load("config.yml");
        //Properties props = getDebeziumProperties();
        props.setProperty("database.hostname", mySqlContainer.getHost());
        props.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        props.setProperty("database.include.list", "employees");
        props.setProperty("clickhouse.server.database", "employees");
        props.setProperty("offset.storage.jdbc.url", clickHouseContainer.getJdbcUrl());
        props.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        props.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        props.setProperty("schema.history.internal.jdbc.url", clickHouseContainer.getJdbcUrl());
        props.setProperty("snapshot.mode", "initial");

//        Properties fileProps = new ConfigLoader().load("config.yml");
//        fileProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
//        fileProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
//        fileProps.setProperty("database.password", "adminpass");
//
//        fileProps.setProperty("database.hostname", mySqlContainer.getHost());
//        fileProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));

        return props;

        // Start the debezium embedded application.
//        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
//        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
//        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
//        defaultProps.setProperty("database.user", "root");
//        defaultProps.setProperty("database.password", "adminpass");
//
//        defaultProps.setProperty("database.include.list", "employees");
//        defaultProps.setProperty("snapshot.mode", "initial");
//
//
//        defaultProps.setProperty("snapshot.mode", "initial");
//        defaultProps.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");
//
//        defaultProps.setProperty("include.schema.change", "true");
//        defaultProps.setProperty("include.schema.comments", "true");
//
//        //defaultProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
//        defaultProps.setProperty("provide.transaction.metadata", "true");
//        //String tempOffsetPath = "/tmp/2/offsets" + System.currentTimeMillis() + ".dat";
//        Path tmpFilePath = Files.createTempFile("offsets", ".dat");
//        Files.deleteIfExists(tmpFilePath);
//        //defaultProps.setProperty("offset.storage.file.filename", tmpFilePath.toAbsolutePath().toString());
//        //defaultProps.setProperty("offset.flush.interval.ms", "60000");
//
//        defaultProps.setProperty("auto.create.tables", "true");
//        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
//        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
//        defaultProps.setProperty("clickhouse.server.user", "default");
//        defaultProps.setProperty("clickhouse.server.pass", "");
//        defaultProps.setProperty("clickhouse.server.database", "employees");
//        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
//        defaultProps.setProperty("metrics.port", "8088");
//        defaultProps.setProperty("thread.pool.size", "1");
//        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
//        defaultProps.setProperty("metrics.enable", "false");
//
//        defaultProps.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
//        defaultProps.setProperty("offset.storage.offset.storage.jdbc.offset.table.name", "altinity_sink_connector.replica_source_info");
//        defaultProps.setProperty("offset.storage.jdbc.url", "jdbc:clickhouse://clickhouse:8123");
//        defaultProps.setProperty("offset.storage.jdbc.user", "root");
//        defaultProps.setProperty("offset.storage.jdbc.password", "root");
//        defaultProps.setProperty("offset.storage.offset.storage.jdbc.offset.table.ddl", "CREATE TABLE if not exists %s\n" +
//                "(\n" +
//                "    `id` String,\n" +
//                "    `offset_key` String,\n" +
//                "    `offset_val` String,\n" +
//                "    `record_insert_ts` DateTime,\n" +
//                "    `record_insert_seq` UInt64,\n" +
//                "    `_version` UInt64 MATERIALIZED toUnixTimestamp64Nano(now64(9))\n" +
//                ")\n" +
//                "ENGINE = ReplacingMergeTree(_version)\n" +
//                "ORDER BY id\n" +
//                "SETTINGS index_granularity = 8198");
//        defaultProps.setProperty("offset.storage.offset.storage.jdbc.offset.table.delete", "delete from %s where 1=1");
//        defaultProps.setProperty("schema.history.internal", "io.debezium.storage.jdbc.history.JdbcSchemaHistory");
//        defaultProps.setProperty("schema.history.internal.jdbc.url", "jdbc:clickhouse://clickhouse:8123" );
//        defaultProps.setProperty("schema.history.internal.jdbc.user", "root");
//        defaultProps.setProperty("schema.history.internal.jdbc.password", "root");
//        defaultProps.setProperty("schema.history.internal.jdbc.schema.history.table.ddl", "CREATE TABLE if not exists %s (`id` VARCHAR(36) NOT NULL, `history_data` VARCHAR(65000), `history_data_seq` INTEGER, `record_insert_ts` TIMESTAMP NOT NULL, `record_insert_seq` INTEGER NOT NULL) ENGINE=ReplacingMergeTree(record_insert_seq) order by id");
//        defaultProps.setProperty("schema.history.internal.jdbc.schema.history.table.name", "altinity_sink_connector.replicate_schema_history");
//
//        return defaultProps;
    }
}

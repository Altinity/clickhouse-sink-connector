package com.altinity.clickhouse.debezium.embedded.ddl.parser;

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
//        if(mySqlContainer != null && mySqlContainer.isRunning()) {
//            mySqlContainer.stop();;
//        }
//        if(clickHouseContainer != null && clickHouseContainer.isRunning()) {
//            clickHouseContainer.stop();
//        }
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

        // Start the debezium embedded application.
        Properties defaultProps = (new EnvironmentConfigurationService()).parse();
        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("database.include.list", "employees");
        defaultProps.setProperty("snapshot.mode", "initial");


        defaultProps.setProperty("snapshot.mode", "initial");
        defaultProps.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");

        defaultProps.setProperty("include.schema.change", "true");
        defaultProps.setProperty("include.schema.comments", "true");

        defaultProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.FileOffsetBackingStore");
        defaultProps.setProperty("provide.transaction.metadata", "true");
        //String tempOffsetPath = "/tmp/2/offsets" + System.currentTimeMillis() + ".dat";
        Path tmpFilePath = Files.createTempFile("offsets", ".dat");
        Files.deleteIfExists(tmpFilePath);
        defaultProps.setProperty("offset.storage.file.filename", tmpFilePath.toString());
        defaultProps.setProperty("offset.flush.interval.ms", "60000");

        defaultProps.setProperty("auto.create.tables", "true");
        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", "default");
        defaultProps.setProperty("clickhouse.server.pass", "");
        defaultProps.setProperty("clickhouse.server.database", "employees");
        defaultProps.setProperty("replacingmergetree.delete.column", "_sign");
        defaultProps.setProperty("metrics.port", "8088");
        defaultProps.setProperty("thread.pool.size", "1");
        defaultProps.setProperty("database.allowPublicKeyRetrieval", "true");
        defaultProps.setProperty("metrics.enable", "false");

        return defaultProps;
    }
}

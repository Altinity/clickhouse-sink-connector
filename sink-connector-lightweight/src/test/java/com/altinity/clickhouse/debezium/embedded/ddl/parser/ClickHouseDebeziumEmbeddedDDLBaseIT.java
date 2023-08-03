package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.common.PropertiesHelper;
import com.altinity.clickhouse.debezium.embedded.config.ConfigLoader;
import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.SelinuxContext;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

@Testcontainers
public class ClickHouseDebeziumEmbeddedDDLBaseIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer
            ("clickhouse/clickhouse-server:latest")
            .withInitScript("init_clickhouse_it.sql")
            .withExposedPorts(8123);
            //.withClasspathResourceMapping("users.xml", "/etc/clickhouse-server/users.xml", BindMode.READ_WRITE,
            //        SelinuxContext.SHARED) ;
            //.withEnv("CLICKHOUSE_USER", "default")
            //.withEnv("CLICKHOUSE_PASSWORD", "root")
            //.withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "0 ");
//            .withFileSystemBind("src/test/resources/users.xml",
//                    "/etc/clickhouse-server/users.xml", BindMode.READ_WRITE);

    //        .withCopyFileToContainer(MountableFile.forClasspathResource("users.xml"),
      //              "/etc/clickhouse-server/users.xml");

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

        // Start the debezium embedded application.

        Properties defaultProps = new Properties();
        Properties defaultProperties = PropertiesHelper.getProperties("config.properties");

        defaultProps.putAll(defaultProperties);
        Properties fileProps = new ConfigLoader().load("config.yml");
        defaultProps.putAll(fileProps);

        defaultProps.setProperty("database.hostname", mySqlContainer.getHost());
        defaultProps.setProperty("database.port", String.valueOf(mySqlContainer.getFirstMappedPort()));
        defaultProps.setProperty("database.user", "root");
        defaultProps.setProperty("database.password", "adminpass");

        defaultProps.setProperty("clickhouse.server.url", clickHouseContainer.getHost());
        defaultProps.setProperty("clickhouse.server.port", String.valueOf(clickHouseContainer.getFirstMappedPort()));
        defaultProps.setProperty("clickhouse.server.user", "default");
        defaultProps.setProperty("clickhouse.server.pass", "");
        defaultProps.setProperty("clickhouse.server.database", "employees");

        defaultProps.setProperty("offset.storage.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));

        defaultProps.setProperty("schema.history.internal.jdbc.url", String.format("jdbc:clickhouse://%s:%s",
                clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort()));


        return defaultProps;

    }
}

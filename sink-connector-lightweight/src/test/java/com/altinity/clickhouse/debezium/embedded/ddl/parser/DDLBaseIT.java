package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;


import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.util.Properties;


import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
@Testcontainers
public class DDLBaseIT {
    protected MySQLContainer mySqlContainer;

    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123)
            // Explicit readiness probe with a generous startup timeout. Under
            // CI load the default timeout is occasionally too short and the
            // container is not yet started when a test calls getMappedPort,
            // which surfaces as the intermittent
            // "Mapped port can only be obtained after the container is started"
            // IllegalStateException. Waiting on ClickHouse's own /ping endpoint
            // (returns 200 "Ok.") and allowing up to 5 minutes makes container
            // start reliable on slow runners.
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                    .forHttp("/ping")
                    .forPort(8123)
                    .forStatusCode(200)
                    .withStartupTimeout(java.time.Duration.ofMinutes(5)));

    /**
     * Starts the shared ClickHouse container if it is not running.
     *
     * <p>{@link #stopContainers()} stops the static {@code clickHouseContainer}
     * after EVERY test, but the Testcontainers extension starts a static
     * {@code @Container} only once per class. In a class with two tests the
     * second one therefore ran against a stopped container and failed with
     * "Mapped port can only be obtained after the container is started" at its
     * first {@code getMappedPort} call (observed on
     * {@code AlterTableModifyColumnIT.testAlterAddPrimaryKeyAndModifyNotNull}).
     * Restarting here gives each test a fresh container (the init script runs
     * again), which is also what the per-test MySQL container provides.</p>
     *
     * <p>Subclasses that override {@link #startContainers()} must call this
     * themselves.</p>
     */
    protected static void ensureClickHouseContainerStarted() {
        if (clickHouseContainer != null && !clickHouseContainer.isRunning()) {
            clickHouseContainer.start();
        }
    }

    @BeforeEach
    public void startContainers() throws InterruptedException {
        ensureClickHouseContainerStarted();
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                // MySQL does not speak HTTP, so an HttpWaitStrategy on 3306 is
                // the wrong readiness probe; wait for the port to accept TCP
                // connections instead (with a generous timeout for slow runners).
                .waitingFor(org.testcontainers.containers.wait.strategy.Wait
                        .forListeningPort()
                        .withStartupTimeout(java.time.Duration.ofMinutes(5)));

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

    protected Connection connectToMySQL() {
        return ITCommon.connectToMySQL(mySqlContainer);
    }

    protected Properties getDebeziumProperties() throws Exception {

        Properties props =  ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);

        props.put(SinkConnectorLightWeightConfig.DDL_RETRY, "true");

        return props;
    }

}

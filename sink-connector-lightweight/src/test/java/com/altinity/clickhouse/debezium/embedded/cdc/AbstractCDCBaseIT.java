package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.log4j.BasicConfigurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;



import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;
@Testcontainers
public class AbstractCDCBaseIT {

        protected static MySQLContainer mySqlContainer;
        protected static ClickHouseContainer clickHouseContainer;
        static {
            clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_it.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

            mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                    .withInitScript("alter_ddl_add_column.sql")
                    .withExtraHost("mysql-server", "0.0.0.0")
                    .waitingFor(new HttpWaitStrategy().forPort(3306));

            BasicConfigurator.configure();
            mySqlContainer.start();
            clickHouseContainer.start();

            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
}


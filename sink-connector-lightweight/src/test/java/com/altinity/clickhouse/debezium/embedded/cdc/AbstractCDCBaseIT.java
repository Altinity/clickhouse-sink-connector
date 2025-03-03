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


@Testcontainers
public class AbstractCDCBaseIT {

        protected static MySQLContainer mySqlContainer;
        protected static ClickHouseContainer clickHouseContainer;
        static {
            clickHouseContainer = new ClickHouseContainer(DockerImageName.parse("clickhouse/clickhouse-server:latest")
                    .asCompatibleSubstituteFor("clickhouse"))
                    .withInitScript("init_clickhouse_it.sql")
                    .withUsername("ch_user")
                    .withPassword("password")
                    .withExposedPorts(8123);

            mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
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

    private AbstractCDCBaseIT() {
    }
}

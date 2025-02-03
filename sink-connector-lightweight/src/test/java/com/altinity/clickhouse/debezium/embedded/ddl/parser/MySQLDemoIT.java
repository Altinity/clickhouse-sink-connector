package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.clickhouse.jdbc.ClickHouseConnection;

public class MySQLDemoIT extends DDLBaseIT {


    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("employees.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testAddColumn() throws Exception {

        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {

                Properties properties = getDebeziumProperties();

                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(),  false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);//

        Connection conn = connectToMySQL();

        conn.prepareStatement("update employees set jobTitle = concat('Senior ', jobTitle);").execute();
        Thread.sleep(10000);

        String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
        "employees");
        ClickHouseConnection chConn = BaseDbWriter.createConnection(jdbcUrl, "Client_1",
        clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), new ClickHouseSinkConnectorConfig(new HashMap<>()));

        BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
        "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, chConn);

        ResultSet rs = conn.prepareStatement("select jobTitle from employees;").executeQuery();
        while(rs.next()) {
            System.out.println(rs.getString(1));
        }
        conn.prepareStatement("select * from employees;").execute();



        // deletion example, 3 tables

        conn.prepareStatement("select count(*) from payments;").execute();
        conn.prepareStatement("select count(*) from customers;").execute();
        conn.prepareStatement("select count(*) from employees;").execute();


        conn.prepareStatement("delete from payments where customerNumber in (select customerNumber from customers where salesRepEmployeeNumber=1621);").execute();
        conn.prepareStatement("delete from customers where salesRepEmployeeNumber=1621;").execute();
        conn.prepareStatement("delete from employees where lastName='Kato';").execute();

        conn.prepareStatement("select count(*) from payments;").execute();
        conn.prepareStatement("select count(*) from customers;").execute();
        conn.prepareStatement("select count(*) from employees;").execute();

        // DDL
        conn.prepareStatement("alter table employees add column hireDate Date not null default (curdate());").execute();
        conn.prepareStatement("alter table employees drop column hireDate;").execute();

        // inserts 
        conn.prepareStatement("insert into employees(employeeNumber,lastName,firstName,extension,email,officeCode,reportsTo,jobTitle) select 2000,lastName,firstName,extension,email,officeCode,reportsTo,jobTitle from employees  where lastName='Gerard';").execute();


        // alter table employees drop  CONSTRAINT `employees_ibfk_2`;
        conn.prepareStatement("alter table employees modify column officeCode int not null;").execute();
        conn.prepareStatement("show create table employees;").execute();

        Thread.sleep(25000);

        if(engine.get() != null) {
            engine.get().stop();
        }
        // Files.deleteIfExists(tmpFilePath);
        executorService.shutdown();



    }

}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Disabled
@Testcontainers
@DisplayName("Integration Test to validate replication of employees database")
public class EmployeesDBIT extends DDLBaseIT {


        @BeforeEach
        @Override
        public void startContainers() throws InterruptedException {
            mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:latest")
                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                    .withInitScript("employees.sql")
                    .withExtraHost("mysql-server", "0.0.0.0")
                    .waitingFor(new HttpWaitStrategy().forPort(3306));

            BasicConfigurator.configure();
            mySqlContainer.start();
            Thread.sleep(15000);
        }

        @Override
        protected Properties getDebeziumProperties() throws Exception {
            Properties baseProps = super.getDebeziumProperties();
            baseProps.put("buffer.max.records", "100");

            return baseProps;
        }

        @Test
        public void testEmployeesDB() throws Exception {
            AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();

            ExecutorService executorService = Executors.newFixedThreadPool(1);
            executorService.execute(() -> {
                try {
                    engine.set(new DebeziumChangeEventCapture());
                    engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),
                            new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>())), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(10000);

            Connection conn = connectToMySQL();
            // alter table ship_class change column class_name class_name_new int;
            // alter table ship_class change column tonange tonange_new decimal(10,10);

            Thread.sleep(40000);


            BaseDbWriter writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                    "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null);


            // Validate that all the tables are created.
            Map<String, String> departmentsColumns = writer.getColumnsDataTypesForTable("departments");
            Assert.assertTrue(departmentsColumns.get("dept_no").equalsIgnoreCase("String"));
            Assert.assertTrue(departmentsColumns.get("dept_name").equalsIgnoreCase("String"));

            Map<String, String> departmentEmpsColumns = writer.getColumnsDataTypesForTable("dept_emp");
            Assert.assertTrue(departmentEmpsColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(departmentEmpsColumns.get("dept_no").equalsIgnoreCase("String"));
            Assert.assertTrue(departmentEmpsColumns.get("from_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(departmentEmpsColumns.get("to_date").equalsIgnoreCase("Date32"));

            Map<String, String> deptManagerColumns = writer.getColumnsDataTypesForTable("dept_manager");

            Map<String, String> employeesColumns = writer.getColumnsDataTypesForTable("employees");
            Assert.assertTrue(employeesColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(employeesColumns.get("birth_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(employeesColumns.get("first_name").equalsIgnoreCase("String"));
            Assert.assertTrue(employeesColumns.get("last_name").equalsIgnoreCase("String"));
            Assert.assertTrue(employeesColumns.get("gender").equalsIgnoreCase("String"));

            Map<String, String> salariesColumns = writer.getColumnsDataTypesForTable("salaries");
            Assert.assertTrue(salariesColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(salariesColumns.get("salary").equalsIgnoreCase("Int32"));
            Assert.assertTrue(salariesColumns.get("from_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(salariesColumns.get("to_date").equalsIgnoreCase("Date32"));

            Map<String, String> titlesColumns = writer.getColumnsDataTypesForTable("titles");
            Assert.assertTrue(titlesColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(titlesColumns.get("title").equalsIgnoreCase("String"));
            Assert.assertTrue(titlesColumns.get("from_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(titlesColumns.get("to_date").equalsIgnoreCase("Nullable(Date32)"));


            int employeesMySqlCount = 0;
            // Check if counts match
            ResultSet rs = conn.prepareStatement("select count(*) from employees").executeQuery();
            while(rs.next()) {
                employeesMySqlCount =  rs.getInt(1);
            }

            int employeesCHCount = 0;

            ResultSet chRs = writer.getConnection().prepareStatement("select count(*) from employees").executeQuery();
            while(chRs.next()) {
                employeesCHCount =  chRs.getInt(1);
            }

            Assert.assertTrue(employeesMySqlCount == employeesCHCount);
            // Files.deleteIfExists(tmpFilePath);
            if(engine.get() != null) {
                engine.get().stop();
            }
            executorService.shutdown();

        }

}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
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
            mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
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
                    engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(), false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Thread.sleep(10000);

            Connection conn = connectToMySQL();
            // alter table ship_class change column class_name class_name_new int;
            // alter table ship_class change column tonange tonange_new decimal(10,10);

            Thread.sleep(40000);

            BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);

            DBMetadata dbMetadata = new DBMetadata();
            // Validate that all the tables are created.
            Map<String, String> departmentsColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "departments", "employees");
            Assert.assertTrue("String".equalsIgnoreCase(departmentsColumns.get("dept_no")));
            Assert.assertTrue("String".equalsIgnoreCase(departmentsColumns.get("dept_name")));

            Map<String, String> departmentEmpsColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "dept_emp", "employees");
            Assert.assertTrue("Int32".equalsIgnoreCase(departmentEmpsColumns.get("emp_no")));
            Assert.assertTrue("String".equalsIgnoreCase(departmentEmpsColumns.get("dept_no")));
            Assert.assertTrue("Date32".equalsIgnoreCase(departmentEmpsColumns.get("from_date")));
            Assert.assertTrue("Date32".equalsIgnoreCase(departmentEmpsColumns.get("to_date")));

            Map<String, String> deptManagerColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "dept_manager", "employees");

            Map<String, String> employeesColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "employees", "employees");
            Assert.assertTrue("Int32".equalsIgnoreCase(employeesColumns.get("emp_no")));
            Assert.assertTrue("Date32".equalsIgnoreCase(employeesColumns.get("birth_date")));
            Assert.assertTrue("String".equalsIgnoreCase(employeesColumns.get("first_name")));
            Assert.assertTrue("String".equalsIgnoreCase(employeesColumns.get("last_name")));
            Assert.assertTrue("String".equalsIgnoreCase(employeesColumns.get("gender")));

            Map<String, String> salariesColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "salaries", "employees");
            Assert.assertTrue("Int32".equalsIgnoreCase(salariesColumns.get("emp_no")));
            Assert.assertTrue("Int32".equalsIgnoreCase(salariesColumns.get("salary")));
            Assert.assertTrue("Date32".equalsIgnoreCase(salariesColumns.get("from_date")));
            Assert.assertTrue("Date32".equalsIgnoreCase(salariesColumns.get("to_date")));

            Map<String, String> titlesColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "titles", "employees");
            Assert.assertTrue("Int32".equalsIgnoreCase(titlesColumns.get("emp_no")));
            Assert.assertTrue("String".equalsIgnoreCase(titlesColumns.get("title")));
            Assert.assertTrue("Date32".equalsIgnoreCase(titlesColumns.get("from_date")));
            Assert.assertTrue("Nullable(Date32)".equalsIgnoreCase(titlesColumns.get("to_date")));


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

            HikariDbSource.close();
        }

}

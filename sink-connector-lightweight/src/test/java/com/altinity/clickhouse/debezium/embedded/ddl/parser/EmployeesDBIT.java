package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.HikariDbSource;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import org.apache.log4j.BasicConfigurator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;


import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
@Disabled
@Testcontainers
@DisplayName("Integration Test to validate replication of employees database")
public class EmployeesDBIT extends DDLBaseIT {
        private static final Logger log = LogManager.getLogger(EmployeesDBIT.class);


        @BeforeEach
        @Override
        public void startContainers() throws InterruptedException {
            mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
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
                    engine.get().setup(getDebeziumProperties(), new SourceRecordParserService(),  false);
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

            DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
            // Validate that all the tables are created.
            Map<String, String> departmentsColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "departments", "employees");
            Assert.assertTrue(departmentsColumns.get("dept_no").equalsIgnoreCase("String"));
            Assert.assertTrue(departmentsColumns.get("dept_name").equalsIgnoreCase("String"));

            Map<String, String> departmentEmpsColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "dept_emp", "employees");
            Assert.assertTrue(departmentEmpsColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(departmentEmpsColumns.get("dept_no").equalsIgnoreCase("String"));
            Assert.assertTrue(departmentEmpsColumns.get("from_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(departmentEmpsColumns.get("to_date").equalsIgnoreCase("Date32"));

            Map<String, String> deptManagerColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "dept_manager", "employees");

            Map<String, String> employeesColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "employees", "employees");
            Assert.assertTrue(employeesColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(employeesColumns.get("birth_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(employeesColumns.get("first_name").equalsIgnoreCase("String"));
            Assert.assertTrue(employeesColumns.get("last_name").equalsIgnoreCase("String"));
            Assert.assertTrue(employeesColumns.get("gender").equalsIgnoreCase("String"));

            Map<String, String> salariesColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "salaries", "employees");
            Assert.assertTrue(salariesColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(salariesColumns.get("salary").equalsIgnoreCase("Int32"));
            Assert.assertTrue(salariesColumns.get("from_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(salariesColumns.get("to_date").equalsIgnoreCase("Date32"));

            Map<String, String> titlesColumns = dbMetadata.getColumnsDataTypesForTable(writer.getConnection(), "titles", "employees");
            Assert.assertTrue(titlesColumns.get("emp_no").equalsIgnoreCase("Int32"));
            Assert.assertTrue(titlesColumns.get("title").equalsIgnoreCase("String"));
            Assert.assertTrue(titlesColumns.get("from_date").equalsIgnoreCase("Date32"));
            Assert.assertTrue(titlesColumns.get("to_date").equalsIgnoreCase("Nullable(Date32)"));


            // Validate data consistency using checksums
            log.info("Starting checksum validation for replicated tables...");
            
            String[] tablesToValidate = {"departments", "dept_emp", "dept_manager", "employees", "salaries", "titles"};
            
            for (String table : tablesToValidate) {
                log.info("Validating table: " + table);
                
                // Calculate MySQL checksum using ITCommon utility
                String mysqlChecksum = ITCommon.calculateMySQLTableChecksum(
                        mySqlContainer.getHost(),
                        mySqlContainer.getMappedPort(3306),
                        mySqlContainer.getUsername(),
                        mySqlContainer.getPassword(),
                        mySqlContainer.getDatabaseName(),
                        table
                );
                
                // Calculate ClickHouse checksum using ITCommon utility
                String clickhouseChecksum = ITCommon.calculateClickHouseTableChecksum(
                        clickHouseContainer.getHost(),
                        clickHouseContainer.getMappedPort(8123),
                        clickHouseContainer.getUsername(),
                        clickHouseContainer.getPassword(),
                        mySqlContainer.getDatabaseName(),
                        table
                );
                
                log.info(String.format("Table %s - MySQL checksum: %s, ClickHouse checksum: %s", 
                        table, mysqlChecksum, clickhouseChecksum));
                
                Assert.assertEquals(
                        String.format("Checksum mismatch for table %s", table),
                        mysqlChecksum,
                        clickhouseChecksum
                );
            }
            
            log.info("All tables validated successfully!");
            
            // Files.deleteIfExists(tmpFilePath);
            if(engine.get() != null) {
                engine.get().stop();
            }
            executorService.shutdown();

            HikariDbSource.close();
        }

}

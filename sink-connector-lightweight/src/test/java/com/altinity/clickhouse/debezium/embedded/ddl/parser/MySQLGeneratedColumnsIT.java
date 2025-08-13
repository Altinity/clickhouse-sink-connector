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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@DisplayName("Integration Test that validates handling of MySQL Generated Columns DDL and data replication")
@Testcontainers
public class MySQLGeneratedColumnsIT extends DDLBaseIT {

    @BeforeEach
    public void startContainers() throws InterruptedException {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("mysql_generated_columns.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
    }

    @Test
    public void testBasicGeneratedColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);

        // Wait for initial data sync
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        // Validate basic generated column structure
        Map<String, String> basicColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "basic_generated_column", "employees");
        
        Assert.assertTrue("ID column should exist", basicColumns.containsKey("id"));
        Assert.assertTrue("Width column should exist", basicColumns.containsKey("width"));
        Assert.assertTrue("Height column should exist", basicColumns.containsKey("height"));
        Assert.assertTrue("Area column should exist", basicColumns.containsKey("area"));
        
        Assert.assertTrue(basicColumns.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(basicColumns.get("width").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(basicColumns.get("height").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(basicColumns.get("area").equalsIgnoreCase("Nullable(Decimal(10, 2))"));

        // Verify data was replicated correctly
        Connection chConn = writer.getConnection();
        PreparedStatement ps = chConn.prepareStatement(
            "SELECT id, width, height, area FROM employees.basic_generated_column ORDER BY id");
        ResultSet rs = ps.executeQuery();
        
        Assert.assertTrue("Should have at least one row", rs.next());
        Assert.assertEquals(1, rs.getInt("id"));
        Assert.assertEquals(10.50, rs.getDouble("width"), 0.01);
        Assert.assertEquals(20.30, rs.getDouble("height"), 0.01);
        Assert.assertEquals(213.15, rs.getDouble("area"), 0.01);

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testVirtualGeneratedColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> virtualColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "virtual_column_example", "employees");
        
        Assert.assertTrue("ID column should exist", virtualColumns.containsKey("id"));
        Assert.assertTrue("First name column should exist", virtualColumns.containsKey("first_name"));
        Assert.assertTrue("Last name column should exist", virtualColumns.containsKey("last_name"));
        Assert.assertTrue("Full name column should exist", virtualColumns.containsKey("full_name"));
        
        Assert.assertTrue(virtualColumns.get("id").equalsIgnoreCase("Int32"));
        Assert.assertTrue(virtualColumns.get("first_name").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(virtualColumns.get("last_name").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(virtualColumns.get("full_name").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testComplexGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> complexColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "complex_generated_columns", "employees");
        
        Assert.assertTrue("Base salary column should exist", complexColumns.containsKey("base_salary"));
        Assert.assertTrue("Tax rate column should exist", complexColumns.containsKey("tax_rate"));
        Assert.assertTrue("Gross salary column should exist", complexColumns.containsKey("gross_salary"));
        Assert.assertTrue("Annual salary column should exist", complexColumns.containsKey("annual_salary"));
        
        Assert.assertTrue(complexColumns.get("base_salary").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(complexColumns.get("tax_rate").equalsIgnoreCase("Nullable(Decimal(5, 2))"));
        Assert.assertTrue(complexColumns.get("gross_salary").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(complexColumns.get("annual_salary").equalsIgnoreCase("Nullable(Decimal(10, 2))"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testDateBasedGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> dateColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "date_generated_columns", "employees");
        
        Assert.assertTrue("Order date column should exist", dateColumns.containsKey("order_date"));
        Assert.assertTrue("Year month column should exist", dateColumns.containsKey("year_month"));
        Assert.assertTrue("Quarter column should exist", dateColumns.containsKey("quarter"));
        Assert.assertTrue("Is weekend column should exist", dateColumns.containsKey("is_weekend"));
        
        Assert.assertTrue(dateColumns.get("order_date").equalsIgnoreCase("Nullable(DateTime)"));
        Assert.assertTrue(dateColumns.get("year_month").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(dateColumns.get("quarter").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(dateColumns.get("is_weekend").equalsIgnoreCase("Nullable(Int8)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testJSONGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> jsonColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "json_generated_columns", "employees");
        
        Assert.assertTrue("Order details column should exist", jsonColumns.containsKey("order_details"));
        Assert.assertTrue("Total amount column should exist", jsonColumns.containsKey("total_amount"));
        Assert.assertTrue("Product name column should exist", jsonColumns.containsKey("product_name"));
        
        Assert.assertTrue(jsonColumns.get("order_details").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(jsonColumns.get("total_amount").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(jsonColumns.get("product_name").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testMathematicalGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> mathColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "mathematical_generated_columns", "employees");
        
        Assert.assertTrue("Radius column should exist", mathColumns.containsKey("radius"));
        Assert.assertTrue("Circle area column should exist", mathColumns.containsKey("circle_area"));
        Assert.assertTrue("Circle circumference column should exist", mathColumns.containsKey("circle_circumference"));
        
        Assert.assertTrue(mathColumns.get("radius").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(mathColumns.get("circle_area").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(mathColumns.get("circle_circumference").equalsIgnoreCase("Nullable(Decimal(10, 2))"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testAlterTableAddGeneratedColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);

        Connection conn = connectToMySQL();
        
        // Add generated column to existing table
        conn.prepareStatement(
            "ALTER TABLE base_table ADD COLUMN full_name VARCHAR(100) " +
            "GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) VIRTUAL"
        ).execute();
        
        // Modify generated column
        conn.prepareStatement(
            "ALTER TABLE base_table MODIFY COLUMN full_name VARCHAR(150) " +
            "GENERATED ALWAYS AS (CONCAT(first_name, ' ', IFNULL(last_name, ''))) VIRTUAL"
        ).execute();

        Thread.sleep(25000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        Map<String, String> baseTableColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "base_table", "employees");

        Assert.assertTrue("Full name column should exist after ALTER", 
            baseTableColumns.containsKey("full_name"));
        Assert.assertTrue(baseTableColumns.get("full_name").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testEmployeeStatusGeneratedColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> employeeColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "employee_status", "employees");
        
        Assert.assertTrue("Employment status column should exist", 
            employeeColumns.containsKey("employment_status"));
        Assert.assertTrue(employeeColumns.get("employment_status").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testProductPricingGeneratedColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> pricingColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "product_pricing", "employees");
        
        Assert.assertTrue("Price category column should exist", 
            pricingColumns.containsKey("price_category"));
        Assert.assertTrue("Category column should exist", 
            pricingColumns.containsKey("category"));
        Assert.assertTrue(pricingColumns.get("price_category").equalsIgnoreCase("Nullable(String)"));
        Assert.assertTrue(pricingColumns.get("category").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testConstrainedGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> constrainedColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "constrained_generated_columns", "employees");
        
        Assert.assertTrue("Temperature category column should exist", 
            constrainedColumns.containsKey("temperature_category"));
        Assert.assertTrue(constrainedColumns.get("temperature_category").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testIndexableGeneratedColumns() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> indexableColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "indexable_generated_columns", "employees");
        
        Assert.assertTrue("Full name column should exist", 
            indexableColumns.containsKey("full_name"));
        Assert.assertTrue(indexableColumns.get("full_name").equalsIgnoreCase("Nullable(String)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }

    @Test
    public void testPersonAgeGeneratedColumn() throws Exception {
        AtomicReference<DebeziumChangeEventCapture> engine = new AtomicReference<>();
        
        ExecutorService executorService = Executors.newFixedThreadPool(1);
        executorService.execute(() -> {
            try {
                Properties properties = getDebeziumProperties();
                engine.set(new DebeziumChangeEventCapture());
                engine.get().setup(properties, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(10000);
        Thread.sleep(15000);

        BaseDbWriter writer = ITCommon.getDBWriter(clickHouseContainer);
        DBMetadata dbMetadata = new DBMetadata(getDebeziumProperties());
        
        Map<String, String> personColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "person", "employees");
        
        Assert.assertTrue("Age column should exist", personColumns.containsKey("age"));
        Assert.assertTrue("Birth date column should exist", personColumns.containsKey("birth_date"));
        Assert.assertTrue(personColumns.get("age").equalsIgnoreCase("Nullable(Int32)"));
        Assert.assertTrue(personColumns.get("birth_date").equalsIgnoreCase("Nullable(Date)"));

        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }
}
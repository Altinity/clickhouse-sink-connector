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
    public void startContainers() throws Exception {
        mySqlContainer = new MySQLContainer<>(DockerImageName.parse("docker.io/bitnami/mysql:8.0.36")
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

        BasicConfigurator.configure();
        mySqlContainer.start();
        clickHouseContainer.start();
        Thread.sleep(15000);
        
        // Create tables and insert data programmatically
        setupDatabase();
    }
    
    private void setupDatabase() throws Exception {
        Connection conn = connectToMySQL();
        
        // Create database
        conn.prepareStatement("CREATE DATABASE IF NOT EXISTS employees").execute();
        conn.prepareStatement("USE employees").execute();
        
        // 1. Basic Generated Column (Stored)
        conn.prepareStatement("""
            CREATE TABLE basic_generated_column (
                id INT PRIMARY KEY,
                width DECIMAL(10,2),
                height DECIMAL(10,2),
                area DECIMAL(10,2) GENERATED ALWAYS AS (width * height) STORED
            )
            """).execute();
            
        // 2. Virtual Generated Column
        conn.prepareStatement("""
            CREATE TABLE virtual_column_example (
                id INT PRIMARY KEY,
                first_name VARCHAR(50),
                last_name VARCHAR(50),
                full_name VARCHAR(100) GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) VIRTUAL
            )
            """).execute();
            
        // 3. Multiple Generated Columns
        conn.prepareStatement("""
            CREATE TABLE complex_generated_columns (
                id INT PRIMARY KEY,
                base_salary DECIMAL(10,2),
                tax_rate DECIMAL(5,2),
                gross_salary DECIMAL(10,2) GENERATED ALWAYS AS (base_salary * (1 + tax_rate/100)) STORED,
                annual_salary DECIMAL(10,2) GENERATED ALWAYS AS (gross_salary * 12) STORED
            )
            """).execute();
            
        // 4. JSON Generated Columns
        conn.prepareStatement("""
            CREATE TABLE json_generated_columns (
                id INT PRIMARY KEY,
                order_details JSON,
                total_amount DECIMAL(10,2) GENERATED ALWAYS AS (
                    JSON_EXTRACT(order_details, '$.price') * JSON_EXTRACT(order_details, '$.quantity')
                ) STORED,
                product_name VARCHAR(255) GENERATED ALWAYS AS (
                    JSON_UNQUOTE(JSON_EXTRACT(order_details, '$.product_name'))
                ) VIRTUAL
            )
            """).execute();
            
        // 5. Mathematical Generated Columns
        conn.prepareStatement("""
            CREATE TABLE mathematical_generated_columns (
                id INT PRIMARY KEY,
                radius DECIMAL(10,2),
                circle_area DECIMAL(10,2) GENERATED ALWAYS AS (PI() * radius * radius) STORED,
                circle_circumference DECIMAL(10,2) GENERATED ALWAYS AS (2 * PI() * radius) STORED
            )
            """).execute();
            
        // 6. Base table for ALTER operations
        conn.prepareStatement("""
            CREATE TABLE base_table (
                id INT PRIMARY KEY,
                first_name VARCHAR(50),
                last_name VARCHAR(50)
            )
            """).execute();
            
//        // 7. Employee status table for complex conditions
//        conn.prepareStatement("""
//            CREATE TABLE employee_status (
//                id INT PRIMARY KEY,
//                hire_date DATE,
//                termination_date DATE,
//                employment_status VARCHAR(20) GENERATED ALWAYS AS (
//                    CASE
//                        WHEN termination_date IS NULL THEN 'Active'
//                        WHEN termination_date < CURRENT_DATE THEN 'Terminated'
//                        ELSE 'Pending Termination'
//                    END
//                ) VIRTUAL
//            )
//            """).execute();
            
        // 8. Product pricing with enum-based generated columns
        conn.prepareStatement("""
            CREATE TABLE product_pricing (
                id INT PRIMARY KEY,
                base_price DECIMAL(10,2),
                category ENUM('Low', 'Medium', 'High'),
                price_category VARCHAR(20) GENERATED ALWAYS AS (
                    CASE
                        WHEN base_price < 10 THEN 'Budget'
                        WHEN base_price BETWEEN 10 AND 50 THEN 'Mid-range'
                        ELSE 'Premium'
                    END
                ) STORED
            )
            """).execute();
            
        // 9. Constrained generated columns
        conn.prepareStatement("""
            CREATE TABLE constrained_generated_columns (
                id INT PRIMARY KEY,
                temperature DECIMAL(5,2),
                temperature_category VARCHAR(20) GENERATED ALWAYS AS (
                    CASE
                        WHEN temperature < 0 THEN 'Freezing'
                        WHEN temperature BETWEEN 0 AND 20 THEN 'Cold'
                        WHEN temperature BETWEEN 20 AND 30 THEN 'Warm'
                        ELSE 'Hot'
                    END
                ) STORED,
                CONSTRAINT chk_temperature CHECK (temperature BETWEEN -50 AND 100)
            )
            """).execute();
            
        // Insert sample data for testing
        conn.prepareStatement("""
            INSERT INTO basic_generated_column (id, width, height) VALUES 
            (1, 10.5, 20.3),
            (2, 15.7, 8.9),
            (3, 12.0, 25.5)
            """).execute();
            
        conn.prepareStatement("""
            INSERT INTO virtual_column_example (id, first_name, last_name) VALUES 
            (1, 'John', 'Doe'),
            (2, 'Jane', 'Smith'),
            (3, 'Bob', 'Johnson')
            """).execute();
            
        conn.prepareStatement("""
            INSERT INTO complex_generated_columns (id, base_salary, tax_rate) VALUES 
            (1, 5000.00, 15.0),
            (2, 7500.00, 20.0),
            (3, 10000.00, 25.0)
            """).execute();
            
        conn.prepareStatement("""
            INSERT INTO json_generated_columns (id, order_details) VALUES 
            (1, '{"price": 25.50, "quantity": 2, "product_name": "Widget A"}'),
            (2, '{"price": 15.75, "quantity": 3, "product_name": "Widget B"}'),
            (3, '{"price": 100.00, "quantity": 1, "product_name": "Premium Widget"}')
            """).execute();
            
        conn.prepareStatement("""
            INSERT INTO mathematical_generated_columns (id, radius) VALUES 
            (1, 5.0),
            (2, 10.0),
            (3, 7.5)
            """).execute();
            
        conn.prepareStatement("""
            INSERT INTO base_table (id, first_name, last_name) VALUES 
            (1, 'Alice', 'Cooper'),
            (2, 'Charlie', 'Brown'),
            (3, 'Diana', 'Prince')
            """).execute();
            
//        conn.prepareStatement("""
//            INSERT INTO employee_status (id, hire_date, termination_date) VALUES
//            (1, '2020-01-15', NULL),
//            (2, '2019-03-20', '2023-12-31'),
//            (3, '2021-06-10', '2025-03-15')
//            """).execute();

        conn.prepareStatement("""
            INSERT INTO product_pricing (id, base_price, category) VALUES 
            (1, 5.99, 'Low'),
            (2, 25.50, 'Medium'),
            (3, 150.00, 'High')
            """).execute();
            
        conn.prepareStatement("""
            INSERT INTO constrained_generated_columns (id, temperature) VALUES 
            (1, -10.5),
            (2, 15.0),
            (3, 25.5),
            (4, 45.0)
            """).execute();
    }

    @Test
    public void testGeneratedColumns() throws Exception {
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
        
        // Test ALTER TABLE operations with generated columns
        conn.prepareStatement(
            "ALTER TABLE base_table ADD COLUMN full_name VARCHAR(100) " +
            "GENERATED ALWAYS AS (CONCAT(first_name, ' ', last_name)) VIRTUAL"
        ).execute();
        
        conn.prepareStatement(
            "ALTER TABLE base_table MODIFY COLUMN full_name VARCHAR(150) " +
            "GENERATED ALWAYS AS (CONCAT(first_name, ' ', IFNULL(last_name, ''))) VIRTUAL"
        ).execute();

        // Wait for initial data sync and DDL processing
        Thread.sleep(25000);

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

        // Test virtual generated columns
        Map<String, String> virtualColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "virtual_column_example", "employees");
        
        Assert.assertTrue("Full name column should exist", virtualColumns.containsKey("full_name"));
        Assert.assertTrue(virtualColumns.get("full_name").equalsIgnoreCase("Nullable(String)"));

        // Test complex generated columns
        Map<String, String> complexColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "complex_generated_columns", "employees");
        
        Assert.assertTrue("Gross salary column should exist", complexColumns.containsKey("gross_salary"));
        Assert.assertTrue("Annual salary column should exist", complexColumns.containsKey("annual_salary"));
        Assert.assertTrue(complexColumns.get("gross_salary").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(complexColumns.get("annual_salary").equalsIgnoreCase("Nullable(Decimal(10, 2))"));

        // Test JSON generated columns
        Map<String, String> jsonColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "json_generated_columns", "employees");
        
        Assert.assertTrue("Total amount column should exist", jsonColumns.containsKey("total_amount"));
        Assert.assertTrue("Product name column should exist", jsonColumns.containsKey("product_name"));
        Assert.assertTrue(jsonColumns.get("total_amount").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(jsonColumns.get("product_name").equalsIgnoreCase("Nullable(String)"));

        // Test mathematical generated columns
        Map<String, String> mathColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "mathematical_generated_columns", "employees");
        
        Assert.assertTrue("Circle area column should exist", mathColumns.containsKey("circle_area"));
        Assert.assertTrue("Circle circumference column should exist", mathColumns.containsKey("circle_circumference"));
        Assert.assertTrue(mathColumns.get("circle_area").equalsIgnoreCase("Nullable(Decimal(10, 2))"));
        Assert.assertTrue(mathColumns.get("circle_circumference").equalsIgnoreCase("Nullable(Decimal(10, 2))"));

        // Test ALTER TABLE generated columns
        Map<String, String> baseTableColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "base_table", "employees");
        Assert.assertTrue("Full name column should exist after ALTER", baseTableColumns.containsKey("full_name"));
        Assert.assertTrue(baseTableColumns.get("full_name").equalsIgnoreCase("Nullable(String)"));

        // Test employee status generated columns
        Map<String, String> employeeColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "employee_status", "employees");
        Assert.assertTrue("Employment status column should exist", employeeColumns.containsKey("employment_status"));
        Assert.assertTrue(employeeColumns.get("employment_status").equalsIgnoreCase("Nullable(String)"));

        // Test product pricing generated columns
        Map<String, String> pricingColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "product_pricing", "employees");
        Assert.assertTrue("Price category column should exist", pricingColumns.containsKey("price_category"));
        Assert.assertTrue(pricingColumns.get("price_category").equalsIgnoreCase("Nullable(String)"));

        // Test constrained generated columns
        Map<String, String> constrainedColumns = dbMetadata.getColumnsDataTypesForTable(
            writer.getConnection(), "constrained_generated_columns", "employees");
        Assert.assertTrue("Temperature category column should exist", constrainedColumns.containsKey("temperature_category"));
        Assert.assertTrue(constrainedColumns.get("temperature_category").equalsIgnoreCase("Nullable(String)"));


        if (engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdown();
        HikariDbSource.close();
    }



}
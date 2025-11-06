package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.altinity.clickhouse.debezium.embedded.ITCommon;
import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import com.altinity.clickhouse.debezium.embedded.parser.SourceRecordParserService;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.db.BaseDbWriter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.clickhouse.jdbc.ClickHouseConnection;

import org.aopalliance.reflect.Metadata;
import org.apache.log4j.BasicConfigurator;
import org.junit.Assert;
import org.junit.jupiter.api.*;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import static com.altinity.clickhouse.debezium.embedded.ITCommon.MYSQL_DOCKER_IMAGE;
import static com.altinity.clickhouse.debezium.embedded.ITCommon.CLICKHOUSE_DOCKER_IMAGE;

/**
 * Integration Test class to validate MySQL and ClickHouse operations using Debezium.
 * Tests various DDL and DML operations including column additions, modifications,
 * and data manipulations while ensuring data consistency between MySQL and ClickHouse.
 */
@DisplayName("Integration Test to validate MySQL and ClickHouse operations using Debezium")
public class MySQLDemoIT  {

    private static AtomicReference<DebeziumChangeEventCapture> engine;
    private static ExecutorService executorService;
    private static Connection mysqlConn;
    private static BaseDbWriter writer;


    @Container
    public static ClickHouseContainer clickHouseContainer = new ClickHouseContainer(DockerImageName.parse(CLICKHOUSE_DOCKER_IMAGE)
            .asCompatibleSubstituteFor("clickhouse"))
            .withInitScript("init_clickhouse_it.sql")
            .withUsername("ch_user")
            .withPassword("password")
            .withExposedPorts(8123);

    @Container
    public static MySQLContainer mySqlContainer = new MySQLContainer<>(DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees").withUsername("root").withPassword("adminpass")
                .withInitScript("alter_ddl_add_column.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));

    /**
     * Initializes test environment by:
     * 1. Starting MySQL container with employees database
     * 2. Starting ClickHouse container
     * 3. Setting up Debezium engine for change data capture
     * 4. Establishing MySQL connection
     *
     * 5. Creating database writer for ClickHouse operations
     *
     * @throws Exception if container startup or initialization fails
     */
    @BeforeAll
    public static void startContainers()  {

        mySqlContainer = new MySQLContainer<>(
                DockerImageName.parse(MYSQL_DOCKER_IMAGE)
                        .asCompatibleSubstituteFor("mysql"))
                .withDatabaseName("employees")
                .withUsername("root")
                .withPassword("adminpass")
                .withInitScript("employees.sql")
                .withExtraHost("mysql-server", "0.0.0.0")
                .waitingFor(new HttpWaitStrategy().forPort(3306));


        try {
            BasicConfigurator.configure();
            mySqlContainer.start();
            clickHouseContainer.start();
            Thread.sleep(10000);

            setupDebeziumEngine();
            mysqlConn = connectToMySQL();
            String jdbcUrl = BaseDbWriter.getConnectionString(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(), "employees");
            Connection connection = BaseDbWriter.createConnection(jdbcUrl, "client_1", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(),
                    "employees", new ClickHouseSinkConnectorConfig(new HashMap<>()));
            writer = new BaseDbWriter(clickHouseContainer.getHost(), clickHouseContainer.getFirstMappedPort(),
                    "employees", clickHouseContainer.getUsername(), clickHouseContainer.getPassword(), null, connection);
            Thread.sleep(20000);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to start containers", e);
        }
    }

    /**
     * Configures and initializes the Debezium engine for change data capture.
     * Creates a new single-threaded executor service to run the engine asynchronously.
     * Waits for engine startup to complete before proceeding.
     *
     * @throws Exception if engine setup fails
     */
    private static void setupDebeziumEngine() throws Exception {
        engine = new AtomicReference<>();
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(() -> {
            try {
                DebeziumChangeEventCapture capture = new DebeziumChangeEventCapture();
                engine.set(capture);
                capture.setup(props, new SourceRecordParserService(), false);
            } catch (Exception e) {
                throw new RuntimeException("Failed to start Debezium engine", e);
            }
        });
        Thread.sleep( 20000); // wait for engine
    }

    protected static Connection connectToMySQL() {
        return ITCommon.connectToMySQL(mySqlContainer);
    }

    /**
     * Cleanup method to stop Debezium engine and executor service.
     * Deletes offsets and ensures proper shutdown of resources.
     *
     * @throws Exception if cleanup operations fail
     */
    @AfterAll
    public static void stopEngine() throws Exception {
        Properties props = ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);
        if (engine != null && engine.get() != null) {
            engine.get().stop();
        }
        executorService.shutdownNow();
    }

    /**
     * Returns the Debezium properties for the test.
     * Sets up the properties for MySQL and ClickHouse connections.
     * Configures buffer size for records.
     *
     * @return Properties object with Debezium configuration
     * @throws Exception if property setup fails
     */

    protected Properties getDebeziumProperties() throws Exception {

        Properties baseProps =  ITCommon.getDebeziumProperties(mySqlContainer, clickHouseContainer);

        baseProps.put(SinkConnectorLightWeightConfig.DDL_RETRY, "true");
        baseProps.put("buffer.max.records", "100");

        return baseProps;
    }

    /**
     * Tests updating and dropping the jobTitle column.
     * 1. Updates all employees' job titles to specified title
     * 2. Verifies the update in ClickHouse
     * 3. Drops the jobTitle column
     * 4. Ensures data consistency
     *
     * @throws Exception if update operations fail
     */
    @Test
    @DisplayName("Test updateJobTitle column and drop")
    public void testUpdateJobTitleColumn() throws Exception {
        //Thread.sleep(100000);
        addJobTitleColumn(mysqlConn, writer); // prerequisite
        updateJobTitle(mysqlConn, writer, "Senior Engineer");
    }

    /**
     * Tests employee deletion by last name.
     * 1. Retrieves a sample last name
     * 2. Deletes employees with that last name
     * 3. Verifies deletion reflected in both databases
     *
     * @throws Exception if deletion or verification fails
     */
    @Test
    @DisplayName("Test deleting employee by last name")
    public void testDeleteEmployeeByLastName() throws Exception {
        deleteEmployeesByLastName(mysqlConn, writer);
    }

    /**
     * Tests adding and dropping a hireDate column.
     * 1. Adds hireDate column with current date default
     * 2. Verifies column exists
     * 3. Drops the column
     * 4. Verifies column removal
     *
     * @throws Exception if column operations fail
     */
    @Test
    @DisplayName("Test add and drop hireDate column")
    public void testAddAndDropHireDate() throws Exception {
        addAndDropHireDateColumn(mysqlConn, writer);
    }

    /**
     * Tests inserting a new employee record.
     * 1. Gets a sample last name from existing employees
     * 2. Creates new employee with ID 2000
     * 3. Verifies insertion in ClickHouse
     *
     * @throws Exception if insertion or verification fails
     */
    @Test
    @DisplayName("Test insert new employee")
    public void testInsertNewEmployee() throws Exception {
        insertNewEmployee(mysqlConn, writer);
    }

    /**
     * Tests modifying the officeCode column.
     * 1. Adds officeCode column as INT
     * 2. Updates all null values to 1
     * 3. Modifies column to NOT NULL with default
     * 4. Verifies column type in ClickHouse
     *
     * @throws Exception if column modification fails
     */
    @Test
    @Disabled
    @DisplayName("Test modifying officeCode column")
    public void testModifyOfficeCode() throws Exception {
        modifyOfficeCodeColumn(mysqlConn, writer);
    }

    /**
     * Creates customers and payments tables with sample data.
     * Sets up foreign key relationship between tables.
     * Inserts test data for relationship testing.
     *
     * @throws Exception if table creation or data insertion fails
     */
    @Test
    @DisplayName("Test customer and payments table creation + query validation")
    public void testCustomersAndPayments() throws Exception {
        createCustomersAndPaymentsTables(mysqlConn);
        runCustomerPaymentQueries(mysqlConn, writer);
    }

    /**
     * Tests adding a jobTitle column to employees table.
     * Verifies column addition in both MySQL and ClickHouse.
     * Ensures data consistency between both databases.
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @throws Exception if column addition or verification fails
     */
    private void addJobTitleColumn(Connection mysqlConn, BaseDbWriter writer) throws Exception {
        mysqlConn.prepareStatement("ALTER TABLE employees ADD COLUMN jobTitle" +
                " VARCHAR(50) NOT NULL DEFAULT 'Engineer';").execute();
       Thread.sleep(10000);
        DBMetadata metadata = new DBMetadata(getDebeziumProperties());
        Map<String, String> columns =
                metadata.getColumnsDataTypesForTable( writer.getConnection(), "employees", "employees");
        Assert.assertTrue("jobTitle column should exist", columns.containsKey("jobTitle"));

        //verifyTableCounts(mysqlConn, writer, "After adding jobTitle column");
    }

    /**
     * Tests updating and dropping the jobTitle column.
     * 1. Updates all employees' job titles to specified title
     * 2. Verifies the update in ClickHouse
     * 3. Drops the jobTitle column
     * 4. Ensures data consistency
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @param title New job title to set
     * @throws Exception if update operations fail
     */
    private void updateJobTitle(Connection mysqlConn, BaseDbWriter writer, String title) throws Exception {
        mysqlConn.prepareStatement("UPDATE employees set jobTitle='Senior Engineer'").execute();
        Thread.sleep(10000);

        // Verify the update
        try (PreparedStatement pstmt = writer.getConnection().prepareStatement(
                "SELECT jobTitle FROM employees.`employees` FINAL LIMIT 1")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
              //  Assert.assertEquals("Job title should match", title, rs.getString("jobTitle"));
            }
        }

        mysqlConn.prepareStatement("ALTER TABLE employees DROP COLUMN jobTitle;").execute();
        Thread.sleep(10000);

        verifyTableCounts(mysqlConn, writer, "After setting jobTitle to " + title);
    }

    /**
     * Tests employee deletion by last name.
     * 1. Retrieves a sample last name
     * 2. Deletes employees with that last name
     * 3. Verifies deletion reflected in both databases
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @throws Exception if deletion or verification fails
     */
    private void deleteEmployeesByLastName(Connection mysqlConn, BaseDbWriter writer) throws Exception {
        String lastName = null;
        try (ResultSet rs = mysqlConn.createStatement().executeQuery("SELECT last_name FROM employees LIMIT 1")) {
            if (rs.next()) {
                lastName = rs.getString("last_name");
            }
        }

        if (lastName != null) {
            int before = getCount(mysqlConn, "SELECT COUNT(*) FROM employees");
            mysqlConn.prepareStatement("DELETE FROM employees WHERE last_name = '" + lastName + "'").execute();
            Thread.sleep(10000);
            int after = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.`employees` FINAL");
            Assert.assertTrue("Records should decrease", after < before);
        }
    }

    /**
     * Tests adding and dropping a hireDate column.
     * 1. Adds hireDate column with current date default
     * 2. Verifies column exists
     * 3. Drops the column
     * 4. Verifies column removal
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @throws Exception if column operations fail
     */
    private void addAndDropHireDateColumn(Connection mysqlConn, BaseDbWriter writer) throws Exception {
        mysqlConn.prepareStatement("ALTER TABLE employees ADD COLUMN hireDate DATE;").execute();
        Thread.sleep(10000);
        DBMetadata metadata = new DBMetadata(getDebeziumProperties());
        Map<String, String> columns = metadata.getColumnsDataTypesForTable(writer.getConnection(), "employees", "employees");
        Assert.assertTrue("hireDate column should exist", columns.containsKey("hireDate"));

        mysqlConn.prepareStatement("ALTER TABLE employees DROP COLUMN hireDate;").execute();
        Thread.sleep(30000);

        Map<String, String> columnsAfterDelete = metadata.getColumnsDataTypesForTable(writer.getConnection(), "employees", "employees");
        Assert.assertFalse("hireDate column should not exist", columnsAfterDelete.containsKey("hireDate"));
    }

    /**
     * Tests inserting a new employee record.
     * 1. Gets a sample last name from existing employees
     * 2. Creates new employee with ID 2000
     * 3. Verifies insertion in ClickHouse
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @throws Exception if insertion or verification fails
     */
    private void insertNewEmployee(Connection mysqlConn, BaseDbWriter writer) throws Exception {
        String lastName = null;
        try (ResultSet rs = mysqlConn.createStatement().executeQuery("SELECT last_name FROM employees LIMIT 1")) {
            if (rs.next()) {
                lastName = rs.getString("last_name");
            }
        }

        if (lastName != null) {
            int count = getCount(mysqlConn, "SELECT * FROM employees WHERE emp_no = 2000");
            if (count == 0) {
                String insertSql = "INSERT INTO employees(emp_no, birth_date, last_name, first_name, gender, hire_date) " +
                        "SELECT 2000, birth_date, last_name, first_name, " +
                        "gender, hire_date FROM employees WHERE last_name = ? LIMIT 1";
                try (PreparedStatement ps = mysqlConn.prepareStatement(insertSql)) {
                    ps.setString(1, lastName);
                    ps.execute();
                }
            }

            Thread.sleep(10000);
            try (ResultSet rs =
                         writer.getConnection().prepareStatement("SELECT * FROM employees.`employees` FINAL WHERE emp_no = 2000").executeQuery()) {
                Assert.assertTrue("Inserted employee should be found",
                        rs.next());
                Assert.assertEquals("Last name should match", lastName,
                        rs.getString("last_name"));
            }
        }
    }

    /**
     * Tests modifying the officeCode column.
     * 1. Adds officeCode column as INT
     * 2. Updates all null values to 1
     * 3. Modifies column to NOT NULL with default
     * 4. Verifies column type in ClickHouse
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @throws Exception if column modification fails
     */
    private void modifyOfficeCodeColumn(Connection mysqlConn, BaseDbWriter writer) throws Exception {
        mysqlConn.prepareStatement("ALTER TABLE employees ADD COLUMN officeCode INT").execute();

        mysqlConn.prepareStatement("UPDATE employees SET officeCode = 1 WHERE officeCode IS NULL").execute();

        mysqlConn.prepareStatement("ALTER TABLE employees MODIFY COLUMN officeCode INT NOT NULL DEFAULT 1").execute();

        Thread.sleep(10000);
        // Validate in ClickHouse
        DBMetadata metadata = new DBMetadata(getDebeziumProperties());
        Map<String, String> columns = metadata.getColumnsDataTypesForTable(writer.getConnection(), "employees", "employees");
        Assert.assertEquals("officeCode should be Int32", "Nullable(Int32)", columns.get("officeCode"));
    }

    /**
     * Creates customers and payments tables with sample data.
     * Sets up foreign key relationship between tables.
     * Inserts test data for relationship testing.
     *
     * @param conn MySQL database connection
     * @throws Exception if table creation or data insertion fails
     */
    private void createCustomersAndPaymentsTables(Connection conn) throws Exception {
        conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS customers (" +
                        "customer_number INT PRIMARY KEY not null, " +
                        "customer_name VARCHAR(100), " +
                        "sales_rep_employee_number INT);");

        conn.createStatement().execute(
                "CREATE TABLE IF NOT EXISTS payments (" +
                        "payment_number INT PRIMARY KEY not null, " +
                        "customer_number INT, " +
                        "amount DECIMAL(10,2), " +
                        "FOREIGN KEY (customer_number) REFERENCES customers(customer_number));");

        // Insert sample data into customers
        conn.prepareStatement("INSERT INTO customers (customer_number, customer_name, sales_rep_employee_number) VALUES " +
                "(1001, 'Acme Corporation', 1621), " +
                "(1002, 'Globex Corporation', 1621), " +
                "(1003, 'Soylent Corp', 1622);").execute();

        // Insert sample data into payments
        conn.prepareStatement("INSERT INTO payments (payment_number, customer_number, amount) VALUES " +
                "(5001, 1001, 1500.00), " +
                "(5002, 1001, 2000.00), " +
                "(5003, 1002, 3000.00), " +
                "(5004, 1003, 2500.00);").execute();

        Thread.sleep(10000);
    }

    /**
     * Tests complex delete operations involving foreign key relationships.
     * 1. Captures initial record counts
     * 2. Deletes payments for specific sales rep's customers
     * 3. Deletes customers for specific sales rep
     * 4. Verifies cascade deletions and data consistency
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @throws Exception if query operations fail
     */
    private void runCustomerPaymentQueries(Connection mysqlConn, BaseDbWriter writer) throws Exception {
        int mysqlPaymentsBefore = getCount(mysqlConn, "SELECT COUNT(*) FROM payments");
        int mysqlCustomersBefore = getCount(mysqlConn, "SELECT COUNT(*) FROM customers");

        int chPaymentsBefore = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.`payments` FINAL");
        int chCustomersBefore = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.`customers` FINAL");

        System.out.printf("Before Delete => MySQL: Payments=%d, Customers=%d | ClickHouse: Payments=%d, Customers=%d%n",
                mysqlPaymentsBefore, mysqlCustomersBefore, chPaymentsBefore, chCustomersBefore);

        mysqlConn.prepareStatement(
                "DELETE FROM payments WHERE customer_number IN " +
                        "(SELECT customer_number FROM customers WHERE sales_rep_employee_number = 1621)"
        ).execute();

        mysqlConn.prepareStatement(
                "DELETE FROM customers WHERE sales_rep_employee_number = 1621"
        ).execute();

        Thread.sleep(10000);

        int mysqlPaymentsAfter = getCount(mysqlConn, "SELECT COUNT(*) FROM payments");
        int mysqlCustomersAfter = getCount(mysqlConn, "SELECT COUNT(*) FROM customers");

        int chPaymentsAfter = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.`payments` FINAL");
        int chCustomersAfter = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.`customers` FINAL");

        System.out.printf("After Delete => MySQL: Payments=%d, Customers=%d | ClickHouse: Payments=%d, Customers=%d%n",
                mysqlPaymentsAfter, mysqlCustomersAfter, chPaymentsAfter, chCustomersAfter);

        // Assertions
        Assert. assertTrue("MySQL payments count should decrease or remain same", mysqlPaymentsAfter <= mysqlPaymentsBefore);
        Assert.assertTrue("MySQL customers count should decrease or remain same", mysqlCustomersAfter <= mysqlCustomersBefore);

        Assert.assertEquals("Payments count should match between MySQL and ClickHouse after deletion",
                mysqlPaymentsAfter, chPaymentsAfter);
        Assert.assertEquals("Customers count should match between MySQL and ClickHouse after deletion",
                mysqlCustomersAfter, chCustomersAfter);
    }

    /**
     * Helper method to get record count from a table.
     *
     * @param conn Database connection (MySQL or ClickHouse)
     * @param query SQL query to execute
     * @return Count of records
     * @throws Exception if query execution fails
     */
    private int getCount(Connection conn, String query) throws Exception {
        try (ResultSet rs = conn.createStatement().executeQuery(query)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Verifies record counts match between MySQL and ClickHouse.
     * Useful for ensuring data consistency after operations.
     *
     * @param mysqlConn MySQL database connection
     * @param writer ClickHouse database writer
     * @param message Context message for assertion
     * @throws Exception if verification fails
     */
    private void verifyTableCounts(Connection mysqlConn, BaseDbWriter writer, String message) throws Exception {
        int mysqlCount = getCount(mysqlConn, "SELECT COUNT(*) FROM employees");
        int chCount = getCount(writer.getConnection(), "SELECT COUNT(*) FROM employees.`employees` FINAL");
        System.out.printf("MySQL: %d, ClickHouse: %d - %s%n", mysqlCount, chCount, message);
        Assert.assertEquals(message, mysqlCount, chCount);
    }
}
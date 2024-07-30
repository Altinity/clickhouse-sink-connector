package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;


public class MySqlDDLParserListenerImplTest {

    private static final Logger log = LogManager.getLogger(MySqlDDLParserListenerImplTest.class);

    private static MySQLDDLParserService mySQLDDLParserService;
    @BeforeAll
    static public void init() {
        mySQLDDLParserService = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()),
                "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }
    @Test
    public void testCreateTableWithEnum() {
        String createQuery = "CREATE TABLE employees_predated (\n" +
                "    emp_no      INT             NOT NULL,\n" +
                "    birth_date  DATE            NOT NULL,\n" +
                "    first_name  VARCHAR(14)     NOT NULL,\n" +
                "    last_name   VARCHAR(16)     NOT NULL,\n" +
                "    gender      ENUM ('M','F')  NOT NULL,\n" +
                "    hire_date   DATE            NOT NULL,\n" +
                "    PRIMARY KEY (emp_no)\n" +
                ")  PARTITION BY RANGE (emp_no) (\n" +
                "    PARTITION p1 VALUES LESS THAN (1000),\n" +
                "    PARTITION p2 VALUES LESS THAN MAXVALUE\n" +
                "  );";

        StringBuffer clickHouseQuery = new StringBuffer();

        mySQLDDLParserService.parseSql(createQuery, "Persons",  clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.employees_predated(emp_no Int32 NOT NULL ,birth_date Date32 NOT NULL ,first_name String NOT NULL ,last_name String NOT NULL ,gender String NOT NULL ,hire_date Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (emp_no)"));
        log.info("Create table " + clickHouseQuery);
    }
    @Test
    public void testCreateTableWithRangeByColumnsPartition() {
        String createQuery = "CREATE TABLE rcx ( a INT, b INT, c CHAR(3), d INT) PARTITION BY RANGE COLUMNS(a,d,c) ( PARTITION p0 VALUES LESS THAN (5,10,'ggg'), PARTITION p1 VALUES LESS THAN (10,20,'mmm'), " +
                "PARTITION p2 VALUES LESS THAN (15,30,'sss'), PARTITION p3 VALUES LESS THAN (MAXVALUE,MAXVALUE,MAXVALUE));";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "Persons",  clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.rcx(a Nullable(Int32),b Nullable(Int32),c Nullable(String),d Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY  (a,d,c) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);
    }

    @Test
    public void testCreateTableWithParitionRange() {
        String createQuery = "create table t(\n" +
                "id int primary key,\n" +
                "dt date not null\n" +
                ") engine=InnoDB\n" +
                "PARTITION BY RANGE  COLUMNS(dt)\n" +
                "(PARTITION p20201231 VALUES LESS THAN ('2021-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20211230 VALUES LESS THAN ('2021-12-31') ENGINE = InnoDB,\n" +
                " PARTITION p20211231 VALUES LESS THAN ('2022-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20220103 VALUES LESS THAN ('2022-01-04') ENGINE = InnoDB,\n" +
                " PARTITION p20220104 VALUES LESS THAN ('2022-01-05') ENGINE = InnoDB,\n" +
                " PARTITION p20220105 VALUES LESS THAN ('2022-01-06') ENGINE = InnoDB\n" +
                ");";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.t(id Nullable(Int32),dt Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY  (dt) ORDER BY id"));
        log.info("Create table " + clickHouseQuery);

        String createQueryWithoutPrimaryKey =  "create table t(\n" +
                "id int ,\n" +
                "dt date not null\n" +
                ") engine=InnoDB\n" +
                "PARTITION BY RANGE  COLUMNS(dt)\n" +
                "(PARTITION p20201231 VALUES LESS THAN ('2021-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20211230 VALUES LESS THAN ('2021-12-31') ENGINE = InnoDB,\n" +
                " PARTITION p20211231 VALUES LESS THAN ('2022-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20220103 VALUES LESS THAN ('2022-01-04') ENGINE = InnoDB,\n" +
                " PARTITION p20220104 VALUES LESS THAN ('2022-01-05') ENGINE = InnoDB,\n" +
                " PARTITION p20220105 VALUES LESS THAN ('2022-01-06') ENGINE = InnoDB\n" +
                ");";
        StringBuffer clickHouseQueryWOPrimaryKey = new StringBuffer();
        mySQLDDLParserService.parseSql(createQueryWithoutPrimaryKey, "Persons", clickHouseQueryWOPrimaryKey);
        Assert.assertTrue(clickHouseQueryWOPrimaryKey.toString().equalsIgnoreCase("CREATE TABLE employees.t(id Nullable(Int32),dt Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY  (dt) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQueryWOPrimaryKey);
    }
    @Test
    public void testCreateTableWithKeyPartition() {
        String createQuery = "CREATE TABLE members (\n" +
                "    firstname VARCHAR(25) NOT NULL,\n" +
                "    lastname VARCHAR(25) NOT NULL,\n" +
                "    username VARCHAR(16) NOT NULL,\n" +
                "    email VARCHAR(35),\n" +
                "    joined DATE NOT NULL\n" +
                ")\n" +
                "PARTITION BY KEY(joined)\n" +
                "PARTITIONS 6;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.members(firstname String NOT NULL ,lastname String NOT NULL ,username String NOT NULL ,email Nullable(String),joined Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY  joined ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);
    }

    @Test
    @DisplayName("Test DDL conversion - DATETIME columns")
    public void testDateTimeColumns() {
        String createQuery6 = "CREATE TABLE `temporal_types_DATETIME4` (\n" +
                "  `Type` varchar(50) NOT NULL,\n" +
                "  `Minimum_Value` datetime(6) NOT NULL,\n" +
                "  `Mid_Value` datetime(6) NOT NULL,\n" +
                "  `Maximum_Value` datetime(6) NOT NULL,\n" +
                "  `Null_Value` datetime(6) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`Type`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=latin1;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery6, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.`temporal_types_DATETIME4`(`Type` String NOT NULL ,`Minimum_Value` DateTime64(6, 0) NOT NULL ,`Mid_Value` DateTime64(6, 0) NOT NULL ,`Maximum_Value` DateTime64(6, 0) NOT NULL ,`Null_Value` Nullable(DateTime64(6, 0)),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`Type`)"));

        String createQuery1 = "CREATE TABLE `temporal_types_DATETIME4` (\n" +
                "  `Type` varchar(50) NOT NULL,\n" +
                "  `Minimum_Value` datetime(1) NOT NULL,\n" +
                "  `Mid_Value` datetime(1) NOT NULL,\n" +
                "  `Maximum_Value` datetime(1) NOT NULL,\n" +
                "  `Null_Value` datetime(1) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`Type`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=latin1;";
        StringBuffer clickHouseQuery1 = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery1, "Persons", clickHouseQuery1);
        Assert.assertTrue(clickHouseQuery1.toString().equalsIgnoreCase("CREATE TABLE employees.`temporal_types_DATETIME4`(`Type` String NOT NULL ,`Minimum_Value` DateTime64(1, 0) NOT NULL ,`Mid_Value` DateTime64(1, 0) NOT NULL ,`Maximum_Value` DateTime64(1, 0) NOT NULL ,`Null_Value` Nullable(DateTime64(1, 0)),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`Type`)"));

        String createQuery2 = "CREATE TABLE `temporal_types_DATETIME4` (\n" +
                "  `Type` varchar(50) NOT NULL,\n" +
                "  `Minimum_Value` datetime(2) NOT NULL,\n" +
                "  `Mid_Value` datetime(2) NOT NULL,\n" +
                "  `Maximum_Value` datetime(2) NOT NULL,\n" +
                "  `Null_Value` datetime(2) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`Type`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=latin1;";
        StringBuffer clickHouseQuery2 = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery2, "Persons", clickHouseQuery2);
        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase("CREATE TABLE employees.`temporal_types_DATETIME4`(`Type` String NOT NULL ,`Minimum_Value` DateTime64(2, 0) NOT NULL ,`Mid_Value` DateTime64(2, 0) NOT NULL ,`Maximum_Value` DateTime64(2, 0) NOT NULL ,`Null_Value` Nullable(DateTime64(2, 0)),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`Type`)"));


    }

    @Test
    @DisplayName("Test DateTime precision/scale conversion for tables with Primary Key")
    public void testDateTimeColumnsWithPrimaryKey() {

        // DateTime(3) with Primary Key.
        String createQuery3 = "CREATE TABLE table_1 (id INT NOT NULL PRIMARY KEY, data DATETIME(3))";
        StringBuffer clickHouseQuery3 = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery3, "Persons", clickHouseQuery3);
        Assert.assertTrue(clickHouseQuery3.toString().equalsIgnoreCase("CREATE TABLE employees.table_1(id Int32 NOT NULL ,data Nullable(DateTime64(3, 0)),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY id"));

        // DateTime(4) with Primary Key
        String createQuery4 = "CREATE TABLE table_1 (id INT NOT NULL PRIMARY KEY, data DATETIME(4))";
        StringBuffer clickHouseQuery4 = new StringBuffer();
        mySQLDDLParserService.parseSql(createQuery4, "Persons", clickHouseQuery4);
        Assert.assertTrue(clickHouseQuery4.toString().equalsIgnoreCase("CREATE TABLE employees.table_1(id Int32 NOT NULL ,data Nullable(DateTime64(4, 0)),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY id"));
    }

    @Test
    @DisplayName("Auto create table with user provided clickhouse timezone")
    public void testAutoCreateTableWithCHTimezone() {
        String createQuery6 = "CREATE TABLE `temporal_types_DATETIME4` (\n" +
                "  `Type` varchar(50) NOT NULL,\n" +
                "  `Minimum_Value` datetime(6) NOT NULL,\n" +
                "  `Mid_Value` datetime(6) NOT NULL,\n" +
                "  `Maximum_Value` datetime(6) NOT NULL,\n" +
                "  `Null_Value` datetime(6) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`Type`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=latin1;";
        StringBuffer clickHouseQuery = new StringBuffer();
        HashMap<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString(), "UTC");

        MySQLDDLParserService mySQLDDLParserService1 = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(props), "datatypes");
        mySQLDDLParserService1.parseSql(createQuery6, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE datatypes.`temporal_types_DATETIME4`(`Type` String NOT NULL ,`Minimum_Value` DateTime64(6,'UTC') NOT NULL ,`Mid_Value` DateTime64(6,'UTC') NOT NULL ,`Maximum_Value` DateTime64(6,'UTC') NOT NULL ,`Null_Value` Nullable(DateTime64(6,'UTC')),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`Type`)"));
        log.info("Create table " + clickHouseQuery);
    }

    @Test
    @DisplayName("Auto create table with user provided clickhouse timezone and uppercase datetime columns")
    public void testAutoCreateTableWithCHTimezoneUpperCaseDateTime() {
        String createQuery6 = "CREATE TABLE `temporal_types_DATETIME4` (\n" +
                "  `Type` varchar(50) NOT NULL,\n" +
                "  `Minimum_Value` DATETIME(1) NOT NULL,\n" +
                "  `Mid_Value` DATETIME(2) NOT NULL,\n" +
                "  `Maximum_Value` DATETIME(3) NOT NULL,\n" +
                "  `Null_Value` DATETIME(4) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`Type`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=latin1;";
        StringBuffer clickHouseQuery = new StringBuffer();
        HashMap<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATETIME_TIMEZONE.toString(), "UTC");

        MySQLDDLParserService mySQLDDLParserService1 = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(props), "datatypes");
        mySQLDDLParserService1.parseSql(createQuery6, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE datatypes.`temporal_types_DATETIME4`(`Type` String NOT NULL ,`Minimum_Value` DateTime64(1,'UTC') NOT NULL ,`Mid_Value` DateTime64(2,'UTC') NOT NULL ,`Maximum_Value` DateTime64(3,'UTC') NOT NULL ,`Null_Value` Nullable(DateTime64(4,'UTC')),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`Type`)"));
        log.info("Create table " + clickHouseQuery);
    }

    @Test
    public void testCreateTableAutoIncrement() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "CREATE TABLE IF NOT EXISTS 730b595f_d475_11ed_b64a_398b553542b2 (id INT AUTO_INCREMENT,x INT, PRIMARY KEY (id)) ENGINE = InnoDB;";
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);
        log.info("Create table " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE if not exists employees.730b595f_d475_11ed_b64a_398b553542b2(id Nullable(Int32),x Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (id)"));
    }

    @Test
    public void testCreateTableLike() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "CREATE TABLE new_tbl LIKE orig_tbl;";
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);
        log.info("Create table " + clickHouseQuery);
    }
    @Test
    public void testCreateTable() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "create table if not exists ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int)";
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE if not exists employees.ship_class(id Nullable(Int32),class_name Nullable(String),tonange Nullable(Decimal(10,2)),max_length Nullable(Decimal(10,2)),start_build Nullable(Int32),end_build Nullable(Int32),max_guns_size Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);

    }
    @Test
    public void testCreateTableWithNulLFields() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "create table ship_class(id int, class_name varchar(100), tonange decimal(10,2) not null, max_length decimal(65,2), start_build year, end_build year(4), max_guns_size int)";
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.ship_class(id Nullable(Int32),class_name Nullable(String),tonange Decimal(10,2) NOT NULL ,max_length Nullable(Decimal(65,2)),start_build Nullable(Int32),end_build Nullable(Int32),max_guns_size Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);

    }

    @Test
    public void testCreateTableWithPrimaryKey() {
        String createDBQuery = "CREATE TABLE IF NOT EXISTS 730b595f_d475_11ed_b64a_398b553542b2 (id INT AUTO_INCREMENT,x INT, PRIMARY KEY (id)) ENGINE = InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(createDBQuery, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE if not exists employees.730b595f_d475_11ed_b64a_398b553542b2(id Nullable(Int32),x Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (id)"));
        log.info("Create table " + clickHouseQuery);

    }
    @Test
    public void testCreateTable2() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String createDB = "CREATE TABLE `salaries` (\n" +
                "  `emp_no` int NOT NULL,\n" +
                "  `salary` int NOT NULL,\n" +
                "  `from_date` date NOT NULL,\n" +
                "  `to_date` date NOT NULL,\n" +
                "  PRIMARY KEY (`emp_no`,`from_date`),\n" +
                "  CONSTRAINT `salaries_ibfk_1` FOREIGN KEY (`emp_no`) REFERENCES `employees` (`emp_no`) ON DELETE CASCADE\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.`salaries`(`emp_no` Int32 NOT NULL ,`salary` Int32 NOT NULL ,`from_date` Date32 NOT NULL ,`to_date` Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`emp_no`,`from_date`)"));
        log.info("Create table query" + clickHouseQuery.toString());
    }

    @Test
    public void testAlterDatabaseAddColumn() {

        String clickhouseExpectedQuery = "ALTER TABLE employees.employees ADD COLUMN ssn_number Nullable(String)";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column ssn_number varchar(100)";
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY" + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testAlterAddColumnWithColumnKeyword() {

        String alterDBAddColumn = "alter table db1.table1 add entity varchar(255) , ALGORITHM=INPLACE, LOCK=NONE";
        String clickhouseExpectedQuery = "ALTER TABLE db1.table1 ADD COLUMN entity Nullable(String)";
        StringBuffer clickHouseQuery = new StringBuffer();

        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY" + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testAlterDatabaseAddColumnNullable() {

        String addColumnNullable = "ALTER TABLE employees add column ssn_number varchar(100)";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(addColumnNullable, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY" + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        //Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    // Before, After
    @Test
    public void testAlterDatabaseAddMultipleColumns1() {
        String expectedClickHouseQuery = "ALTER TABLE employees.employees ADD COLUMN ship_spec Nullable(String)  first, ADD COLUMN somecol Nullable(Int32)  after start_build";
        StringBuffer clickHouseQuery = new StringBuffer();
        String query = "alter table employees.employees add column ship_spec varchar(150) first, add somecol int after start_build, algorithm=instant;";
        mySQLDDLParserService.parseSql(query, "employees", clickHouseQuery);
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));

    }

    @Test
    public void testAlterDatabaseAddMultipleColumns() {

        String expectedClickHouseQuery = "ALTER TABLE employees.employees ADD COLUMN ssn_number Nullable(String), ADD COLUMN home_address Nullable(String)";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees.employees add column ssn_number varchar(100), add column home_address varchar(20)";
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);


        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));

    }

    @Test
    public void testAddColumnWithNull() {
        String expectedClickHouseQuery = "ALTER TABLE employees.add_test ADD COLUMN optional Nullable(Bool)  DEFAULT 0";
        String mysqlQuery = "alter table add_test add column optional bool default 0 null;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(mysqlQuery, "employees", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);
    }

    @Test
    @DisplayName("Test ALTER TABLE ADD column")
    public void testAddColumnWithNotNull() {
        String mysqlQuery = "alter table add_test add column customer_address varchar(100) not null, add column customer_name varchar(20) null;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(mysqlQuery, "add_test", clickHouseQuery);

        String expectedCHQuery = "ALTER TABLE employees.add_test ADD COLUMN customer_address String, ADD COLUMN customer_name Nullable(String)";
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);
    }

    @Test
    public void testAddDefault() {
        String expectedClickHouseQuery = "ALTER TABLE employees.add_test ADD COLUMN foo Nullable(Int32)  DEFAULT 2";
        String mysqlQuery = "ALTER TABLE add_test ADD COLUMN foo INT DEFAULT 2;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(mysqlQuery, "add_test", clickHouseQuery);

        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));
    }

    @Test
    public void testAddColumnWithoutExplicitNull() {
        String expectedClickHouseQuery = "ALTER TABLE employees.add_test ADD COLUMN foo Nullable(Int32)";
        String mysqlQuery = "ALTER TABLE add_test ADD COLUMN foo INT;";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(mysqlQuery, "add_test", clickHouseQuery);

        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));
    }



    @Test
    public void testAlterDatabaseModifyColumns() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE contacts change column last_name new_name varchar(50) NULL;";
        mySQLDDLParserService.parseSql(alterDBAddColumn, "contacts", clickHouseQuery);
        //Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE contacts MODIFY COLUMN last_name Nullable(String)"));
        log.info("CLICKHOUSE QUERY" + clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.contacts MODIFY COLUMN last_name Nullable(String) \n" +
                "ALTER TABLE employees.contacts RENAME COLUMN last_name to new_name"));

        StringBuffer clickHouseQueryNonNullable = new StringBuffer();
        String alterDBAddColumnNonNullable = "ALTER TABLE database_1.`table_fcdd63fd_0c60_11ef_a293_cfcc8bfdbf55` CHANGE COLUMN col1 new_col varchar(255)";
        mySQLDDLParserService.parseSql(alterDBAddColumnNonNullable, "contacts", clickHouseQueryNonNullable);
        //Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE contacts MODIFY COLUMN last_name Nullable(String)"));
        log.info("CLICKHOUSE QUERY" + clickHouseQueryNonNullable);
        Assert.assertTrue(clickHouseQueryNonNullable.toString().equalsIgnoreCase("ALTER TABLE database_1.`table_fcdd63fd_0c60_11ef_a293_cfcc8bfdbf55` MODIFY COLUMN col1 String\n" +
                "ALTER TABLE database_1.`table_fcdd63fd_0c60_11ef_a293_cfcc8bfdbf55` RENAME COLUMN col1 to new_col"));
    }

    @Test
    public void testAlterTableWithNotNullAndDefault() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String sql = "ALTER TABLE products ADD stocks int not null";

        mySQLDDLParserService.parseSql(sql, "products", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.products ADD COLUMN stocks Int32"));
        StringBuffer clickHouseQuery2 = new StringBuffer();

        String defaultSql = "alter table add_test add column stocks bool null default 1;";

        mySQLDDLParserService.parseSql(defaultSql, "add_test", clickHouseQuery2);
        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase("ALTER TABLE employees.add_test ADD COLUMN stocks Nullable(Bool)  DEFAULT 1"));

    }

    @Test
    public void testRenameColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test rename column stocks to options";
        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("alter table employees.add_test rename column stocks to options"));

        StringBuffer clickHouseQuery2 = new StringBuffer();
        String sql2 = "alter table employees.add_test rename column stocks to options, rename column options to stocks";
        mySQLDDLParserService.parseSql(sql2, "t2", clickHouseQuery2);

        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase(sql2));

    }

    @Test
    public void testChangeColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE employees.add_test MODIFY COLUMN stocks Bool\n" +
                "ALTER TABLE employees.add_test RENAME COLUMN stocks to options";
        String sql = "alter table add_test change column stocks options bool";
        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }

    @Test
    public void testChangeColumnFirst() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE employees.add_test MODIFY COLUMN stocks Bool first\n" +
                "ALTER TABLE employees.add_test RENAME COLUMN stocks to options";
        String sql = "alter table add_test change column stocks options bool first";
        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }

    @Test
    public void testChangeColumnAfter() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE employees.add_test MODIFY COLUMN stocks Bool after col1\n" +
                "ALTER TABLE employees.add_test RENAME COLUMN stocks to options";
        String sql = "alter table add_test change column stocks options bool after col1";
        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }

    @Test
    public void testChangeColumnWithDecimalScaleAndPrecision() {
        String sql = "alter table ship_class change column tonange tonange_new decimal(10,10)";

        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE employees.ship_class MODIFY COLUMN tonange Decimal(10,10)\n" +
                "ALTER TABLE employees.ship_class RENAME COLUMN tonange to tonange_new";

        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }
    @Test
    public void testAddConstraints() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table t2 add constraint t2_pk_constraint primary key (1c), alter column `_` set default 1;\n";
        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);


        StringBuffer clickHouseQuery2 = new StringBuffer();

        String checkConstraintSql = "ALTER TABLE orders ADD CONSTRAINT check_revenue_positive CHECK (revenue >= 0);";
        mySQLDDLParserService.parseSql(checkConstraintSql, " ", clickHouseQuery2);
    }

    @Test
    public void testAddConstraintsWithAnd() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String checkConstraintSql = "ALTER TABLE orders ADD CONSTRAINT check_revenue_positive CHECK ( (revenue>=0 and revenue<1000) or (revenue>=2000) );";
        String clickhouseExpectedQuery = "ALTER TABLE employees.orders ADD CONSTRAINT check_revenue_positive CHECK ( ( revenue >=0 and revenue <1000 ) or ( revenue >=2000 ) ) ";
        mySQLDDLParserService.parseSql(checkConstraintSql, " ", clickHouseQuery);
        log.info("CLICKHOUSE QUERY " + clickHouseQuery.toString());
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testAddPrimaryKey() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table table1 add primary key (id)";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

    }

    @Test
    public void truncateTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "truncate table add_test";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("TRUNCATE TABLE employees.add_test"));
    }

    @Test
    public void dropTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table add_test";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void dropTableIfExists() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table if exists add_test";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void dropMultipleTables() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table add_test, add_test2";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("drop table add_test,add_test2"));
    }

    @Test
    public void renameTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "rename table add_test to add_test_old";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("rename table employees.add_test to employees.add_test_old"));
    }

    @Test
    public void testAddIndex() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test add index if not exists ix_add_test_col1 using btree (col1) comment 'test index';\n";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);


    }


    @Test
    public void testDropConstraint() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table table1 add primary key (id)";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

    }

    @Test
    public void testAlterColumnAddDefault() {

        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test alter flag add default 1";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);


    }

    @Test
    public void testCreateDatabase() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "create database test_ddl";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("create database if not exists test_ddl"));
    }

    @Test
    public void testDropColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table employees.add_test drop column col1";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("alter table employees.add_test drop column col1"));

        String multipleDropColumnsSql = "ALTER TABLE fffe3e80f_d197_11ee_836a_19710b02e0b5 DROP COLUMN new_col1, DROP COLUMN new_col2, DROP COLUMN new_col3";

        StringBuffer multipleDropColumnCHQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(multipleDropColumnsSql, "", multipleDropColumnCHQuery);

        Assert.assertTrue(multipleDropColumnCHQuery.toString().equalsIgnoreCase("ALTER TABLE employees.fffe3e80f_d197_11ee_836a_19710b02e0b5 DROP COLUMN new_col1, DROP COLUMN new_col2, DROP COLUMN new_col3"));

    }

    @Test
    public void testDropColumnWithoutColumnSyntax() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table `leads`  drop `country`";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("alter table employees.`leads` drop column `country`"));
    }

    @Test
    public void renameMultipleTables() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "rename /* gh-ost */ table `trade_prod`.`enriched_trade` to `trade_prod`.`_enriched_trade_del`, `trade_prod`.`_enriched_trade_gho` to `trade_prod`.`enriched_trade`\n";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("RENAME TABLE `trade_prod`.`enriched_trade` to `trade_prod`.`_enriched_trade_del`,`trade_prod`.`_enriched_trade_gho` to `trade_prod`.`enriched_trade`"));
    }
    @Test
    public void alterTableRenameTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "ALTER TABLE test_table rename to test_table_new";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("RENAME TABLE employees.test_table to employees.test_table_new"));
    }

    @Test
    public void testGeneratedColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "CREATE TABLE employees.contacts (fullname varchar(101) GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)), email VARCHAR(100) NOT NULL);";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.contacts(fullname Nullable(String) MATERIALIZED CONCAT(first_name,' ',last_name),email String NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"));
    }

    @Test
    public void testSourceWithIsDeletedColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "create table new_table(col1 varchar(255), col2 int, is_deleted int, _sign int);";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.new_table(col1 Nullable(String),col2 Nullable(Int32),is_deleted Nullable(Int32),_sign Nullable(Int32),`_version` UInt64,`__is_deleted` UInt8) Engine=ReplacingMergeTree(_version,__is_deleted) ORDER BY tuple()"));
    }

    @ParameterizedTest
    @CsvSource({
            "ALTER TABLE test_table rename to test_table_new, false",
            "drop table if exists table1, true",
            "drop database db1, true",
            "drop database db2 if exists, true",
            "truncate table table1, true",
            "create database test_ddl, false",
            "ALTER TABLE add_test MODIFY COLUMN stocks Bool after col1, ALTER TABLE add_test RENAME COLUMN stocks to options, false"
    })
    @DisplayName("Test to validate if the statement is flagged as DROP or TRUNCATE")
    public void checkIfDropOrTruncate(String sql, boolean expectedResult) {
        StringBuffer clickHouseQuery = new StringBuffer();

        AtomicBoolean isDropOrTruncate = new AtomicBoolean();
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery, isDropOrTruncate);
        Assert.assertTrue(isDropOrTruncate.get() == expectedResult);

    }

    @Test
    public void testReplicatedReplacingMergeTreeWithoutIsDeletedColumn() {
        HashMap configMap = new HashMap();
        configMap.put(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(configMap);
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(config, "datatypes");
        StringBuffer clickHouseQuery = new StringBuffer();
        AtomicBoolean isDropOrTruncate = new AtomicBoolean();

        String sql = "CREATE TABLE temporal_types_TIMESTAMP1(`Mid_Value` timestamp(1) NOT NULL) ENGINE=InnoDB;";
        mySQLDDLParserService.parseSql(sql, "temporal_types_DATETIME4", clickHouseQuery, isDropOrTruncate);

        String expectedResult = "CREATE TABLE datatypes.temporal_types_TIMESTAMP1 ON CLUSTER `{cluster}`(`Mid_Value` DateTime64(1, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8)Engine=ReplicatedReplacingMergeTree(_version, is_deleted) ORDER BY tuple()";
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedResult));


    }

    @ParameterizedTest
    @CsvSource(
            value = {"CREATE TABLE temporal_types_TIMESTAMP1(`Mid_Value` timestamp(1) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP1(`Mid_Value` DateTime64(1, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
            "CREATE TABLE temporal_types_TIMESTAMP2(`Mid_Value` timestamp(2) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP2(`Mid_Value` DateTime64(2, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
            "CREATE TABLE temporal_types_TIMESTAMP3(`Mid_Value` timestamp(3) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP3(`Mid_Value` DateTime64(3, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
            "CREATE TABLE temporal_types_TIMESTAMP4(`Mid_Value` timestamp(4) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP4(`Mid_Value` DateTime64(4, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
            "CREATE TABLE temporal_types_TIMESTAMP5(`Mid_Value` timestamp(5) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP5(`Mid_Value` DateTime64(5, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
            "CREATE TABLE temporal_types_TIMESTAMP6(`Mid_Value` timestamp(6) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP6(`Mid_Value` DateTime64(6, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"}
    ,delimiter = ':')
    @DisplayName("Test to validate if the timestamp data type precision is maintained from MySQL to ClickHouse")
    public void checkIfTimestampDataTypePrecisionIsMaintained(String sql, String expectedResult) {
        StringBuffer clickHouseQuery = new StringBuffer();

        AtomicBoolean isDropOrTruncate = new AtomicBoolean();
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery, isDropOrTruncate);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedResult));

    }

    @ParameterizedTest
    @CsvSource(
            value = {"CREATE TABLE temporal_types_TIMESTAMP1(`Mid_Value` TIMESTAMP(1) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP1(`Mid_Value` DateTime64(1, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
                    "CREATE TABLE temporal_types_TIMESTAMP2(`Mid_Value` TIMESTAMP(2) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP2(`Mid_Value` DateTime64(2, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
                    "CREATE TABLE temporal_types_TIMESTAMP3(`Mid_Value` TIMESTAMP(3) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP3(`Mid_Value` DateTime64(3, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
                    "CREATE TABLE temporal_types_TIMESTAMP4(`Mid_Value` TIMESTAMP(4) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP4(`Mid_Value` DateTime64(4, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
                    "CREATE TABLE temporal_types_TIMESTAMP5(`Mid_Value` TIMESTAMP(5) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP5(`Mid_Value` DateTime64(5, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()",
                    "CREATE TABLE temporal_types_TIMESTAMP6(`Mid_Value` TIMESTAMP(6) NOT NULL) ENGINE=InnoDB;: CREATE TABLE employees.temporal_types_TIMESTAMP6(`Mid_Value` DateTime64(6, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"}
            ,delimiter = ':')
    @DisplayName("Test to validate if the timestamp data type precision(uppercase timestamp is maintained from MySQL to ClickHouse")
    public void checkIfTimestampDataTypeUpperCasePrecisionIsMaintained(String sql, String expectedResult) {
        StringBuffer clickHouseQuery = new StringBuffer();

        AtomicBoolean isDropOrTruncate = new AtomicBoolean();
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery, isDropOrTruncate);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedResult));

    }
    @Test
    public void testAlterDatabaseAddColumnEnum() {
        String clickhouseExpectedQuery = "ALTER TABLE employees.employees ADD COLUMN gender String";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column gender ENUM ('M','F') NOT NULL";
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testAlterDatabaseAddColumnJson() {
        String clickhouseExpectedQuery = "ALTER TABLE employees.employees ADD COLUMN data String";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column data JSON NOT NULL";
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testRenameIsDeletedColumn() {
        String sql = "CREATE TABLE `city` (\n" +
                "  `ID` int NOT NULL AUTO_INCREMENT,\n" +
                "  `Name` char(35) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',\n" +
                "  `CountryCode` char(3) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',\n" +
                "  `District` char(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',\n" +
                "  `Population` int NOT NULL DEFAULT '0',\n" +
                "  `is_deleted` tinyint(1) DEFAULT '0',\n" +
                "  PRIMARY KEY (`ID`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;";

        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(sql, "employees", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(
                "CREATE TABLE employees.`city`(`ID` Int32 NOT NULL ,`Name` String NOT NULL ,`CountryCode` String NOT NULL ,`District` String NOT NULL ,`Population` Int32 NOT NULL ,`is_deleted` Nullable(Int16),`_version` UInt64,`__is_deleted` UInt8) Engine=ReplacingMergeTree(_version,__is_deleted) ORDER BY (`ID`)"));


        String sqlWithoutBackticks = "create table city(id int not null auto_increment, Name char(35) , is_deleted tinyint(1) DEFAULT 0, primary key(id))";

        StringBuffer clickHouseQuery2 = new StringBuffer();
        mySQLDDLParserService.parseSql(sqlWithoutBackticks, "employees", clickHouseQuery2);

        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase(
                "CREATE TABLE employees.city(id Int32 NOT NULL ,Name Nullable(String),is_deleted Nullable(Int16),`_version` UInt64,`__is_deleted` UInt8) Engine=ReplacingMergeTree(_version,__is_deleted) ORDER BY (id)"));
    }
//    @Test
//    public void deleteData() {
//        String sql = "DELETE FROM Customers WHERE CustomerName='Alfreds Futterkiste'";
//        StringBuffer clickHouseQuery = new StringBuffer();
//
//        AtomicBoolean isDropOrTruncate = new AtomicBoolean();
//        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
//        mySQLDDLParserService2.parseSql(sql, "", clickHouseQuery, isDropOrTruncate);
//
//        System.out.println("Clickhouse query" + clickHouseQuery);
//
//    }

//    @Test
//    public void testDropDatabase() {
//        StringBuffer clickHouseQuery = new StringBuffer();
//
//        String sql = "drop database if exists employees";
//        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
//        mySQLDDLParserService2.parseSql(sql, "", clickHouseQuery);
//
//        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
//    }
}

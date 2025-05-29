package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP;


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
    public void testCreateTableWithSetDataType() {

        String createQuery = "CREATE TABLE example(options SET('a', 'b', 'c', 'd'))";
        StringBuffer clickHouseQuery = new StringBuffer();

        mySQLDDLParserService.parseSql(createQuery, "test", clickHouseQuery);
        Assert.assertTrue("CREATE TABLE employees.example(options Nullable(String),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()".equalsIgnoreCase(clickHouseQuery.toString()));
        ;
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

//     @Test
//     public void testAlterTableWithAnalyzePartition() {
//
//         String alterTableQuery = "alter  table  std_txn_agg analyze partition p20231229";
//         StringBuffer clickHouseQuery = new StringBuffer();
//         mySQLDDLParserService.parseSql(alterTableQuery, "Persons",  clickHouseQuery);
//         Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.std_txn_agg ANALYZE PARTITION p20231229"));
//         log.info("Alter table " + clickHouseQuery);
//     }

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
    public void testDropDatabaseWithOverrideMap() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String dropQuery = "DROP DATABASE `test`";
        Map<String, String> config = new HashMap<>();
        config.put(CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), "test:test2");

        MySQLDDLParserService mySQLDDLParserService1 = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(config), "test");
        mySQLDDLParserService1.parseSql(dropQuery, "test", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("DROP DATABASE IF EXISTS test2"));
        log.info("Drop database " + clickHouseQuery);
    }
    @Test
    public void testCreateTableWithReplicatedReplacingMergeTree() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "CREATE TABLE IF NOT EXISTS mysql1.`table_7220f7bd_8c8c_11ef_94db_67ff65f7711d` (id INT NOT NULL,col1 varchar(255), col2 int, PRIMARY KEY (id)) ENGINE = InnoDB";

        // Set ClickHouse sink connector config to set replicated tables.
        Map<String, String> config = new HashMap<>();
        config.put(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        config.put(CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), "mysql1:ch1");

        ClickHouseSinkConnectorConfig clickHouseSinkConnectorConfig = new ClickHouseSinkConnectorConfig(config);
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(clickHouseSinkConnectorConfig, "ch1");
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);
        log.info("Create table " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE if not exists ch1.`table_7220f7bd_8c8c_11ef_94db_67ff65f7711d` ON CLUSTER `{cluster}`(id Int32 NOT NULL ,col1 Nullable(String),col2 Nullable(Int32),`_version` UInt64,`is_deleted` UInt8)Engine=ReplicatedReplacingMergeTree(_version, is_deleted) ORDER BY (id)"));

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
        Assert.assertTrue("CREATE TABLE employees.new_tbl AS employees.orig_tbl".equalsIgnoreCase(clickHouseQuery.toString()));
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
        String clickhouseExpectedQuery = "ALTER TABLE employees.table1 ADD COLUMN entity Nullable(String)";
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

        String expectedCHQuery = "ALTER TABLE employees.add_test ADD COLUMN customer_address Nullable(String), ADD COLUMN customer_name Nullable(String)";
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
    public void testAlterTableModifyColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterTableModifyColumn = "ALTER TABLE employees.add_test MODIFY COLUMN col1 INT;";
        mySQLDDLParserService.parseSql(alterTableModifyColumn, "add_test", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.add_test MODIFY COLUMN col1 Int32"));
    }

    @Test
    public void testAlterTableModifyAddColumn() {
        StringBuffer clickHouseQuery2 = new StringBuffer();
        String alterTableModifyColumn2 = "alter table  test1 add  column `vendor_folder` varchar(128) COLLATE latin1_general_cs NOT NULL after expected_arrival_time";
        mySQLDDLParserService.parseSql(alterTableModifyColumn2, "add_test", clickHouseQuery2);
        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase("ALTER TABLE employees.test1 ADD COLUMN `vendor_folder` Nullable(String)  after expected_arrival_time"));
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
        Assert.assertTrue(clickHouseQueryNonNullable.toString().equalsIgnoreCase("ALTER TABLE employees.`table_fcdd63fd_0c60_11ef_a293_cfcc8bfdbf55` MODIFY COLUMN col1 String\n" +
                "ALTER TABLE database_1.`table_fcdd63fd_0c60_11ef_a293_cfcc8bfdbf55` RENAME COLUMN col1 to new_col"));
    }

    @Test
    public void testAlterTableWithNotNullAndDefault() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String sql = "ALTER TABLE products ADD stocks int not null";

        mySQLDDLParserService.parseSql(sql, "products", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.products ADD COLUMN stocks Nullable(Int32)"));
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
    public void testRenameColumnWithDatabaseOverride() {

        Map<String, String> props = new HashMap<>();
        props.put(CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), "mysql1:ch1");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(config, "ch1");

        StringBuffer clickHouseQuery = new StringBuffer();
        String sql = "ALTER TABLE mysql1.table_01dacfed_9875_11ef_b2c5_e7434a0f1a60 RENAME COLUMN col1 to new_col";
        mySQLDDLParserService.parseSql(sql, "t2", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE ch1.table_01dacfed_9875_11ef_b2c5_e7434a0f1a60 RENAME COLUMN col1 to new_col"));
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
    public void testDropContraints() {
        StringBuffer clickhouseQuery = new StringBuffer();

        String dropConstraintsSql = "alter table employees drop CONSTRAINT employees_ibfk_2";
        mySQLDDLParserService.parseSql(dropConstraintsSql, "employees", clickhouseQuery);

        Assert.assertTrue(clickhouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.employees DROP CONSTRAINT employees_ibfk_2"));
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
    //    Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("alter table employees.table1 add primary key (id)"));
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
    public void renameTableWithoutTableKeyword() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "ALTER TABLE employees.old_table RENAME employees.new_table";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("rename table employees.old_table to employees.new_table"));
    }

    @Test
    public void renameTableWithTableKeyword() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String sql = "ALTER TABLE old_table RENAME new_table";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("rename table employees.old_table to employees.new_table"));
    }

    @Test
    public void renameTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "rename table add_test to add_test_old";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("rename table employees.add_test to employees.add_test_old"));
    }

    @Test
    public void testRenameTableWithDatabaseOverride() {
        StringBuffer clickHouseQuery = new StringBuffer();

        HashMap<String, String> props = new HashMap<>();
        props.put(CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(), "employees:employees2, products:productsnew");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(props);
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(config, "employees2");

        String sql = "rename table employees.add_test to employees.add_test_old";

        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("rename table employees2.add_test to employees2.add_test_old"));
    }

//    @Test
//    public void testAddIndex() {
//        StringBuffer clickHouseQuery = new StringBuffer();
//
//        String sql = "alter table add_test add index if not exists ix_add_test_col1 using btree (col1) comment 'test index';\n";
//        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);
//
//
//    }


    @Test
    public void testDropConstraint() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table table1 add primary key (id)";
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
    public void testCreateDatabaseReplicated() {
        StringBuffer clickHouseQuery = new StringBuffer();

        HashMap<String, String> map = new HashMap<>();
        map.put(ClickHouseSinkConnectorConfigVariables.AUTO_CREATE_TABLES_REPLICATED.toString(), "true");
        ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(map);

        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService(config, "test");
        String sql = "create database if not exists repl_test_ddl";
        mySQLDDLParserService.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("create database if not exists repl_test_ddl on cluster `{cluster}`"));
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

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("RENAME TABLE employees.`enriched_trade` to employees.`_enriched_trade_del`,employees.`_enriched_trade_gho` to employees.`enriched_trade`"));
    }
    @Test
    public void alterTableRenameTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "ALTER TABLE test_table rename to test_table_new";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("RENAME TABLE employees.test_table to employees.test_table_new"));
    }

    @Test
    public void testAlterTableColumnWithComment() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "ALTER TABLE test_table ADD COLUMN col1 varchar(255) COMMENT 'test column';";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.test_table ADD COLUMN col1 Nullable(String)"));
    }

    @Test
    public void testAlterTableColumnWithCommentAndDecimalScale() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "ALTER TABLE test_table ADD COLUMN col1 decimal(10,2) COMMENT 'test column';";
        mySQLDDLParserService.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE employees.test_table ADD COLUMN col1 Nullable(Decimal(10,2))"));
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
        String clickhouseExpectedQuery = "ALTER TABLE employees.employees ADD COLUMN gender Nullable(String)";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column gender ENUM ('M','F') NOT NULL";
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testAlterDatabaseAddColumnJson() {
        String clickhouseExpectedQuery = "ALTER TABLE employees.employees ADD COLUMN data Nullable(String)";
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

    @Test
    public void testGhostSQL() {
        String sql = " alter /* gh-ost */ table `p_prod`.`_j_failed_s_g` REMOVE PARTITIONING;\n";
        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(sql, "employees", clickHouseQuery);

        //Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("alter table `p_prod`.`_j_failed_s_g` REMOVE PARTITIONING"));

        String createTableQuery = "create /* gh-ost */ table `p_prod`.`_j_failed_s_g`(id int auto_increment primary key)engine=InnoDB comment='ghost-cut-over'";

        StringBuffer clickHouseQuery2 = new StringBuffer();
        mySQLDDLParserService.parseSql(createTableQuery, "employees", clickHouseQuery2);

        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase("CREATE TABLE employees.`_j_failed_s_g`(id Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY id"));
    }

    public void testCreateDefiner() {
        String sql = "CREATE DEFINER=`bcadmin`@`%` PROCEDURE `sp_next_available_otc_instance_strategy_id`()\n" +
                "begin\n" +
                "  select min(st.value) as strategy_id\n" +
                "  from SEQUENCE_TABLE(100000) st\n" +
                "  join btc_quant.stratId_ranges r on r.stratId0 <= st.value and st.value < r.stratId1\n" +
                "  left join otc_instance i on i.strategy_id=st.value\n" +
                "  where r.category = 'otc' and now() between validFromDate and coalesce(validToDate, now())\n" +
                "  and i.strategy_id is null;\n" +
                "end";

        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(sql, "employees", clickHouseQuery);

        // Just validates that the debezium parsor does not throw an error
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(""));
    }

    @Test
    public void testCreateTableWithPartitionByRange() {

        String sql2 = "CREATE TABLE `clearing_position_incomplete_detail` (\n" +
                "  `clearing_position_incomplete_detail_id` bigint unsigned NOT NULL AUTO_INCREMENT,\n" +
                "  `clearing_date` date NOT NULL,\n" +
                "  `incomplete_reason_id` smallint unsigned NOT NULL,\n" +
                "  `incomplete_lookup_type_id` smallint unsigned NOT NULL,\n" +
                "  `clearing_position_id` bigint DEFAULT NULL,\n" +
                "  `ref_lookup_db_time` datetime(6) NOT NULL,\n" +
                "  PRIMARY KEY (`clearing_position_incomplete_detail_id`,`clearing_date`),\n" +
                "  UNIQUE KEY `clearing_position_incomplete_detail_uq1` (`clearing_date`,`incomplete_reason_id`,`incomplete_lookup_type_id`,`clearing_position_id`),\n" +
                "  KEY `clearing_position_incomplete_detail_idx1` (`clearing_position_id`,`clearing_date`),\n" +
                "  KEY `clearing_position_incomplete_detail_idx2` (`incomplete_reason_id`),\n" +
                "  KEY `clearing_position_incomplete_detail_idx3` (`incomplete_lookup_type_id`)\n" +
                ") ENGINE=InnoDB AUTO_INCREMENT=2364061321790051335 DEFAULT CHARSET=latin1 COLLATE=latin1_general_cs STATS_SAMPLE_PAGES=200\n" +
                "/*!50500 PARTITION BY RANGE  COLUMNS(clearing_date)\n" +
                "(PARTITION p20201231 VALUES LESS THAN ('2021-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20211230 VALUES LESS THAN ('2021-12-31') ENGINE = InnoDB,\n" +
                " PARTITION p20211231 VALUES LESS THAN ('2022-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20221229 VALUES LESS THAN ('2022-12-30') ENGINE = InnoDB,\n" +
                " PARTITION p20221230 VALUES LESS THAN ('2023-01-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230102 VALUES LESS THAN ('2023-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230103 VALUES LESS THAN ('2023-01-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230104 VALUES LESS THAN ('2023-01-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230105 VALUES LESS THAN ('2023-01-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230106 VALUES LESS THAN ('2023-01-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230109 VALUES LESS THAN ('2023-01-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230110 VALUES LESS THAN ('2023-01-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230111 VALUES LESS THAN ('2023-01-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230112 VALUES LESS THAN ('2023-01-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230113 VALUES LESS THAN ('2023-01-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230116 VALUES LESS THAN ('2023-01-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230117 VALUES LESS THAN ('2023-01-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230118 VALUES LESS THAN ('2023-01-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230119 VALUES LESS THAN ('2023-01-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230120 VALUES LESS THAN ('2023-01-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230123 VALUES LESS THAN ('2023-01-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230124 VALUES LESS THAN ('2023-01-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230125 VALUES LESS THAN ('2023-01-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230126 VALUES LESS THAN ('2023-01-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230127 VALUES LESS THAN ('2023-01-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230130 VALUES LESS THAN ('2023-01-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230131 VALUES LESS THAN ('2023-02-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230201 VALUES LESS THAN ('2023-02-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230202 VALUES LESS THAN ('2023-02-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230203 VALUES LESS THAN ('2023-02-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230206 VALUES LESS THAN ('2023-02-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230207 VALUES LESS THAN ('2023-02-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230208 VALUES LESS THAN ('2023-02-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230209 VALUES LESS THAN ('2023-02-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230210 VALUES LESS THAN ('2023-02-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230213 VALUES LESS THAN ('2023-02-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230214 VALUES LESS THAN ('2023-02-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230215 VALUES LESS THAN ('2023-02-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230216 VALUES LESS THAN ('2023-02-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230217 VALUES LESS THAN ('2023-02-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230220 VALUES LESS THAN ('2023-02-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230221 VALUES LESS THAN ('2023-02-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230222 VALUES LESS THAN ('2023-02-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230223 VALUES LESS THAN ('2023-02-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230224 VALUES LESS THAN ('2023-02-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230227 VALUES LESS THAN ('2023-02-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230228 VALUES LESS THAN ('2023-03-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230301 VALUES LESS THAN ('2023-03-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230302 VALUES LESS THAN ('2023-03-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230303 VALUES LESS THAN ('2023-03-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230306 VALUES LESS THAN ('2023-03-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230307 VALUES LESS THAN ('2023-03-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230308 VALUES LESS THAN ('2023-03-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230309 VALUES LESS THAN ('2023-03-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230310 VALUES LESS THAN ('2023-03-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230313 VALUES LESS THAN ('2023-03-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230314 VALUES LESS THAN ('2023-03-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230315 VALUES LESS THAN ('2023-03-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230316 VALUES LESS THAN ('2023-03-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230317 VALUES LESS THAN ('2023-03-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230320 VALUES LESS THAN ('2023-03-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230321 VALUES LESS THAN ('2023-03-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230322 VALUES LESS THAN ('2023-03-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230323 VALUES LESS THAN ('2023-03-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230324 VALUES LESS THAN ('2023-03-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230327 VALUES LESS THAN ('2023-03-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230328 VALUES LESS THAN ('2023-03-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230329 VALUES LESS THAN ('2023-03-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230330 VALUES LESS THAN ('2023-03-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230331 VALUES LESS THAN ('2023-04-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230403 VALUES LESS THAN ('2023-04-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230404 VALUES LESS THAN ('2023-04-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230405 VALUES LESS THAN ('2023-04-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230406 VALUES LESS THAN ('2023-04-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230407 VALUES LESS THAN ('2023-04-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230410 VALUES LESS THAN ('2023-04-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230411 VALUES LESS THAN ('2023-04-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230412 VALUES LESS THAN ('2023-04-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230413 VALUES LESS THAN ('2023-04-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230414 VALUES LESS THAN ('2023-04-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230417 VALUES LESS THAN ('2023-04-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230418 VALUES LESS THAN ('2023-04-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230419 VALUES LESS THAN ('2023-04-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230420 VALUES LESS THAN ('2023-04-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230421 VALUES LESS THAN ('2023-04-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230424 VALUES LESS THAN ('2023-04-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230425 VALUES LESS THAN ('2023-04-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230426 VALUES LESS THAN ('2023-04-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230427 VALUES LESS THAN ('2023-04-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230428 VALUES LESS THAN ('2023-05-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230501 VALUES LESS THAN ('2023-05-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230502 VALUES LESS THAN ('2023-05-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230503 VALUES LESS THAN ('2023-05-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230504 VALUES LESS THAN ('2023-05-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230505 VALUES LESS THAN ('2023-05-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230508 VALUES LESS THAN ('2023-05-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230509 VALUES LESS THAN ('2023-05-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230510 VALUES LESS THAN ('2023-05-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230511 VALUES LESS THAN ('2023-05-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230512 VALUES LESS THAN ('2023-05-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230515 VALUES LESS THAN ('2023-05-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230516 VALUES LESS THAN ('2023-05-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230517 VALUES LESS THAN ('2023-05-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230518 VALUES LESS THAN ('2023-05-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230519 VALUES LESS THAN ('2023-05-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230522 VALUES LESS THAN ('2023-05-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230523 VALUES LESS THAN ('2023-05-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230524 VALUES LESS THAN ('2023-05-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230525 VALUES LESS THAN ('2023-05-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230526 VALUES LESS THAN ('2023-05-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230529 VALUES LESS THAN ('2023-05-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230530 VALUES LESS THAN ('2023-05-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230531 VALUES LESS THAN ('2023-06-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230601 VALUES LESS THAN ('2023-06-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230602 VALUES LESS THAN ('2023-06-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230605 VALUES LESS THAN ('2023-06-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230606 VALUES LESS THAN ('2023-06-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230607 VALUES LESS THAN ('2023-06-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230608 VALUES LESS THAN ('2023-06-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230609 VALUES LESS THAN ('2023-06-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230612 VALUES LESS THAN ('2023-06-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230613 VALUES LESS THAN ('2023-06-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230614 VALUES LESS THAN ('2023-06-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230615 VALUES LESS THAN ('2023-06-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230616 VALUES LESS THAN ('2023-06-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230619 VALUES LESS THAN ('2023-06-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230620 VALUES LESS THAN ('2023-06-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230621 VALUES LESS THAN ('2023-06-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230622 VALUES LESS THAN ('2023-06-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230623 VALUES LESS THAN ('2023-06-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230626 VALUES LESS THAN ('2023-06-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230627 VALUES LESS THAN ('2023-06-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230628 VALUES LESS THAN ('2023-06-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230629 VALUES LESS THAN ('2023-06-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230630 VALUES LESS THAN ('2023-07-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230703 VALUES LESS THAN ('2023-07-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230704 VALUES LESS THAN ('2023-07-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230705 VALUES LESS THAN ('2023-07-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230706 VALUES LESS THAN ('2023-07-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230707 VALUES LESS THAN ('2023-07-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230710 VALUES LESS THAN ('2023-07-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230711 VALUES LESS THAN ('2023-07-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230712 VALUES LESS THAN ('2023-07-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230713 VALUES LESS THAN ('2023-07-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230714 VALUES LESS THAN ('2023-07-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230717 VALUES LESS THAN ('2023-07-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230718 VALUES LESS THAN ('2023-07-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230719 VALUES LESS THAN ('2023-07-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230720 VALUES LESS THAN ('2023-07-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230721 VALUES LESS THAN ('2023-07-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230724 VALUES LESS THAN ('2023-07-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230725 VALUES LESS THAN ('2023-07-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230726 VALUES LESS THAN ('2023-07-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230727 VALUES LESS THAN ('2023-07-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230728 VALUES LESS THAN ('2023-07-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230731 VALUES LESS THAN ('2023-08-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230801 VALUES LESS THAN ('2023-08-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230802 VALUES LESS THAN ('2023-08-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230803 VALUES LESS THAN ('2023-08-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230804 VALUES LESS THAN ('2023-08-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230807 VALUES LESS THAN ('2023-08-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230808 VALUES LESS THAN ('2023-08-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230809 VALUES LESS THAN ('2023-08-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230810 VALUES LESS THAN ('2023-08-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230811 VALUES LESS THAN ('2023-08-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230814 VALUES LESS THAN ('2023-08-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230815 VALUES LESS THAN ('2023-08-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230816 VALUES LESS THAN ('2023-08-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230817 VALUES LESS THAN ('2023-08-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230818 VALUES LESS THAN ('2023-08-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230821 VALUES LESS THAN ('2023-08-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230822 VALUES LESS THAN ('2023-08-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230823 VALUES LESS THAN ('2023-08-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230824 VALUES LESS THAN ('2023-08-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230825 VALUES LESS THAN ('2023-08-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230828 VALUES LESS THAN ('2023-08-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230829 VALUES LESS THAN ('2023-08-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230830 VALUES LESS THAN ('2023-08-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230831 VALUES LESS THAN ('2023-09-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230901 VALUES LESS THAN ('2023-09-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230904 VALUES LESS THAN ('2023-09-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230905 VALUES LESS THAN ('2023-09-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230906 VALUES LESS THAN ('2023-09-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230907 VALUES LESS THAN ('2023-09-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230908 VALUES LESS THAN ('2023-09-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230911 VALUES LESS THAN ('2023-09-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230912 VALUES LESS THAN ('2023-09-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230913 VALUES LESS THAN ('2023-09-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230914 VALUES LESS THAN ('2023-09-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230915 VALUES LESS THAN ('2023-09-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230918 VALUES LESS THAN ('2023-09-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230919 VALUES LESS THAN ('2023-09-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230920 VALUES LESS THAN ('2023-09-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230921 VALUES LESS THAN ('2023-09-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230922 VALUES LESS THAN ('2023-09-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230925 VALUES LESS THAN ('2023-09-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230926 VALUES LESS THAN ('2023-09-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230927 VALUES LESS THAN ('2023-09-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230928 VALUES LESS THAN ('2023-09-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230929 VALUES LESS THAN ('2023-10-02') ENGINE = InnoDB,\n" +
                " PARTITION p20231002 VALUES LESS THAN ('2023-10-03') ENGINE = InnoDB,\n" +
                " PARTITION p20231003 VALUES LESS THAN ('2023-10-04') ENGINE = InnoDB,\n" +
                " PARTITION p20231004 VALUES LESS THAN ('2023-10-05') ENGINE = InnoDB,\n" +
                " PARTITION p20231005 VALUES LESS THAN ('2023-10-06') ENGINE = InnoDB,\n" +
                " PARTITION p20231006 VALUES LESS THAN ('2023-10-09') ENGINE = InnoDB,\n" +
                " PARTITION p20231009 VALUES LESS THAN ('2023-10-10') ENGINE = InnoDB,\n" +
                " PARTITION p20231010 VALUES LESS THAN ('2023-10-11') ENGINE = InnoDB,\n" +
                " PARTITION p20231011 VALUES LESS THAN ('2023-10-12') ENGINE = InnoDB,\n" +
                " PARTITION p20231012 VALUES LESS THAN ('2023-10-13') ENGINE = InnoDB,\n" +
                " PARTITION p20231013 VALUES LESS THAN ('2023-10-16') ENGINE = InnoDB,\n" +
                " PARTITION p20231016 VALUES LESS THAN ('2023-10-17') ENGINE = InnoDB,\n" +
                " PARTITION p20231017 VALUES LESS THAN ('2023-10-18') ENGINE = InnoDB,\n" +
                " PARTITION p20231018 VALUES LESS THAN ('2023-10-19') ENGINE = InnoDB,\n" +
                " PARTITION p20231019 VALUES LESS THAN ('2023-10-20') ENGINE = InnoDB,\n" +
                " PARTITION p20231020 VALUES LESS THAN ('2023-10-23') ENGINE = InnoDB,\n" +
                " PARTITION p20231023 VALUES LESS THAN ('2023-10-24') ENGINE = InnoDB,\n" +
                " PARTITION p20231024 VALUES LESS THAN ('2023-10-25') ENGINE = InnoDB,\n" +
                " PARTITION p20231025 VALUES LESS THAN ('2023-10-26') ENGINE = InnoDB,\n" +
                " PARTITION p20231026 VALUES LESS THAN ('2023-10-27') ENGINE = InnoDB,\n" +
                " PARTITION p20231027 VALUES LESS THAN ('2023-10-30') ENGINE = InnoDB,\n" +
                " PARTITION p20231030 VALUES LESS THAN ('2023-10-31') ENGINE = InnoDB,\n" +
                " PARTITION p20231031 VALUES LESS THAN ('2023-11-01') ENGINE = InnoDB,\n" +
                " PARTITION p20231101 VALUES LESS THAN ('2023-11-02') ENGINE = InnoDB,\n" +
                " PARTITION p20231102 VALUES LESS THAN ('2023-11-03') ENGINE = InnoDB,\n" +
                " PARTITION p20231103 VALUES LESS THAN ('2023-11-06') ENGINE = InnoDB,\n" +
                " PARTITION p20231106 VALUES LESS THAN ('2023-11-07') ENGINE = InnoDB,\n" +
                " PARTITION p20231107 VALUES LESS THAN ('2023-11-08') ENGINE = InnoDB,\n" +
                " PARTITION p20231108 VALUES LESS THAN ('2023-11-09') ENGINE = InnoDB,\n" +
                " PARTITION p20231109 VALUES LESS THAN ('2023-11-10') ENGINE = InnoDB,\n" +
                " PARTITION p20231110 VALUES LESS THAN ('2023-11-13') ENGINE = InnoDB,\n" +
                " PARTITION p20231113 VALUES LESS THAN ('2023-11-14') ENGINE = InnoDB,\n" +
                " PARTITION p20231114 VALUES LESS THAN ('2023-11-15') ENGINE = InnoDB,\n" +
                " PARTITION p20231115 VALUES LESS THAN ('2023-11-16') ENGINE = InnoDB,\n" +
                " PARTITION p20231116 VALUES LESS THAN ('2023-11-17') ENGINE = InnoDB,\n" +
                " PARTITION p20231117 VALUES LESS THAN ('2023-11-20') ENGINE = InnoDB,\n" +
                " PARTITION p20231120 VALUES LESS THAN ('2023-11-21') ENGINE = InnoDB,\n" +
                " PARTITION p20231121 VALUES LESS THAN ('2023-11-22') ENGINE = InnoDB,\n" +
                " PARTITION p20231122 VALUES LESS THAN ('2023-11-23') ENGINE = InnoDB,\n" +
                " PARTITION p20231123 VALUES LESS THAN ('2023-11-24') ENGINE = InnoDB,\n" +
                " PARTITION p20231124 VALUES LESS THAN ('2023-11-27') ENGINE = InnoDB,\n" +
                " PARTITION p20231127 VALUES LESS THAN ('2023-11-28') ENGINE = InnoDB,\n" +
                " PARTITION p20231128 VALUES LESS THAN ('2023-11-29') ENGINE = InnoDB,\n" +
                " PARTITION p20231129 VALUES LESS THAN ('2023-11-30') ENGINE = InnoDB,\n" +
                " PARTITION p20231130 VALUES LESS THAN ('2023-12-01') ENGINE = InnoDB,\n" +
                " PARTITION p20231201 VALUES LESS THAN ('2023-12-04') ENGINE = InnoDB,\n" +
                " PARTITION p20231204 VALUES LESS THAN ('2023-12-05') ENGINE = InnoDB,\n" +
                " PARTITION p20231205 VALUES LESS THAN ('2023-12-06') ENGINE = InnoDB,\n" +
                " PARTITION p20231206 VALUES LESS THAN ('2023-12-07') ENGINE = InnoDB,\n" +
                " PARTITION p20231207 VALUES LESS THAN ('2023-12-08') ENGINE = InnoDB,\n" +
                " PARTITION p20231208 VALUES LESS THAN ('2023-12-11') ENGINE = InnoDB,\n" +
                " PARTITION p20231211 VALUES LESS THAN ('2023-12-12') ENGINE = InnoDB,\n" +
                " PARTITION p20231212 VALUES LESS THAN ('2023-12-13') ENGINE = InnoDB,\n" +
                " PARTITION p20231213 VALUES LESS THAN ('2023-12-14') ENGINE = InnoDB,\n" +
                " PARTITION p20231214 VALUES LESS THAN ('2023-12-15') ENGINE = InnoDB,\n" +
                " PARTITION p20231215 VALUES LESS THAN ('2023-12-18') ENGINE = InnoDB,\n" +
                " PARTITION p20231218 VALUES LESS THAN ('2023-12-19') ENGINE = InnoDB,\n" +
                " PARTITION p20231219 VALUES LESS THAN ('2023-12-20') ENGINE = InnoDB,\n" +
                " PARTITION p20231220 VALUES LESS THAN ('2023-12-21') ENGINE = InnoDB,\n" +
                " PARTITION p20231221 VALUES LESS THAN ('2023-12-22') ENGINE = InnoDB,\n" +
                " PARTITION p20231222 VALUES LESS THAN ('2023-12-25') ENGINE = InnoDB,\n" +
                " PARTITION p20231225 VALUES LESS THAN ('2023-12-26') ENGINE = InnoDB,\n" +
                " PARTITION p20231226 VALUES LESS THAN ('2023-12-27') ENGINE = InnoDB,\n" +
                " PARTITION p20231227 VALUES LESS THAN ('2023-12-28') ENGINE = InnoDB,\n" +
                " PARTITION p20231228 VALUES LESS THAN ('2023-12-29') ENGINE = InnoDB,\n" +
                " PARTITION p20231229 VALUES LESS THAN ('2024-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240101 VALUES LESS THAN ('2024-01-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240102 VALUES LESS THAN ('2024-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240103 VALUES LESS THAN ('2024-01-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240104 VALUES LESS THAN ('2024-01-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240105 VALUES LESS THAN ('2024-01-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240108 VALUES LESS THAN ('2024-01-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240109 VALUES LESS THAN ('2024-01-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240110 VALUES LESS THAN ('2024-01-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240111 VALUES LESS THAN ('2024-01-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240112 VALUES LESS THAN ('2024-01-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240115 VALUES LESS THAN ('2024-01-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240116 VALUES LESS THAN ('2024-01-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240117 VALUES LESS THAN ('2024-01-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240118 VALUES LESS THAN ('2024-01-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240119 VALUES LESS THAN ('2024-01-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240122 VALUES LESS THAN ('2024-01-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240123 VALUES LESS THAN ('2024-01-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240124 VALUES LESS THAN ('2024-01-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240125 VALUES LESS THAN ('2024-01-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240126 VALUES LESS THAN ('2024-01-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240129 VALUES LESS THAN ('2024-01-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240130 VALUES LESS THAN ('2024-01-31') ENGINE = InnoDB,\n" +
                " PARTITION p20240131 VALUES LESS THAN ('2024-02-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240201 VALUES LESS THAN ('2024-02-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240202 VALUES LESS THAN ('2024-02-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240205 VALUES LESS THAN ('2024-02-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240206 VALUES LESS THAN ('2024-02-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240207 VALUES LESS THAN ('2024-02-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240208 VALUES LESS THAN ('2024-02-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240209 VALUES LESS THAN ('2024-02-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240212 VALUES LESS THAN ('2024-02-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240213 VALUES LESS THAN ('2024-02-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240214 VALUES LESS THAN ('2024-02-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240215 VALUES LESS THAN ('2024-02-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240216 VALUES LESS THAN ('2024-02-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240219 VALUES LESS THAN ('2024-02-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240220 VALUES LESS THAN ('2024-02-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240221 VALUES LESS THAN ('2024-02-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240222 VALUES LESS THAN ('2024-02-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240223 VALUES LESS THAN ('2024-02-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240226 VALUES LESS THAN ('2024-02-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240227 VALUES LESS THAN ('2024-02-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240228 VALUES LESS THAN ('2024-02-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240229 VALUES LESS THAN ('2024-03-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240301 VALUES LESS THAN ('2024-03-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240304 VALUES LESS THAN ('2024-03-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240305 VALUES LESS THAN ('2024-03-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240306 VALUES LESS THAN ('2024-03-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240307 VALUES LESS THAN ('2024-03-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240308 VALUES LESS THAN ('2024-03-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240311 VALUES LESS THAN ('2024-03-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240312 VALUES LESS THAN ('2024-03-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240313 VALUES LESS THAN ('2024-03-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240314 VALUES LESS THAN ('2024-03-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240315 VALUES LESS THAN ('2024-03-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240318 VALUES LESS THAN ('2024-03-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240319 VALUES LESS THAN ('2024-03-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240320 VALUES LESS THAN ('2024-03-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240321 VALUES LESS THAN ('2024-03-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240322 VALUES LESS THAN ('2024-03-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240325 VALUES LESS THAN ('2024-03-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240326 VALUES LESS THAN ('2024-03-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240327 VALUES LESS THAN ('2024-03-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240328 VALUES LESS THAN ('2024-03-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240329 VALUES LESS THAN ('2024-04-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240401 VALUES LESS THAN ('2024-04-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240402 VALUES LESS THAN ('2024-04-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240403 VALUES LESS THAN ('2024-04-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240404 VALUES LESS THAN ('2024-04-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240405 VALUES LESS THAN ('2024-04-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240408 VALUES LESS THAN ('2024-04-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240409 VALUES LESS THAN ('2024-04-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240410 VALUES LESS THAN ('2024-04-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240411 VALUES LESS THAN ('2024-04-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240412 VALUES LESS THAN ('2024-04-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240415 VALUES LESS THAN ('2024-04-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240416 VALUES LESS THAN ('2024-04-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240417 VALUES LESS THAN ('2024-04-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240418 VALUES LESS THAN ('2024-04-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240419 VALUES LESS THAN ('2024-04-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240422 VALUES LESS THAN ('2024-04-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240423 VALUES LESS THAN ('2024-04-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240424 VALUES LESS THAN ('2024-04-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240425 VALUES LESS THAN ('2024-04-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240426 VALUES LESS THAN ('2024-04-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240429 VALUES LESS THAN ('2024-04-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240430 VALUES LESS THAN ('2024-05-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240501 VALUES LESS THAN ('2024-05-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240502 VALUES LESS THAN ('2024-05-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240503 VALUES LESS THAN ('2024-05-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240506 VALUES LESS THAN ('2024-05-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240507 VALUES LESS THAN ('2024-05-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240508 VALUES LESS THAN ('2024-05-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240509 VALUES LESS THAN ('2024-05-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240510 VALUES LESS THAN ('2024-05-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240513 VALUES LESS THAN ('2024-05-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240514 VALUES LESS THAN ('2024-05-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240515 VALUES LESS THAN ('2024-05-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240516 VALUES LESS THAN ('2024-05-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240517 VALUES LESS THAN ('2024-05-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240520 VALUES LESS THAN ('2024-05-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240521 VALUES LESS THAN ('2024-05-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240522 VALUES LESS THAN ('2024-05-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240523 VALUES LESS THAN ('2024-05-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240524 VALUES LESS THAN ('2024-05-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240527 VALUES LESS THAN ('2024-05-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240528 VALUES LESS THAN ('2024-05-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240529 VALUES LESS THAN ('2024-05-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240530 VALUES LESS THAN ('2024-05-31') ENGINE = InnoDB,\n" +
                " PARTITION p20240531 VALUES LESS THAN ('2024-06-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240603 VALUES LESS THAN ('2024-06-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240604 VALUES LESS THAN ('2024-06-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240605 VALUES LESS THAN ('2024-06-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240606 VALUES LESS THAN ('2024-06-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240607 VALUES LESS THAN ('2024-06-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240610 VALUES LESS THAN ('2024-06-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240611 VALUES LESS THAN ('2024-06-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240612 VALUES LESS THAN ('2024-06-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240613 VALUES LESS THAN ('2024-06-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240614 VALUES LESS THAN ('2024-06-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240617 VALUES LESS THAN ('2024-06-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240618 VALUES LESS THAN ('2024-06-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240619 VALUES LESS THAN ('2024-06-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240620 VALUES LESS THAN ('2024-06-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240621 VALUES LESS THAN ('2024-06-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240624 VALUES LESS THAN ('2024-06-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240625 VALUES LESS THAN ('2024-06-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240626 VALUES LESS THAN ('2024-06-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240627 VALUES LESS THAN ('2024-06-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240628 VALUES LESS THAN ('2024-07-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240701 VALUES LESS THAN ('2024-07-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240702 VALUES LESS THAN ('2024-07-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240703 VALUES LESS THAN ('2024-07-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240704 VALUES LESS THAN ('2024-07-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240705 VALUES LESS THAN ('2024-07-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240708 VALUES LESS THAN ('2024-07-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240709 VALUES LESS THAN ('2024-07-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240710 VALUES LESS THAN ('2024-07-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240711 VALUES LESS THAN ('2024-07-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240712 VALUES LESS THAN ('2024-07-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240715 VALUES LESS THAN ('2024-07-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240716 VALUES LESS THAN ('2024-07-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240717 VALUES LESS THAN ('2024-07-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240718 VALUES LESS THAN ('2024-07-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240719 VALUES LESS THAN ('2024-07-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240722 VALUES LESS THAN ('2024-07-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240723 VALUES LESS THAN ('2024-07-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240724 VALUES LESS THAN ('2024-07-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240725 VALUES LESS THAN ('2024-07-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240726 VALUES LESS THAN ('2024-07-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240729 VALUES LESS THAN ('2024-07-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240730 VALUES LESS THAN ('2024-07-31') ENGINE = InnoDB,\n" +
                " PARTITION p20240731 VALUES LESS THAN ('2024-08-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240801 VALUES LESS THAN ('2024-08-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240802 VALUES LESS THAN ('2024-08-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240805 VALUES LESS THAN ('2024-08-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240806 VALUES LESS THAN ('2024-08-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240807 VALUES LESS THAN ('2024-08-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240808 VALUES LESS THAN ('2024-08-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240809 VALUES LESS THAN ('2024-08-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240812 VALUES LESS THAN ('2024-08-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240813 VALUES LESS THAN ('2024-08-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240814 VALUES LESS THAN ('2024-08-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240815 VALUES LESS THAN ('2024-08-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240816 VALUES LESS THAN ('2024-08-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240819 VALUES LESS THAN ('2024-08-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240820 VALUES LESS THAN ('2024-08-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240821 VALUES LESS THAN ('2024-08-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240822 VALUES LESS THAN ('2024-08-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240823 VALUES LESS THAN ('2024-08-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240826 VALUES LESS THAN ('2024-08-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240827 VALUES LESS THAN ('2024-08-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240828 VALUES LESS THAN ('2024-08-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240829 VALUES LESS THAN ('2024-08-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240830 VALUES LESS THAN ('2024-09-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240902 VALUES LESS THAN ('2024-09-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240903 VALUES LESS THAN ('2024-09-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240904 VALUES LESS THAN ('2024-09-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240905 VALUES LESS THAN ('2024-09-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240906 VALUES LESS THAN ('2024-09-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240909 VALUES LESS THAN ('2024-09-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240910 VALUES LESS THAN ('2024-09-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240911 VALUES LESS THAN ('2024-09-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240912 VALUES LESS THAN ('2024-09-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240913 VALUES LESS THAN ('2024-09-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240916 VALUES LESS THAN ('2024-09-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240917 VALUES LESS THAN ('2024-09-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240918 VALUES LESS THAN ('2024-09-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240919 VALUES LESS THAN ('2024-09-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240920 VALUES LESS THAN ('2024-09-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240923 VALUES LESS THAN ('2024-09-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240924 VALUES LESS THAN ('2024-09-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240925 VALUES LESS THAN ('2024-09-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240926 VALUES LESS THAN ('2024-09-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240927 VALUES LESS THAN ('2024-09-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240930 VALUES LESS THAN ('2024-10-01') ENGINE = InnoDB,\n" +
                " PARTITION p20241001 VALUES LESS THAN ('2024-10-02') ENGINE = InnoDB,\n" +
                " PARTITION p20241002 VALUES LESS THAN ('2024-10-03') ENGINE = InnoDB,\n" +
                " PARTITION p20241003 VALUES LESS THAN ('2024-10-04') ENGINE = InnoDB,\n" +
                " PARTITION p20241004 VALUES LESS THAN ('2024-10-07') ENGINE = InnoDB,\n" +
                " PARTITION p20241007 VALUES LESS THAN ('2024-10-08') ENGINE = InnoDB,\n" +
                " PARTITION p20241008 VALUES LESS THAN ('2024-10-09') ENGINE = InnoDB,\n" +
                " PARTITION p20241009 VALUES LESS THAN ('2024-10-10') ENGINE = InnoDB,\n" +
                " PARTITION p20241010 VALUES LESS THAN ('2024-10-11') ENGINE = InnoDB,\n" +
                " PARTITION p20241011 VALUES LESS THAN ('2024-10-14') ENGINE = InnoDB,\n" +
                " PARTITION p20241014 VALUES LESS THAN ('2024-10-15') ENGINE = InnoDB,\n" +
                " PARTITION p20241015 VALUES LESS THAN ('2024-10-16') ENGINE = InnoDB,\n" +
                " PARTITION p20241016 VALUES LESS THAN ('2024-10-17') ENGINE = InnoDB,\n" +
                " PARTITION p20241017 VALUES LESS THAN ('2024-10-18') ENGINE = InnoDB,\n" +
                " PARTITION p20241018 VALUES LESS THAN ('2024-10-21') ENGINE = InnoDB,\n" +
                " PARTITION p20241021 VALUES LESS THAN ('2024-10-22') ENGINE = InnoDB,\n" +
                " PARTITION p20241022 VALUES LESS THAN ('2024-10-23') ENGINE = InnoDB,\n" +
                " PARTITION p20241023 VALUES LESS THAN ('2024-10-24') ENGINE = InnoDB,\n" +
                " PARTITION p20241024 VALUES LESS THAN ('2024-10-25') ENGINE = InnoDB,\n" +
                " PARTITION p20241025 VALUES LESS THAN ('2024-10-28') ENGINE = InnoDB,\n" +
                " PARTITION p20241028 VALUES LESS THAN ('2024-10-29') ENGINE = InnoDB,\n" +
                " PARTITION p20241029 VALUES LESS THAN ('2024-10-30') ENGINE = InnoDB,\n" +
                " PARTITION p20241030 VALUES LESS THAN ('2024-10-31') ENGINE = InnoDB,\n" +
                " PARTITION p20241031 VALUES LESS THAN ('2024-11-01') ENGINE = InnoDB,\n" +
                " PARTITION p20241101 VALUES LESS THAN ('2024-11-04') ENGINE = InnoDB,\n" +
                " PARTITION p20241104 VALUES LESS THAN ('2024-11-05') ENGINE = InnoDB,\n" +
                " PARTITION p20241105 VALUES LESS THAN ('2024-11-06') ENGINE = InnoDB,\n" +
                " PARTITION p20241106 VALUES LESS THAN ('2024-11-07') ENGINE = InnoDB,\n" +
                " PARTITION p20241107 VALUES LESS THAN ('2024-11-08') ENGINE = InnoDB,\n" +
                " PARTITION p20241108 VALUES LESS THAN ('2024-11-11') ENGINE = InnoDB,\n" +
                " PARTITION p20241111 VALUES LESS THAN ('2024-11-12') ENGINE = InnoDB,\n" +
                " PARTITION p20241112 VALUES LESS THAN ('2024-11-13') ENGINE = InnoDB,\n" +
                " PARTITION p20241113 VALUES LESS THAN ('2024-11-14') ENGINE = InnoDB,\n" +
                " PARTITION p20241114 VALUES LESS THAN ('2024-11-15') ENGINE = InnoDB,\n" +
                " PARTITION p20241115 VALUES LESS THAN ('2024-11-18') ENGINE = InnoDB,\n" +
                " PARTITION p20241118 VALUES LESS THAN ('2024-11-19') ENGINE = InnoDB,\n" +
                " PARTITION p20241119 VALUES LESS THAN ('2024-11-20') ENGINE = InnoDB,\n" +
                " PARTITION p20241120 VALUES LESS THAN ('2024-11-21') ENGINE = InnoDB,\n" +
                " PARTITION p20241121 VALUES LESS THAN ('2024-11-22') ENGINE = InnoDB,\n" +
                " PARTITION p20241122 VALUES LESS THAN ('2024-11-25') ENGINE = InnoDB,\n" +
                " PARTITION p20241125 VALUES LESS THAN ('2024-11-26') ENGINE = InnoDB,\n" +
                " PARTITION p20241126 VALUES LESS THAN ('2024-11-27') ENGINE = InnoDB,\n" +
                " PARTITION p20241127 VALUES LESS THAN ('2024-11-28') ENGINE = InnoDB,\n" +
                " PARTITION p20241128 VALUES LESS THAN ('2024-11-29') ENGINE = InnoDB,\n" +
                " PARTITION p20241129 VALUES LESS THAN ('2024-12-02') ENGINE = InnoDB,\n" +
                " PARTITION p20241202 VALUES LESS THAN ('2024-12-03') ENGINE = InnoDB,\n" +
                " PARTITION p20241203 VALUES LESS THAN ('2024-12-04') ENGINE = InnoDB,\n" +
                " PARTITION p20241204 VALUES LESS THAN ('2024-12-05') ENGINE = InnoDB,\n" +
                " PARTITION p20241205 VALUES LESS THAN ('2024-12-06') ENGINE = InnoDB,\n" +
                " PARTITION p20241206 VALUES LESS THAN ('2024-12-09') ENGINE = InnoDB,\n" +
                " PARTITION p20241209 VALUES LESS THAN ('2024-12-10') ENGINE = InnoDB,\n" +
                " PARTITION p20241210 VALUES LESS THAN ('2024-12-11') ENGINE = InnoDB,\n" +
                " PARTITION p20241211 VALUES LESS THAN ('2024-12-12') ENGINE = InnoDB,\n" +
                " PARTITION p20241212 VALUES LESS THAN ('2024-12-13') ENGINE = InnoDB,\n" +
                " PARTITION p20241213 VALUES LESS THAN ('2024-12-16') ENGINE = InnoDB,\n" +
                " PARTITION p20241216 VALUES LESS THAN ('2024-12-17') ENGINE = InnoDB,\n" +
                " PARTITION p20241217 VALUES LESS THAN ('2024-12-18') ENGINE = InnoDB,\n" +
                " PARTITION p20241218 VALUES LESS THAN ('2024-12-19') ENGINE = InnoDB,\n" +
                " PARTITION p20241219 VALUES LESS THAN ('2024-12-20') ENGINE = InnoDB,\n" +
                " PARTITION p20241220 VALUES LESS THAN ('2024-12-23') ENGINE = InnoDB,\n" +
                " PARTITION p20241223 VALUES LESS THAN ('2024-12-24') ENGINE = InnoDB,\n" +
                " PARTITION p20241224 VALUES LESS THAN ('2024-12-25') ENGINE = InnoDB,\n" +
                " PARTITION p20241225 VALUES LESS THAN ('2024-12-26') ENGINE = InnoDB,\n" +
                " PARTITION p20241226 VALUES LESS THAN ('2024-12-27') ENGINE = InnoDB,\n" +
                " PARTITION p20241227 VALUES LESS THAN ('2024-12-30') ENGINE = InnoDB,\n" +
                " PARTITION p20241230 VALUES LESS THAN ('2024-12-31') ENGINE = InnoDB,\n" +
                " PARTITION p20241231 VALUES LESS THAN ('2025-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20250101 VALUES LESS THAN ('2025-01-02') ENGINE = InnoDB,\n" +
                " PARTITION p20250102 VALUES LESS THAN ('2025-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20250103 VALUES LESS THAN ('2025-01-06') ENGINE = InnoDB,\n" +
                " PARTITION p99991231 VALUES LESS THAN (MAXVALUE) ENGINE = InnoDB) */";



        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(sql2, "employees", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees.`clearing_position_incomplete_detail`(`clearing_position_incomplete_detail_id` Int64 NOT NULL ,`clearing_date` Date32 NOT NULL ,`incomplete_reason_id` Int32 NOT NULL ,`incomplete_lookup_type_id` Int32 NOT NULL ,`clearing_position_id` Nullable(Int64),`ref_lookup_db_time` DateTime64(6, 0) NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY" +
                " (`clearing_position_incomplete_detail_id`,`clearing_date`)"));
    }

    @Test
    public void testPartitionByRange() {
        String sql = "CREATE TABLE `enriched_trade` (\n" +
                "  `enriched_trade_id` bigint unsigned NOT NULL,\n" +
                "  `enriched_trade_key` bigint unsigned NOT NULL,\n" +
                "  `version_num` smallint unsigned NOT NULL DEFAULT '0',\n" +
                "  `pos_agg_id` bigint unsigned DEFAULT NULL,\n" +
                "  `is_complete` tinyint(1) NOT NULL,\n" +
                "  `enriched_trade_type_id` smallint unsigned NOT NULL,\n" +
                "  `trade_date` date NOT NULL,\n" +
                "  `street_trade_date` date DEFAULT NULL,\n" +
                "  `settlement_date` date DEFAULT NULL,\n" +
                "  `direction_id` tinyint(1) NOT NULL,\n" +
                "  `price` decimal(24,10) DEFAULT NULL,\n" +
                "  `quantity` decimal(30,10) NOT NULL,\n" +
                "  `sid` bigint unsigned DEFAULT NULL,\n" +
                "  `currency_sid` bigint unsigned DEFAULT NULL,\n" +
                "  `currency_id` smallint unsigned DEFAULT NULL,\n" +
                "  `unit_value` decimal(30,10) DEFAULT NULL,\n" +
                "  `exchange_lglent_id` int DEFAULT NULL,\n" +
                "  `exec_broker_lglent_id` int DEFAULT NULL,\n" +
                "  `branch_id` int DEFAULT NULL,\n" +
                "  `dim_risk_strategy_id` int unsigned DEFAULT NULL,\n" +
                "  `account_id` int DEFAULT NULL,\n" +
                "  `parent_account_id` int DEFAULT NULL,\n" +
                "  `child_account_id` int DEFAULT NULL,\n" +
                "  `account_relshp_type_id` smallint unsigned DEFAULT NULL,\n" +
                "  `valid_time` datetime(6) NOT NULL,\n" +
                "  `db_from` datetime(6) NOT NULL,\n" +
                "  `db_to` datetime(6) NOT NULL,\n" +
                "  `created_by` int NOT NULL,\n" +
                "  `capped_by` int DEFAULT NULL,\n" +
                "  `user_id` mediumint NOT NULL,\n" +
                "  `valid_ts` bigint unsigned DEFAULT NULL,\n" +
                "  `kafka_ts` bigint unsigned DEFAULT NULL,\n" +
                "  `kafka_offset` bigint DEFAULT NULL,\n" +
                "  `kafka_partition` int unsigned DEFAULT NULL,\n" +
                "  `inst_type_id` int NOT NULL,\n" +
                "  `enriched_trade_attributes_1` bigint unsigned DEFAULT '0',\n" +
                "  `is_reversal` int GENERATED ALWAYS AS (((`enriched_trade_attributes_1` & (1 << 0)) > 0)) VIRTUAL,\n" +
                "  PRIMARY KEY (`enriched_trade_id`,`trade_date`),\n" +
                "  UNIQUE KEY `enriched_trade_uq1` (`enriched_trade_key`,`trade_date`,`version_num`),\n" +
                "  UNIQUE KEY `enriched_trade_uq3` (`enriched_trade_id`,`trade_date`,`db_to`),\n" +
                "  UNIQUE KEY `enriched_trade_uq2` (`street_trade_date`,`enriched_trade_id`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx1` (`trade_date`,`valid_time`),\n" +
                "  KEY `enriched_trade_idx2` (`trade_date`,`db_from`,`db_to`),\n" +
                "  KEY `enriched_trade_idx3` (`db_to`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx4` (`db_from`),\n" +
                "  KEY `enriched_trade_idx5` (`sid`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx6` (`account_id`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx7` (`exchange_lglent_id`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx8` (`enriched_trade_type_id`,`street_trade_date`),\n" +
                "  KEY `enriched_trade_idx9` (`enriched_trade_type_id`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx10` (`created_by`,`db_from`,`street_trade_date`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx11` (`capped_by`,`db_to`,`street_trade_date`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx12` (`inst_type_id`,`trade_date`),\n" +
                "  KEY `enriched_trade_idx13` (`kafka_partition`,`trade_date`),\n" +
                "  KEY `idx1_test` (`inst_type_id`,`trade_date`,`account_id`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=latin1 COLLATE=latin1_general_cs STATS_SAMPLE_PAGES=200\n" +
                "/*!50500 PARTITION BY RANGE  COLUMNS(trade_date)\n" +
                "(PARTITION p20211230 VALUES LESS THAN ('2021-12-31') ENGINE = InnoDB,\n" +
                " PARTITION p20211231 VALUES LESS THAN ('2022-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20221229 VALUES LESS THAN ('2022-12-30') ENGINE = InnoDB,\n" +
                " PARTITION p20221230 VALUES LESS THAN ('2023-01-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230102 VALUES LESS THAN ('2023-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230103 VALUES LESS THAN ('2023-01-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230104 VALUES LESS THAN ('2023-01-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230105 VALUES LESS THAN ('2023-01-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230106 VALUES LESS THAN ('2023-01-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230109 VALUES LESS THAN ('2023-01-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230110 VALUES LESS THAN ('2023-01-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230111 VALUES LESS THAN ('2023-01-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230112 VALUES LESS THAN ('2023-01-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230113 VALUES LESS THAN ('2023-01-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230116 VALUES LESS THAN ('2023-01-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230117 VALUES LESS THAN ('2023-01-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230118 VALUES LESS THAN ('2023-01-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230119 VALUES LESS THAN ('2023-01-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230120 VALUES LESS THAN ('2023-01-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230123 VALUES LESS THAN ('2023-01-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230124 VALUES LESS THAN ('2023-01-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230125 VALUES LESS THAN ('2023-01-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230126 VALUES LESS THAN ('2023-01-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230127 VALUES LESS THAN ('2023-01-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230130 VALUES LESS THAN ('2023-01-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230131 VALUES LESS THAN ('2023-02-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230201 VALUES LESS THAN ('2023-02-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230202 VALUES LESS THAN ('2023-02-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230203 VALUES LESS THAN ('2023-02-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230206 VALUES LESS THAN ('2023-02-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230207 VALUES LESS THAN ('2023-02-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230208 VALUES LESS THAN ('2023-02-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230209 VALUES LESS THAN ('2023-02-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230210 VALUES LESS THAN ('2023-02-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230213 VALUES LESS THAN ('2023-02-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230214 VALUES LESS THAN ('2023-02-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230215 VALUES LESS THAN ('2023-02-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230216 VALUES LESS THAN ('2023-02-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230217 VALUES LESS THAN ('2023-02-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230220 VALUES LESS THAN ('2023-02-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230221 VALUES LESS THAN ('2023-02-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230222 VALUES LESS THAN ('2023-02-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230223 VALUES LESS THAN ('2023-02-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230224 VALUES LESS THAN ('2023-02-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230227 VALUES LESS THAN ('2023-02-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230228 VALUES LESS THAN ('2023-03-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230301 VALUES LESS THAN ('2023-03-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230302 VALUES LESS THAN ('2023-03-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230303 VALUES LESS THAN ('2023-03-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230306 VALUES LESS THAN ('2023-03-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230307 VALUES LESS THAN ('2023-03-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230308 VALUES LESS THAN ('2023-03-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230309 VALUES LESS THAN ('2023-03-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230310 VALUES LESS THAN ('2023-03-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230313 VALUES LESS THAN ('2023-03-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230314 VALUES LESS THAN ('2023-03-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230315 VALUES LESS THAN ('2023-03-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230316 VALUES LESS THAN ('2023-03-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230317 VALUES LESS THAN ('2023-03-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230320 VALUES LESS THAN ('2023-03-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230321 VALUES LESS THAN ('2023-03-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230322 VALUES LESS THAN ('2023-03-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230323 VALUES LESS THAN ('2023-03-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230324 VALUES LESS THAN ('2023-03-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230327 VALUES LESS THAN ('2023-03-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230328 VALUES LESS THAN ('2023-03-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230329 VALUES LESS THAN ('2023-03-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230330 VALUES LESS THAN ('2023-03-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230331 VALUES LESS THAN ('2023-04-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230403 VALUES LESS THAN ('2023-04-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230404 VALUES LESS THAN ('2023-04-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230405 VALUES LESS THAN ('2023-04-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230406 VALUES LESS THAN ('2023-04-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230407 VALUES LESS THAN ('2023-04-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230410 VALUES LESS THAN ('2023-04-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230411 VALUES LESS THAN ('2023-04-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230412 VALUES LESS THAN ('2023-04-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230413 VALUES LESS THAN ('2023-04-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230414 VALUES LESS THAN ('2023-04-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230417 VALUES LESS THAN ('2023-04-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230418 VALUES LESS THAN ('2023-04-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230419 VALUES LESS THAN ('2023-04-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230420 VALUES LESS THAN ('2023-04-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230421 VALUES LESS THAN ('2023-04-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230424 VALUES LESS THAN ('2023-04-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230425 VALUES LESS THAN ('2023-04-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230426 VALUES LESS THAN ('2023-04-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230427 VALUES LESS THAN ('2023-04-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230428 VALUES LESS THAN ('2023-05-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230501 VALUES LESS THAN ('2023-05-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230502 VALUES LESS THAN ('2023-05-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230503 VALUES LESS THAN ('2023-05-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230504 VALUES LESS THAN ('2023-05-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230505 VALUES LESS THAN ('2023-05-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230508 VALUES LESS THAN ('2023-05-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230509 VALUES LESS THAN ('2023-05-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230510 VALUES LESS THAN ('2023-05-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230511 VALUES LESS THAN ('2023-05-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230512 VALUES LESS THAN ('2023-05-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230515 VALUES LESS THAN ('2023-05-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230516 VALUES LESS THAN ('2023-05-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230517 VALUES LESS THAN ('2023-05-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230518 VALUES LESS THAN ('2023-05-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230519 VALUES LESS THAN ('2023-05-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230522 VALUES LESS THAN ('2023-05-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230523 VALUES LESS THAN ('2023-05-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230524 VALUES LESS THAN ('2023-05-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230525 VALUES LESS THAN ('2023-05-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230526 VALUES LESS THAN ('2023-05-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230529 VALUES LESS THAN ('2023-05-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230530 VALUES LESS THAN ('2023-05-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230531 VALUES LESS THAN ('2023-06-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230601 VALUES LESS THAN ('2023-06-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230602 VALUES LESS THAN ('2023-06-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230605 VALUES LESS THAN ('2023-06-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230606 VALUES LESS THAN ('2023-06-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230607 VALUES LESS THAN ('2023-06-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230608 VALUES LESS THAN ('2023-06-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230609 VALUES LESS THAN ('2023-06-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230612 VALUES LESS THAN ('2023-06-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230613 VALUES LESS THAN ('2023-06-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230614 VALUES LESS THAN ('2023-06-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230615 VALUES LESS THAN ('2023-06-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230616 VALUES LESS THAN ('2023-06-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230619 VALUES LESS THAN ('2023-06-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230620 VALUES LESS THAN ('2023-06-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230621 VALUES LESS THAN ('2023-06-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230622 VALUES LESS THAN ('2023-06-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230623 VALUES LESS THAN ('2023-06-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230626 VALUES LESS THAN ('2023-06-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230627 VALUES LESS THAN ('2023-06-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230628 VALUES LESS THAN ('2023-06-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230629 VALUES LESS THAN ('2023-06-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230630 VALUES LESS THAN ('2023-07-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230703 VALUES LESS THAN ('2023-07-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230704 VALUES LESS THAN ('2023-07-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230705 VALUES LESS THAN ('2023-07-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230706 VALUES LESS THAN ('2023-07-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230707 VALUES LESS THAN ('2023-07-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230710 VALUES LESS THAN ('2023-07-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230711 VALUES LESS THAN ('2023-07-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230712 VALUES LESS THAN ('2023-07-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230713 VALUES LESS THAN ('2023-07-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230714 VALUES LESS THAN ('2023-07-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230717 VALUES LESS THAN ('2023-07-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230718 VALUES LESS THAN ('2023-07-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230719 VALUES LESS THAN ('2023-07-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230720 VALUES LESS THAN ('2023-07-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230721 VALUES LESS THAN ('2023-07-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230724 VALUES LESS THAN ('2023-07-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230725 VALUES LESS THAN ('2023-07-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230726 VALUES LESS THAN ('2023-07-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230727 VALUES LESS THAN ('2023-07-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230728 VALUES LESS THAN ('2023-07-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230731 VALUES LESS THAN ('2023-08-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230801 VALUES LESS THAN ('2023-08-02') ENGINE = InnoDB,\n" +
                " PARTITION p20230802 VALUES LESS THAN ('2023-08-03') ENGINE = InnoDB,\n" +
                " PARTITION p20230803 VALUES LESS THAN ('2023-08-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230804 VALUES LESS THAN ('2023-08-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230807 VALUES LESS THAN ('2023-08-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230808 VALUES LESS THAN ('2023-08-09') ENGINE = InnoDB,\n" +
                " PARTITION p20230809 VALUES LESS THAN ('2023-08-10') ENGINE = InnoDB,\n" +
                " PARTITION p20230810 VALUES LESS THAN ('2023-08-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230811 VALUES LESS THAN ('2023-08-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230814 VALUES LESS THAN ('2023-08-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230815 VALUES LESS THAN ('2023-08-16') ENGINE = InnoDB,\n" +
                " PARTITION p20230816 VALUES LESS THAN ('2023-08-17') ENGINE = InnoDB,\n" +
                " PARTITION p20230817 VALUES LESS THAN ('2023-08-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230818 VALUES LESS THAN ('2023-08-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230821 VALUES LESS THAN ('2023-08-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230822 VALUES LESS THAN ('2023-08-23') ENGINE = InnoDB,\n" +
                " PARTITION p20230823 VALUES LESS THAN ('2023-08-24') ENGINE = InnoDB,\n" +
                " PARTITION p20230824 VALUES LESS THAN ('2023-08-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230825 VALUES LESS THAN ('2023-08-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230828 VALUES LESS THAN ('2023-08-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230829 VALUES LESS THAN ('2023-08-30') ENGINE = InnoDB,\n" +
                " PARTITION p20230830 VALUES LESS THAN ('2023-08-31') ENGINE = InnoDB,\n" +
                " PARTITION p20230831 VALUES LESS THAN ('2023-09-01') ENGINE = InnoDB,\n" +
                " PARTITION p20230901 VALUES LESS THAN ('2023-09-04') ENGINE = InnoDB,\n" +
                " PARTITION p20230904 VALUES LESS THAN ('2023-09-05') ENGINE = InnoDB,\n" +
                " PARTITION p20230905 VALUES LESS THAN ('2023-09-06') ENGINE = InnoDB,\n" +
                " PARTITION p20230906 VALUES LESS THAN ('2023-09-07') ENGINE = InnoDB,\n" +
                " PARTITION p20230907 VALUES LESS THAN ('2023-09-08') ENGINE = InnoDB,\n" +
                " PARTITION p20230908 VALUES LESS THAN ('2023-09-11') ENGINE = InnoDB,\n" +
                " PARTITION p20230911 VALUES LESS THAN ('2023-09-12') ENGINE = InnoDB,\n" +
                " PARTITION p20230912 VALUES LESS THAN ('2023-09-13') ENGINE = InnoDB,\n" +
                " PARTITION p20230913 VALUES LESS THAN ('2023-09-14') ENGINE = InnoDB,\n" +
                " PARTITION p20230914 VALUES LESS THAN ('2023-09-15') ENGINE = InnoDB,\n" +
                " PARTITION p20230915 VALUES LESS THAN ('2023-09-18') ENGINE = InnoDB,\n" +
                " PARTITION p20230918 VALUES LESS THAN ('2023-09-19') ENGINE = InnoDB,\n" +
                " PARTITION p20230919 VALUES LESS THAN ('2023-09-20') ENGINE = InnoDB,\n" +
                " PARTITION p20230920 VALUES LESS THAN ('2023-09-21') ENGINE = InnoDB,\n" +
                " PARTITION p20230921 VALUES LESS THAN ('2023-09-22') ENGINE = InnoDB,\n" +
                " PARTITION p20230922 VALUES LESS THAN ('2023-09-25') ENGINE = InnoDB,\n" +
                " PARTITION p20230925 VALUES LESS THAN ('2023-09-26') ENGINE = InnoDB,\n" +
                " PARTITION p20230926 VALUES LESS THAN ('2023-09-27') ENGINE = InnoDB,\n" +
                " PARTITION p20230927 VALUES LESS THAN ('2023-09-28') ENGINE = InnoDB,\n" +
                " PARTITION p20230928 VALUES LESS THAN ('2023-09-29') ENGINE = InnoDB,\n" +
                " PARTITION p20230929 VALUES LESS THAN ('2023-10-02') ENGINE = InnoDB,\n" +
                " PARTITION p20231002 VALUES LESS THAN ('2023-10-03') ENGINE = InnoDB,\n" +
                " PARTITION p20231003 VALUES LESS THAN ('2023-10-04') ENGINE = InnoDB,\n" +
                " PARTITION p20231004 VALUES LESS THAN ('2023-10-05') ENGINE = InnoDB,\n" +
                " PARTITION p20231005 VALUES LESS THAN ('2023-10-06') ENGINE = InnoDB,\n" +
                " PARTITION p20231006 VALUES LESS THAN ('2023-10-09') ENGINE = InnoDB,\n" +
                " PARTITION p20231009 VALUES LESS THAN ('2023-10-10') ENGINE = InnoDB,\n" +
                " PARTITION p20231010 VALUES LESS THAN ('2023-10-11') ENGINE = InnoDB,\n" +
                " PARTITION p20231011 VALUES LESS THAN ('2023-10-12') ENGINE = InnoDB,\n" +
                " PARTITION p20231012 VALUES LESS THAN ('2023-10-13') ENGINE = InnoDB,\n" +
                " PARTITION p20231013 VALUES LESS THAN ('2023-10-16') ENGINE = InnoDB,\n" +
                " PARTITION p20231016 VALUES LESS THAN ('2023-10-17') ENGINE = InnoDB,\n" +
                " PARTITION p20231017 VALUES LESS THAN ('2023-10-18') ENGINE = InnoDB,\n" +
                " PARTITION p20231018 VALUES LESS THAN ('2023-10-19') ENGINE = InnoDB,\n" +
                " PARTITION p20231019 VALUES LESS THAN ('2023-10-20') ENGINE = InnoDB,\n" +
                " PARTITION p20231020 VALUES LESS THAN ('2023-10-23') ENGINE = InnoDB,\n" +
                " PARTITION p20231023 VALUES LESS THAN ('2023-10-24') ENGINE = InnoDB,\n" +
                " PARTITION p20231024 VALUES LESS THAN ('2023-10-25') ENGINE = InnoDB,\n" +
                " PARTITION p20231025 VALUES LESS THAN ('2023-10-26') ENGINE = InnoDB,\n" +
                " PARTITION p20231026 VALUES LESS THAN ('2023-10-27') ENGINE = InnoDB,\n" +
                " PARTITION p20231027 VALUES LESS THAN ('2023-10-30') ENGINE = InnoDB,\n" +
                " PARTITION p20231030 VALUES LESS THAN ('2023-10-31') ENGINE = InnoDB,\n" +
                " PARTITION p20231031 VALUES LESS THAN ('2023-11-01') ENGINE = InnoDB,\n" +
                " PARTITION p20231101 VALUES LESS THAN ('2023-11-02') ENGINE = InnoDB,\n" +
                " PARTITION p20231102 VALUES LESS THAN ('2023-11-03') ENGINE = InnoDB,\n" +
                " PARTITION p20231103 VALUES LESS THAN ('2023-11-06') ENGINE = InnoDB,\n" +
                " PARTITION p20231106 VALUES LESS THAN ('2023-11-07') ENGINE = InnoDB,\n" +
                " PARTITION p20231107 VALUES LESS THAN ('2023-11-08') ENGINE = InnoDB,\n" +
                " PARTITION p20231108 VALUES LESS THAN ('2023-11-09') ENGINE = InnoDB,\n" +
                " PARTITION p20231109 VALUES LESS THAN ('2023-11-10') ENGINE = InnoDB,\n" +
                " PARTITION p20231110 VALUES LESS THAN ('2023-11-13') ENGINE = InnoDB,\n" +
                " PARTITION p20231113 VALUES LESS THAN ('2023-11-14') ENGINE = InnoDB,\n" +
                " PARTITION p20231114 VALUES LESS THAN ('2023-11-15') ENGINE = InnoDB,\n" +
                " PARTITION p20231115 VALUES LESS THAN ('2023-11-16') ENGINE = InnoDB,\n" +
                " PARTITION p20231116 VALUES LESS THAN ('2023-11-17') ENGINE = InnoDB,\n" +
                " PARTITION p20231117 VALUES LESS THAN ('2023-11-20') ENGINE = InnoDB,\n" +
                " PARTITION p20231120 VALUES LESS THAN ('2023-11-21') ENGINE = InnoDB,\n" +
                " PARTITION p20231121 VALUES LESS THAN ('2023-11-22') ENGINE = InnoDB,\n" +
                " PARTITION p20231122 VALUES LESS THAN ('2023-11-23') ENGINE = InnoDB,\n" +
                " PARTITION p20231123 VALUES LESS THAN ('2023-11-24') ENGINE = InnoDB,\n" +
                " PARTITION p20231124 VALUES LESS THAN ('2023-11-27') ENGINE = InnoDB,\n" +
                " PARTITION p20231127 VALUES LESS THAN ('2023-11-28') ENGINE = InnoDB,\n" +
                " PARTITION p20231128 VALUES LESS THAN ('2023-11-29') ENGINE = InnoDB,\n" +
                " PARTITION p20231129 VALUES LESS THAN ('2023-11-30') ENGINE = InnoDB,\n" +
                " PARTITION p20231130 VALUES LESS THAN ('2023-12-01') ENGINE = InnoDB,\n" +
                " PARTITION p20231201 VALUES LESS THAN ('2023-12-04') ENGINE = InnoDB,\n" +
                " PARTITION p20231204 VALUES LESS THAN ('2023-12-05') ENGINE = InnoDB,\n" +
                " PARTITION p20231205 VALUES LESS THAN ('2023-12-06') ENGINE = InnoDB,\n" +
                " PARTITION p20231206 VALUES LESS THAN ('2023-12-07') ENGINE = InnoDB,\n" +
                " PARTITION p20231207 VALUES LESS THAN ('2023-12-08') ENGINE = InnoDB,\n" +
                " PARTITION p20231208 VALUES LESS THAN ('2023-12-11') ENGINE = InnoDB,\n" +
                " PARTITION p20231211 VALUES LESS THAN ('2023-12-12') ENGINE = InnoDB,\n" +
                " PARTITION p20231212 VALUES LESS THAN ('2023-12-13') ENGINE = InnoDB,\n" +
                " PARTITION p20231213 VALUES LESS THAN ('2023-12-14') ENGINE = InnoDB,\n" +
                " PARTITION p20231214 VALUES LESS THAN ('2023-12-15') ENGINE = InnoDB,\n" +
                " PARTITION p20231215 VALUES LESS THAN ('2023-12-18') ENGINE = InnoDB,\n" +
                " PARTITION p20231218 VALUES LESS THAN ('2023-12-19') ENGINE = InnoDB,\n" +
                " PARTITION p20231219 VALUES LESS THAN ('2023-12-20') ENGINE = InnoDB,\n" +
                " PARTITION p20231220 VALUES LESS THAN ('2023-12-21') ENGINE = InnoDB,\n" +
                " PARTITION p20231221 VALUES LESS THAN ('2023-12-22') ENGINE = InnoDB,\n" +
                " PARTITION p20231222 VALUES LESS THAN ('2023-12-25') ENGINE = InnoDB,\n" +
                " PARTITION p20231225 VALUES LESS THAN ('2023-12-26') ENGINE = InnoDB,\n" +
                " PARTITION p20231226 VALUES LESS THAN ('2023-12-27') ENGINE = InnoDB,\n" +
                " PARTITION p20231227 VALUES LESS THAN ('2023-12-28') ENGINE = InnoDB,\n" +
                " PARTITION p20231228 VALUES LESS THAN ('2023-12-29') ENGINE = InnoDB,\n" +
                " PARTITION p20231229 VALUES LESS THAN ('2024-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240101 VALUES LESS THAN ('2024-01-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240102 VALUES LESS THAN ('2024-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240103 VALUES LESS THAN ('2024-01-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240104 VALUES LESS THAN ('2024-01-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240105 VALUES LESS THAN ('2024-01-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240108 VALUES LESS THAN ('2024-01-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240109 VALUES LESS THAN ('2024-01-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240110 VALUES LESS THAN ('2024-01-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240111 VALUES LESS THAN ('2024-01-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240112 VALUES LESS THAN ('2024-01-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240115 VALUES LESS THAN ('2024-01-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240116 VALUES LESS THAN ('2024-01-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240117 VALUES LESS THAN ('2024-01-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240118 VALUES LESS THAN ('2024-01-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240119 VALUES LESS THAN ('2024-01-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240122 VALUES LESS THAN ('2024-01-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240123 VALUES LESS THAN ('2024-01-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240124 VALUES LESS THAN ('2024-01-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240125 VALUES LESS THAN ('2024-01-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240126 VALUES LESS THAN ('2024-01-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240129 VALUES LESS THAN ('2024-01-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240130 VALUES LESS THAN ('2024-01-31') ENGINE = InnoDB,\n" +
                " PARTITION p20240131 VALUES LESS THAN ('2024-02-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240201 VALUES LESS THAN ('2024-02-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240202 VALUES LESS THAN ('2024-02-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240205 VALUES LESS THAN ('2024-02-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240206 VALUES LESS THAN ('2024-02-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240207 VALUES LESS THAN ('2024-02-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240208 VALUES LESS THAN ('2024-02-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240209 VALUES LESS THAN ('2024-02-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240212 VALUES LESS THAN ('2024-02-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240213 VALUES LESS THAN ('2024-02-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240214 VALUES LESS THAN ('2024-02-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240215 VALUES LESS THAN ('2024-02-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240216 VALUES LESS THAN ('2024-02-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240219 VALUES LESS THAN ('2024-02-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240220 VALUES LESS THAN ('2024-02-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240221 VALUES LESS THAN ('2024-02-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240222 VALUES LESS THAN ('2024-02-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240223 VALUES LESS THAN ('2024-02-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240226 VALUES LESS THAN ('2024-02-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240227 VALUES LESS THAN ('2024-02-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240228 VALUES LESS THAN ('2024-02-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240229 VALUES LESS THAN ('2024-03-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240301 VALUES LESS THAN ('2024-03-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240304 VALUES LESS THAN ('2024-03-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240305 VALUES LESS THAN ('2024-03-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240306 VALUES LESS THAN ('2024-03-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240307 VALUES LESS THAN ('2024-03-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240308 VALUES LESS THAN ('2024-03-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240311 VALUES LESS THAN ('2024-03-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240312 VALUES LESS THAN ('2024-03-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240313 VALUES LESS THAN ('2024-03-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240314 VALUES LESS THAN ('2024-03-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240315 VALUES LESS THAN ('2024-03-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240318 VALUES LESS THAN ('2024-03-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240319 VALUES LESS THAN ('2024-03-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240320 VALUES LESS THAN ('2024-03-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240321 VALUES LESS THAN ('2024-03-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240322 VALUES LESS THAN ('2024-03-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240325 VALUES LESS THAN ('2024-03-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240326 VALUES LESS THAN ('2024-03-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240327 VALUES LESS THAN ('2024-03-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240328 VALUES LESS THAN ('2024-03-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240329 VALUES LESS THAN ('2024-04-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240401 VALUES LESS THAN ('2024-04-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240402 VALUES LESS THAN ('2024-04-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240403 VALUES LESS THAN ('2024-04-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240404 VALUES LESS THAN ('2024-04-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240405 VALUES LESS THAN ('2024-04-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240408 VALUES LESS THAN ('2024-04-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240409 VALUES LESS THAN ('2024-04-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240410 VALUES LESS THAN ('2024-04-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240411 VALUES LESS THAN ('2024-04-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240412 VALUES LESS THAN ('2024-04-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240415 VALUES LESS THAN ('2024-04-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240416 VALUES LESS THAN ('2024-04-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240417 VALUES LESS THAN ('2024-04-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240418 VALUES LESS THAN ('2024-04-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240419 VALUES LESS THAN ('2024-04-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240422 VALUES LESS THAN ('2024-04-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240423 VALUES LESS THAN ('2024-04-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240424 VALUES LESS THAN ('2024-04-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240425 VALUES LESS THAN ('2024-04-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240426 VALUES LESS THAN ('2024-04-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240429 VALUES LESS THAN ('2024-04-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240430 VALUES LESS THAN ('2024-05-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240501 VALUES LESS THAN ('2024-05-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240502 VALUES LESS THAN ('2024-05-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240503 VALUES LESS THAN ('2024-05-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240506 VALUES LESS THAN ('2024-05-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240507 VALUES LESS THAN ('2024-05-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240508 VALUES LESS THAN ('2024-05-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240509 VALUES LESS THAN ('2024-05-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240510 VALUES LESS THAN ('2024-05-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240513 VALUES LESS THAN ('2024-05-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240514 VALUES LESS THAN ('2024-05-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240515 VALUES LESS THAN ('2024-05-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240516 VALUES LESS THAN ('2024-05-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240517 VALUES LESS THAN ('2024-05-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240520 VALUES LESS THAN ('2024-05-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240521 VALUES LESS THAN ('2024-05-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240522 VALUES LESS THAN ('2024-05-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240523 VALUES LESS THAN ('2024-05-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240524 VALUES LESS THAN ('2024-05-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240527 VALUES LESS THAN ('2024-05-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240528 VALUES LESS THAN ('2024-05-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240529 VALUES LESS THAN ('2024-05-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240530 VALUES LESS THAN ('2024-05-31') ENGINE = InnoDB,\n" +
                " PARTITION p20240531 VALUES LESS THAN ('2024-06-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240603 VALUES LESS THAN ('2024-06-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240604 VALUES LESS THAN ('2024-06-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240605 VALUES LESS THAN ('2024-06-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240606 VALUES LESS THAN ('2024-06-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240607 VALUES LESS THAN ('2024-06-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240610 VALUES LESS THAN ('2024-06-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240611 VALUES LESS THAN ('2024-06-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240612 VALUES LESS THAN ('2024-06-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240613 VALUES LESS THAN ('2024-06-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240614 VALUES LESS THAN ('2024-06-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240617 VALUES LESS THAN ('2024-06-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240618 VALUES LESS THAN ('2024-06-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240619 VALUES LESS THAN ('2024-06-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240620 VALUES LESS THAN ('2024-06-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240621 VALUES LESS THAN ('2024-06-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240624 VALUES LESS THAN ('2024-06-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240625 VALUES LESS THAN ('2024-06-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240626 VALUES LESS THAN ('2024-06-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240627 VALUES LESS THAN ('2024-06-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240628 VALUES LESS THAN ('2024-07-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240701 VALUES LESS THAN ('2024-07-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240702 VALUES LESS THAN ('2024-07-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240703 VALUES LESS THAN ('2024-07-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240704 VALUES LESS THAN ('2024-07-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240705 VALUES LESS THAN ('2024-07-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240708 VALUES LESS THAN ('2024-07-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240709 VALUES LESS THAN ('2024-07-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240710 VALUES LESS THAN ('2024-07-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240711 VALUES LESS THAN ('2024-07-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240712 VALUES LESS THAN ('2024-07-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240715 VALUES LESS THAN ('2024-07-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240716 VALUES LESS THAN ('2024-07-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240717 VALUES LESS THAN ('2024-07-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240718 VALUES LESS THAN ('2024-07-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240719 VALUES LESS THAN ('2024-07-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240722 VALUES LESS THAN ('2024-07-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240723 VALUES LESS THAN ('2024-07-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240724 VALUES LESS THAN ('2024-07-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240725 VALUES LESS THAN ('2024-07-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240726 VALUES LESS THAN ('2024-07-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240729 VALUES LESS THAN ('2024-07-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240730 VALUES LESS THAN ('2024-07-31') ENGINE = InnoDB,\n" +
                " PARTITION p20240731 VALUES LESS THAN ('2024-08-01') ENGINE = InnoDB,\n" +
                " PARTITION p20240801 VALUES LESS THAN ('2024-08-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240802 VALUES LESS THAN ('2024-08-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240805 VALUES LESS THAN ('2024-08-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240806 VALUES LESS THAN ('2024-08-07') ENGINE = InnoDB,\n" +
                " PARTITION p20240807 VALUES LESS THAN ('2024-08-08') ENGINE = InnoDB,\n" +
                " PARTITION p20240808 VALUES LESS THAN ('2024-08-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240809 VALUES LESS THAN ('2024-08-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240812 VALUES LESS THAN ('2024-08-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240813 VALUES LESS THAN ('2024-08-14') ENGINE = InnoDB,\n" +
                " PARTITION p20240814 VALUES LESS THAN ('2024-08-15') ENGINE = InnoDB,\n" +
                " PARTITION p20240815 VALUES LESS THAN ('2024-08-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240816 VALUES LESS THAN ('2024-08-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240819 VALUES LESS THAN ('2024-08-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240820 VALUES LESS THAN ('2024-08-21') ENGINE = InnoDB,\n" +
                " PARTITION p20240821 VALUES LESS THAN ('2024-08-22') ENGINE = InnoDB,\n" +
                " PARTITION p20240822 VALUES LESS THAN ('2024-08-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240823 VALUES LESS THAN ('2024-08-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240826 VALUES LESS THAN ('2024-08-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240827 VALUES LESS THAN ('2024-08-28') ENGINE = InnoDB,\n" +
                " PARTITION p20240828 VALUES LESS THAN ('2024-08-29') ENGINE = InnoDB,\n" +
                " PARTITION p20240829 VALUES LESS THAN ('2024-08-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240830 VALUES LESS THAN ('2024-09-02') ENGINE = InnoDB,\n" +
                " PARTITION p20240902 VALUES LESS THAN ('2024-09-03') ENGINE = InnoDB,\n" +
                " PARTITION p20240903 VALUES LESS THAN ('2024-09-04') ENGINE = InnoDB,\n" +
                " PARTITION p20240904 VALUES LESS THAN ('2024-09-05') ENGINE = InnoDB,\n" +
                " PARTITION p20240905 VALUES LESS THAN ('2024-09-06') ENGINE = InnoDB,\n" +
                " PARTITION p20240906 VALUES LESS THAN ('2024-09-09') ENGINE = InnoDB,\n" +
                " PARTITION p20240909 VALUES LESS THAN ('2024-09-10') ENGINE = InnoDB,\n" +
                " PARTITION p20240910 VALUES LESS THAN ('2024-09-11') ENGINE = InnoDB,\n" +
                " PARTITION p20240911 VALUES LESS THAN ('2024-09-12') ENGINE = InnoDB,\n" +
                " PARTITION p20240912 VALUES LESS THAN ('2024-09-13') ENGINE = InnoDB,\n" +
                " PARTITION p20240913 VALUES LESS THAN ('2024-09-16') ENGINE = InnoDB,\n" +
                " PARTITION p20240916 VALUES LESS THAN ('2024-09-17') ENGINE = InnoDB,\n" +
                " PARTITION p20240917 VALUES LESS THAN ('2024-09-18') ENGINE = InnoDB,\n" +
                " PARTITION p20240918 VALUES LESS THAN ('2024-09-19') ENGINE = InnoDB,\n" +
                " PARTITION p20240919 VALUES LESS THAN ('2024-09-20') ENGINE = InnoDB,\n" +
                " PARTITION p20240920 VALUES LESS THAN ('2024-09-23') ENGINE = InnoDB,\n" +
                " PARTITION p20240923 VALUES LESS THAN ('2024-09-24') ENGINE = InnoDB,\n" +
                " PARTITION p20240924 VALUES LESS THAN ('2024-09-25') ENGINE = InnoDB,\n" +
                " PARTITION p20240925 VALUES LESS THAN ('2024-09-26') ENGINE = InnoDB,\n" +
                " PARTITION p20240926 VALUES LESS THAN ('2024-09-27') ENGINE = InnoDB,\n" +
                " PARTITION p20240927 VALUES LESS THAN ('2024-09-30') ENGINE = InnoDB,\n" +
                " PARTITION p20240930 VALUES LESS THAN ('2024-10-01') ENGINE = InnoDB,\n" +
                " PARTITION p20241001 VALUES LESS THAN ('2024-10-02') ENGINE = InnoDB,\n" +
                " PARTITION p20241002 VALUES LESS THAN ('2024-10-03') ENGINE = InnoDB,\n" +
                " PARTITION p20241003 VALUES LESS THAN ('2024-10-04') ENGINE = InnoDB,\n" +
                " PARTITION p20241004 VALUES LESS THAN ('2024-10-07') ENGINE = InnoDB,\n" +
                " PARTITION p20241007 VALUES LESS THAN ('2024-10-08') ENGINE = InnoDB,\n" +
                " PARTITION p20241008 VALUES LESS THAN ('2024-10-09') ENGINE = InnoDB,\n" +
                " PARTITION p20241009 VALUES LESS THAN ('2024-10-10') ENGINE = InnoDB,\n" +
                " PARTITION p20241010 VALUES LESS THAN ('2024-10-11') ENGINE = InnoDB,\n" +
                " PARTITION p20241011 VALUES LESS THAN ('2024-10-14') ENGINE = InnoDB,\n" +
                " PARTITION p20241014 VALUES LESS THAN ('2024-10-15') ENGINE = InnoDB,\n" +
                " PARTITION p20241015 VALUES LESS THAN ('2024-10-16') ENGINE = InnoDB,\n" +
                " PARTITION p20241016 VALUES LESS THAN ('2024-10-17') ENGINE = InnoDB,\n" +
                " PARTITION p20241017 VALUES LESS THAN ('2024-10-18') ENGINE = InnoDB,\n" +
                " PARTITION p20241018 VALUES LESS THAN ('2024-10-21') ENGINE = InnoDB,\n" +
                " PARTITION p20241021 VALUES LESS THAN ('2024-10-22') ENGINE = InnoDB,\n" +
                " PARTITION p20241022 VALUES LESS THAN ('2024-10-23') ENGINE = InnoDB,\n" +
                " PARTITION p20241023 VALUES LESS THAN ('2024-10-24') ENGINE = InnoDB,\n" +
                " PARTITION p20241024 VALUES LESS THAN ('2024-10-25') ENGINE = InnoDB,\n" +
                " PARTITION p20241025 VALUES LESS THAN ('2024-10-28') ENGINE = InnoDB,\n" +
                " PARTITION p20241028 VALUES LESS THAN ('2024-10-29') ENGINE = InnoDB,\n" +
                " PARTITION p20241029 VALUES LESS THAN ('2024-10-30') ENGINE = InnoDB,\n" +
                " PARTITION p20241030 VALUES LESS THAN ('2024-10-31') ENGINE = InnoDB,\n" +
                " PARTITION p20241031 VALUES LESS THAN ('2024-11-01') ENGINE = InnoDB,\n" +
                " PARTITION p20241101 VALUES LESS THAN ('2024-11-04') ENGINE = InnoDB,\n" +
                " PARTITION p20241104 VALUES LESS THAN ('2024-11-05') ENGINE = InnoDB,\n" +
                " PARTITION p20241105 VALUES LESS THAN ('2024-11-06') ENGINE = InnoDB,\n" +
                " PARTITION p20241106 VALUES LESS THAN ('2024-11-07') ENGINE = InnoDB,\n" +
                " PARTITION p20241107 VALUES LESS THAN ('2024-11-08') ENGINE = InnoDB,\n" +
                " PARTITION p20241108 VALUES LESS THAN ('2024-11-11') ENGINE = InnoDB,\n" +
                " PARTITION p20241111 VALUES LESS THAN ('2024-11-12') ENGINE = InnoDB,\n" +
                " PARTITION p20241112 VALUES LESS THAN ('2024-11-13') ENGINE = InnoDB,\n" +
                " PARTITION p20241113 VALUES LESS THAN ('2024-11-14') ENGINE = InnoDB,\n" +
                " PARTITION p20241114 VALUES LESS THAN ('2024-11-15') ENGINE = InnoDB,\n" +
                " PARTITION p20241115 VALUES LESS THAN ('2024-11-18') ENGINE = InnoDB,\n" +
                " PARTITION p20241118 VALUES LESS THAN ('2024-11-19') ENGINE = InnoDB,\n" +
                " PARTITION p20241119 VALUES LESS THAN ('2024-11-20') ENGINE = InnoDB,\n" +
                " PARTITION p20241120 VALUES LESS THAN ('2024-11-21') ENGINE = InnoDB,\n" +
                " PARTITION p20241121 VALUES LESS THAN ('2024-11-22') ENGINE = InnoDB,\n" +
                " PARTITION p20241122 VALUES LESS THAN ('2024-11-25') ENGINE = InnoDB,\n" +
                " PARTITION p20241125 VALUES LESS THAN ('2024-11-26') ENGINE = InnoDB,\n" +
                " PARTITION p20241126 VALUES LESS THAN ('2024-11-27') ENGINE = InnoDB,\n" +
                " PARTITION p20241127 VALUES LESS THAN ('2024-11-28') ENGINE = InnoDB,\n" +
                " PARTITION p20241128 VALUES LESS THAN ('2024-11-29') ENGINE = InnoDB,\n" +
                " PARTITION p20241129 VALUES LESS THAN ('2024-12-02') ENGINE = InnoDB,\n" +
                " PARTITION p20241202 VALUES LESS THAN ('2024-12-03') ENGINE = InnoDB,\n" +
                " PARTITION p20241203 VALUES LESS THAN ('2024-12-04') ENGINE = InnoDB,\n" +
                " PARTITION p20241204 VALUES LESS THAN ('2024-12-05') ENGINE = InnoDB,\n" +
                " PARTITION p20241205 VALUES LESS THAN ('2024-12-06') ENGINE = InnoDB,\n" +
                " PARTITION p20241206 VALUES LESS THAN ('2024-12-09') ENGINE = InnoDB,\n" +
                " PARTITION p20241209 VALUES LESS THAN ('2024-12-10') ENGINE = InnoDB,\n" +
                " PARTITION p20241210 VALUES LESS THAN ('2024-12-11') ENGINE = InnoDB,\n" +
                " PARTITION p20241211 VALUES LESS THAN ('2024-12-12') ENGINE = InnoDB,\n" +
                " PARTITION p20241212 VALUES LESS THAN ('2024-12-13') ENGINE = InnoDB,\n" +
                " PARTITION p20241213 VALUES LESS THAN ('2024-12-16') ENGINE = InnoDB,\n" +
                " PARTITION p20241216 VALUES LESS THAN ('2024-12-17') ENGINE = InnoDB,\n" +
                " PARTITION p20241217 VALUES LESS THAN ('2024-12-18') ENGINE = InnoDB,\n" +
                " PARTITION p20241218 VALUES LESS THAN ('2024-12-19') ENGINE = InnoDB,\n" +
                " PARTITION p20241219 VALUES LESS THAN ('2024-12-20') ENGINE = InnoDB,\n" +
                " PARTITION p20241220 VALUES LESS THAN ('2024-12-23') ENGINE = InnoDB,\n" +
                " PARTITION p20241223 VALUES LESS THAN ('2024-12-24') ENGINE = InnoDB,\n" +
                " PARTITION p20241224 VALUES LESS THAN ('2024-12-25') ENGINE = InnoDB,\n" +
                " PARTITION p20241225 VALUES LESS THAN ('2024-12-26') ENGINE = InnoDB,\n" +
                " PARTITION p20241226 VALUES LESS THAN ('2024-12-27') ENGINE = InnoDB,\n" +
                " PARTITION p20241227 VALUES LESS THAN ('2024-12-30') ENGINE = InnoDB,\n" +
                " PARTITION p20241230 VALUES LESS THAN ('2024-12-31') ENGINE = InnoDB,\n" +
                " PARTITION p20241231 VALUES LESS THAN ('2025-01-01') ENGINE = InnoDB,\n" +
                " PARTITION p20250101 VALUES LESS THAN ('2025-01-02') ENGINE = InnoDB,\n" +
                " PARTITION p20250102 VALUES LESS THAN ('2025-01-03') ENGINE = InnoDB,\n" +
                " PARTITION p20250103 VALUES LESS THAN ('2025-01-06') ENGINE = InnoDB,\n" +
                " PARTITION p99991231 VALUES LESS THAN (MAXVALUE) ENGINE = InnoDB) */";

        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(sql, "employees", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(
                "CREATE TABLE employees.`enriched_trade`(`enriched_trade_id` Int64 NOT NULL ,`enriched_trade_key` Int64 NOT NULL ,`version_num` Int32 NOT NULL ,`pos_agg_id` Nullable(Int64),`is_complete` Int16 NOT NULL ,`enriched_trade_type_id` Int32 NOT NULL ,`trade_date` Date32 NOT NULL ,`street_trade_date` Nullable(Date32),`settlement_date` Nullable(Date32),`direction_id` Int16 NOT NULL ,`price` Nullable(Decimal(24,10)),`quantity` Decimal(30,10) NOT NULL ,`sid` Nullable(Int64),`currency_sid` Nullable(Int64),`currency_id` Nullable(Int32),`unit_value` Nullable(Decimal(30,10)),`exchange_lglent_id` Nullable(Int32),`exec_broker_lglent_id` Nullable(Int32),`branch_id` Nullable(Int32),`dim_risk_strategy_id` Nullable(Int64),`account_id` Nullable(Int32),`parent_account_id` Nullable(Int32),`child_account_id` Nullable(Int32),`account_relshp_type_id` Nullable(Int32),`valid_time` DateTime64(6, 0) NOT NULL ,`db_from` DateTime64(6, 0) NOT NULL ,`db_to` DateTime64(6, 0) NOT NULL ,`created_by` Int32 NOT NULL ,`capped_by` Nullable(Int32),`user_id` Int32 NOT NULL ,`valid_ts` Nullable(Int64),`kafka_ts` Nullable(Int64),`kafka_offset` Nullable(Int64),`kafka_partition` Nullable(Int64),`inst_type_id` Int32 NOT NULL ,`enriched_trade_attributes_1` Nullable(Int64),`is_reversal` Nullable(Int32) MATERIALIZED ((`enriched_trade_attributes_1`&(1<<0))>0),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`enriched_trade_id`,`trade_date`)"));
    }
    @Test
    @Disabled
    public void testPartitionedByRangeTable() {
        String sql = "CREATE TABLE `city` (\n" +
                "  `ID` int NOT NULL AUTO_INCREMENT,\n" +
                "  `Name` char(35) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',\n" +
                "  `CountryCode` char(3) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',\n" +
                "  `District` char(20) COLLATE utf8mb4_general_ci NOT NULL DEFAULT '',\n" +
                "  `Population` int NOT NULL DEFAULT '0',\n" +
                "  `is_deleted` tinyint(1) DEFAULT '0',\n" +
                "  PRIMARY KEY (`ID`)\n" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci PARTITION BY RANGE(`ID`)\n" +
                "(PARTITION p0 VALUES LESS THAN (1000),\n" +
                " PARTITION p1 VALUES LESS THAN (2000),\n" +
                " PARTITION p2 VALUES LESS THAN (3000),\n" +
                " PARTITION p3 VALUES LESS THAN (4000),\n" +
                " PARTITION p4 VALUES LESS THAN (5000),\n" +
                " PARTITION p5 VALUES LESS THAN (6000),\n" +
                " PARTITION p6 VALUES LESS THAN (7000),\n" +
                " PARTITION p7 VALUES LESS THAN (8000),\n" +
                " PARTITION p8 VALUES LESS THAN (9000),\n" +
                " PARTITION p9 VALUES LESS THAN (10000));";

        StringBuffer clickHouseQuery = new StringBuffer();
        mySQLDDLParserService.parseSql(sql, "employees", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(
                "CREATE TABLE employees.`city`(`ID` Int32 NOT NULL ,`Name` String NOT NULL ,`CountryCode` String NOT NULL ,`District` String NOT NULL ,`Population` Int32 NOT NULL ,`is_deleted` Nullable(Int16),`_version` UInt64,`__is_deleted` UInt8) Engine=ReplacingMergeTree(_version,__is_deleted) ORDER BY (`ID`) PARTITION BY ID"));
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

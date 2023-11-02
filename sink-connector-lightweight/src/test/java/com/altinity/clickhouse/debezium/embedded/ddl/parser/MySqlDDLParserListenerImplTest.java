package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;


public class MySqlDDLParserListenerImplTest {

    private static final Logger log = LoggerFactory.getLogger(MySqlDDLParserListenerImplTest.class);


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
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createQuery, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE employees_predated(emp_no Int32 NOT NULL ,birth_date Date32 NOT NULL ,first_name String NOT NULL ,last_name String NOT NULL ,gender String NOT NULL ,hire_date Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (emp_no)"));
        log.info("Create table " + clickHouseQuery);
    }
    @Test
    public void testCreateTableWithRangeByColumnsPartition() {
        String createQuery = "CREATE TABLE rcx ( a INT, b INT, c CHAR(3), d INT) PARTITION BY RANGE COLUMNS(a,d,c) ( PARTITION p0 VALUES LESS THAN (5,10,'ggg'), PARTITION p1 VALUES LESS THAN (10,20,'mmm'), " +
                "PARTITION p2 VALUES LESS THAN (15,30,'sss'), PARTITION p3 VALUES LESS THAN (MAXVALUE,MAXVALUE,MAXVALUE));";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createQuery, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE rcx(a Nullable(Int32),b Nullable(Int32),c Nullable(String),d Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY  (a,d,c) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);
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
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createQuery, "Persons", clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE members(firstname String NOT NULL ,lastname String NOT NULL ,username String NOT NULL ,email Nullable(String),joined Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) PARTITION BY  joined ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);
    }
    @Test
    public void testCreateTableAutoIncrement() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "CREATE TABLE IF NOT EXISTS 730b595f_d475_11ed_b64a_398b553542b2 (id INT AUTO_INCREMENT,x INT, PRIMARY KEY (id)) ENGINE = InnoDB;";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);
        log.info("Create table " + clickHouseQuery);
    }

    @Test
    public void testCreateTableLike() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "CREATE TABLE new_tbl LIKE orig_tbl;";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);
        log.info("Create table " + clickHouseQuery);
    }
    @Test
    public void testCreateTable() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "create table if not exists ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE if not exists ship_class(id Nullable(Int32),class_name Nullable(String),tonange Nullable(Decimal(10,2)),max_length Nullable(Decimal(10,2)),start_build Nullable(Int32),end_build Nullable(Int32),max_guns_size Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);

    }
    @Test
    public void testCreateTableWithNulLFields() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "create table ship_class(id int, class_name varchar(100), tonange decimal(10,2) not null, max_length decimal(65,2), start_build year, end_build year(4), max_guns_size int)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE ship_class(id Nullable(Int32),class_name Nullable(String),tonange Decimal(10,2) NOT NULL ,max_length Nullable(Decimal(65,2)),start_build Nullable(Int32),end_build Nullable(Int32),max_guns_size Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);

    }

    @Test
    public void testCreateTableWithPrimaryKey() {
        String createDBQuery = "CREATE TABLE IF NOT EXISTS 730b595f_d475_11ed_b64a_398b553542b2 (id INT AUTO_INCREMENT,x INT, PRIMARY KEY (id)) ENGINE = InnoDB;";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDBQuery, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE if not exists 730b595f_d475_11ed_b64a_398b553542b2(id Nullable(Int32),x Nullable(Int32),`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (id)"));
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
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE `salaries`(`emp_no` Int32 NOT NULL ,`salary` Int32 NOT NULL ,`from_date` Date32 NOT NULL ,`to_date` Date32 NOT NULL ,`_version` UInt64,`is_deleted` UInt8) Engine=ReplacingMergeTree(_version,is_deleted) ORDER BY (`emp_no`,`from_date`)"));
        log.info("Create table query" + clickHouseQuery.toString());
    }

    @Test
    public void testAlterDatabaseAddColumn() {

        String clickhouseExpectedQuery = "ALTER TABLE employees ADD COLUMN ssn_number Nullable(String)";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column ssn_number varchar(100)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY" + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    @Test
    public void testAlterDatabaseAddColumnNullable() {

        String addColumnNullable = "ALTER TABLE employees add column ssn_number varchar(100)";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(addColumnNullable, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY" + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        //Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    // Before, After
    @Test
    public void testAlterDatabaseAddMultipleColumns1() {
        String expectedClickHouseQuery = "ALTER TABLE employees ADD COLUMN ship_spec Nullable(String)  first, ADD COLUMN somecol Nullable(Int32)  after start_build,";
        StringBuffer clickHouseQuery = new StringBuffer();
        String query = "alter table employees add column ship_spec varchar(150) first, add somecol int after start_build, algorithm=instant;";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(query, "employees", clickHouseQuery);
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));

    }

    @Test
    public void testAlterDatabaseAddMultipleColumns() {

        String expectedClickHouseQuery = "ALTER TABLE employees ADD COLUMN ssn_number Nullable(String), ADD COLUMN home_address Nullable(String)";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column ssn_number varchar(100), add column home_address varchar(20)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);


        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));

    }

    @Test
    public void testAddColumnWithNull() {
        String expectedClickHouseQuery = "ALTER TABLE add_test ADD COLUMN optional Nullable(Bool)  DEFAULT 0";
        String mysqlQuery = "alter table add_test add column optional bool default 0 null;";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(mysqlQuery, "employees", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);
    }

    @Test
    public void testAddColumnWithNotNull() {
        String mysqlQuery = "alter table add_test add column customer_address varchar(100) not null, add column customer_name varchar(20) null;";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(mysqlQuery, "add_test", clickHouseQuery);

        String expectedCHQuery = "ALTER TABLE add_test ADD COLUMN customer_address String, ADD COLUMN customer_name Nullable(String)";
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);
    }

    @Test
    public void testAddDefault() {
        String expectedClickHouseQuery = "ALTER TABLE add_test ADD COLUMN foo Nullable(Int32)  DEFAULT 2";
        String mysqlQuery = "ALTER TABLE add_test ADD COLUMN foo INT DEFAULT 2;";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(mysqlQuery, "add_test", clickHouseQuery);

        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));
    }

    @Test
    public void testAddColumnWithoutExplicitNull() {
        String expectedClickHouseQuery = "ALTER TABLE add_test ADD COLUMN foo Nullable(Int32)";
        String mysqlQuery = "ALTER TABLE add_test ADD COLUMN foo INT;";
        StringBuffer clickHouseQuery = new StringBuffer();
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(mysqlQuery, "add_test", clickHouseQuery);

        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));
    }



    @Test
    public void testAlterDatabaseModifyColumns() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE contacts change column last_name new_name varchar(50) NULL;";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(alterDBAddColumn, "contacts", clickHouseQuery);
        //Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE contacts MODIFY COLUMN last_name Nullable(String)"));
        log.info("CLICKHOUSE QUERY" + clickHouseQuery);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE contacts MODIFY COLUMN last_name Nullable(String) \n" +
                "ALTER TABLE contacts RENAME COLUMN last_name to new_name"));

    }

    @Test
    public void testAlterTableWithNotNullAndDefault() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String sql = "ALTER TABLE products ADD stocks int not null";

        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "products", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("ALTER TABLE products ADD COLUMN stocks Int32"));
        StringBuffer clickHouseQuery2 = new StringBuffer();

        String defaultSql = "alter table add_test add column stocks bool null default 1;";
        MySQLDDLParserService mySQLDDLParserService3 = new MySQLDDLParserService();
        mySQLDDLParserService3.parseSql(defaultSql, "add_test", clickHouseQuery2);
        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase("ALTER TABLE add_test ADD COLUMN stocks Nullable(Bool)  DEFAULT 1"));

    }

    @Test
    public void testRenameColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test rename column stocks to options";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));

        StringBuffer clickHouseQuery2 = new StringBuffer();
        String sql2 = "alter table add_test rename column stocks to options, rename column options to stocks";
        MySQLDDLParserService mySQLDDLParserService3 = new MySQLDDLParserService();
        mySQLDDLParserService3.parseSql(sql2, "t2", clickHouseQuery2);

        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase(sql2));

    }

    @Test
    public void testChangeColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE add_test MODIFY COLUMN stocks Bool\n" +
                "ALTER TABLE add_test RENAME COLUMN stocks to options";
        String sql = "alter table add_test change column stocks options bool";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }

    @Test
    public void testChangeColumnFirst() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE add_test MODIFY COLUMN stocks Bool first\n" +
                "ALTER TABLE add_test RENAME COLUMN stocks to options";
        String sql = "alter table add_test change column stocks options bool first";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }

    @Test
    public void testChangeColumnAfter() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE add_test MODIFY COLUMN stocks Bool after col1\n" +
                "ALTER TABLE add_test RENAME COLUMN stocks to options";
        String sql = "alter table add_test change column stocks options bool after col1";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }

    @Test
    public void testChangeColumnWithDecimalScaleAndPrecision() {
        String sql = "alter table ship_class change column tonange tonange_new decimal(10,10)";

        StringBuffer clickHouseQuery = new StringBuffer();

        String expectedCHQuery = "ALTER TABLE ship_class MODIFY COLUMN tonange Decimal(10,10)\n" +
                "ALTER TABLE ship_class RENAME COLUMN tonange to tonange_new";

        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "t2", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
    }
    @Test
    public void testAddConstraints() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table t2 add constraint t2_pk_constraint primary key (1c), alter column `_` set default 1;\n";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "t2", clickHouseQuery);


        StringBuffer clickHouseQuery2 = new StringBuffer();

        String checkConstraintSql = "ALTER TABLE orders ADD CONSTRAINT check_revenue_positive CHECK (revenue >= 0);";
        MySQLDDLParserService mySQLDDLParserService3 = new MySQLDDLParserService();
        mySQLDDLParserService3.parseSql(checkConstraintSql, " ", clickHouseQuery2);


    }

    @Test
    public void testAddPrimaryKey() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table table1 add primary key (id)";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

    }

    @Test
    public void truncateTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "truncate table add_test";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void dropTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table add_test";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void dropTableIfExists() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table if exists add_test";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void dropMultipleTables() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table add_test, add_test2";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("drop table add_test,add_test2"));
    }

    @Test
    public void renameTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "rename table add_test to add_test_old";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void testAddIndex() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test add index if not exists ix_add_test_col1 using btree (col1) comment 'test index';\n";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);


    }


    @Test
    public void testDropConstraint() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table table1 add primary key (id)";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

    }

    @Test
    public void testAlterColumnAddDefault() {

        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test alter flag add default 1";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);


    }

    @Test
    public void testCreateDatabase() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "create database test_ddl";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void testDropColumn() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "alter table add_test drop column col1";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
    }

    @Test
    public void renameMultipleTables() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "rename /* gh-ost */ table `trade_prod`.`enriched_trade` to `trade_prod`.`_enriched_trade_del`, `trade_prod`.`_enriched_trade_gho` to `trade_prod`.`enriched_trade`\n";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("RENAME TABLE `trade_prod`.`enriched_trade` to `trade_prod`.`_enriched_trade_del`,`trade_prod`.`_enriched_trade_gho` to `trade_prod`.`enriched_trade`"));
    }
    @Test
    public void alterTableRenameTable() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "ALTER TABLE test_table rename to test_table_new";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("RENAME TABLE test_table to test_table_new"));
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
    public void checkIfDropOrTruncate(String sql, boolean expectedResult) {
        StringBuffer clickHouseQuery = new StringBuffer();

        AtomicBoolean isDropOrTruncate = new AtomicBoolean();
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "", clickHouseQuery, isDropOrTruncate);
        Assert.assertTrue(isDropOrTruncate.get() == expectedResult);

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

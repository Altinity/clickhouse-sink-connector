package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class MySqlDDLParserListenerImplTest {

    private static final Logger log = LoggerFactory.getLogger(MySqlDDLParserListenerImplTest.class);

    @Test
    public void testCreateTable() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "create table ship_class(id int, class_name varchar(100), tonange decimal(10,2), max_length decimal(10,2), start_build year, end_build year(4), max_guns_size int)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE ship_class(id Int32,class_name String,tonange Decimal(10, 0),max_length Decimal(10, 0),start_build Int32,end_build Int32,max_guns_size Int32,`_sign` Int8,`_version` UInt64) Engine=ReplacingMergeTree(_version) ORDER BY tuple()"));
        log.info("Create table " + clickHouseQuery);

    }
    @Test
    public void testCreateTableWithNulLFields() {
        StringBuffer clickHouseQuery = new StringBuffer();
        String createDB = "create table ship_class(id int, class_name varchar(100), tonange decimal(10,2) not null, max_length decimal(65,2), start_build year, end_build year(4), max_guns_size int)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(createDB, "Persons", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase("CREATE TABLE ship_class(id Nullable(Int32),class_name Nullable(String),tonange Decimal(10, 0),max_length Nullable(Decimal(10, 0)),start_build Nullable(Int32),end_build Nullable(Int32),max_guns_size Nullable(Int32),`_sign` Int8,`_version` UInt64) Engine=ReplacingMergeTree(_version) ORDER BY tuple()"));
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

        log.info("Create table query" + clickHouseQuery.toString());
    }

    @Test
    public void testAlterDatabaseAddColumn() {

        String clickhouseExpectedQuery = "ALTER TABLE employees ADD COLUMN ssn_number String";
        StringBuffer clickHouseQuery = new StringBuffer();
        String alterDBAddColumn = "ALTER TABLE employees add column ssn_number varchar(100)";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(alterDBAddColumn, "employees", clickHouseQuery);

        log.info("CLICKHOUSE QUERY" + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery != null && clickHouseQuery.length() != 0);
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(clickhouseExpectedQuery));
    }

    // Before, After
    @Test
    public void testAlterDatabaseAddMultipleColumns1() {
        String expectedClickHouseQuery = "ALTER TABLE employees ADD COLUMN ship_spec String first, ADD COLUMN somecol Int32 after start_build,";
        StringBuffer clickHouseQuery = new StringBuffer();
        String query = "alter table employees add column ship_spec varchar(150) first, add somecol int after start_build, algorithm=instant;";
        MySQLDDLParserService mySQLDDLParserService = new MySQLDDLParserService();
        mySQLDDLParserService.parseSql(query, "employees", clickHouseQuery);
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedClickHouseQuery));

    }

    @Test
    public void testAlterDatabaseAddMultipleColumns() {

        String expectedClickHouseQuery = "ALTER TABLE employees ADD COLUMN ssn_number String, ADD COLUMN home_address String";
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

        String expectedCHQuery = "ALTER TABLE add_test ADD COLUMN customer_address String, ADD COLUMN customer_name Nullable(String) ";
        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(expectedCHQuery));
        log.info("CLICKHOUSE QUERY: " + clickHouseQuery);
    }

    @Test
    public void testAddDefault() {
        String expectedClickHouseQuery = "ALTER TABLE add_test ADD COLUMN foo Int32 DEFAULT 2";
        String mysqlQuery = "ALTER TABLE add_test ADD COLUMN foo INT DEFAULT 2;";
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
//
//        StringBuffer clickHouseQuery2 = new StringBuffer();
//        String alterDBModifyColumn = "ALTER TABLE contacts\n" +
//                "  MODIFY last_name varchar(55) NULL\n" +
//                "    AFTER contact_type,\n" +
//                "  MODIFY first_name varchar(30) NOT NULL;";
//        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
//        mySQLDDLParserService2.parseSql(alterDBModifyColumn, "contacts", clickHouseQuery2);
//        Assert.assertTrue(clickHouseQuery2.toString().equalsIgnoreCase("ALTER TABLE contacts MODIFY COLUMN last_name Nullable(String)  AFTER contact_type, MODIFY COLUMN first_name String"));
    }

    @Test
    public void testAlterTableWithNotNullAndDefault() {

        StringBuffer clickHouseQuery = new StringBuffer();
        String sql = "ALTER TABLE products ADD stocks int not null";

        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "products", clickHouseQuery);

        StringBuffer clickHouseQuery2 = new StringBuffer();

        String defaultSql = "alter table add_test add column stocks bool null default 1;";
        MySQLDDLParserService mySQLDDLParserService3 = new MySQLDDLParserService();
        mySQLDDLParserService3.parseSql(defaultSql, "add_test", clickHouseQuery2);

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
    public void dropMultipleTables() {
        StringBuffer clickHouseQuery = new StringBuffer();

        String sql = "drop table add_test, add_test2";
        MySQLDDLParserService mySQLDDLParserService2 = new MySQLDDLParserService();
        mySQLDDLParserService2.parseSql(sql, "table1", clickHouseQuery);

        Assert.assertTrue(clickHouseQuery.toString().equalsIgnoreCase(sql));
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

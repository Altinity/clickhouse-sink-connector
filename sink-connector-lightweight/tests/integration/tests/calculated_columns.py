from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special,
)
from integration.tests.steps.mysql import *


@TestStep(Then)
def check_replication(self, table_name, actual_data, columns, clickhouse_node=None):
    """Check that the created table in MySQL exists in ClickHouse and the data is correct."""
    if clickhouse_node is None:
        clickhouse_node = self.context.clickhouse_node

    with By("making sure that the table was replicated on the ClickHouse side"):
        for retry in retries(timeout=40):
            with retry:
                clickhouse_node.query(f"EXISTS test.{table_name}", message="1")

    with And("checking that the calculated values are replicated in ClickHouse"):
        for retry in retries(timeout=40):
            with retry:
                data = clickhouse_node.query(
                    f"SELECT {columns} FROM test.{table_name} ORDER BY tuple(*) FORMAT CSV"
                )
                assert actual_data in data.output.strip(), error()


@TestScenario
def string_concatenation(self):
    """Create mysql table that contains the calculated column values."""
    mysql_node = self.context.mysql_node
    table_name = "table_" + getuid()

    with Given(
        f"I create a {table_name} table with calculated column with string concatenation"
    ):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"first_name VARCHAR(50) NOT NULL,last_name VARCHAR(50) NOT NULL,fullname varchar(101) "
            f"GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)),email VARCHAR(100) NOT NULL",
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(
            f"INSERT INTO {table_name} (id, first_name, last_name, email) VALUES (1, 'test', 'test2', 'test@gmail.com')"
        )

    check_replication(
        table_name=table_name, columns="fullname", actual_data="test test2"
    )


@TestScenario
def basic_arithmetic_operations(self):
    """Create mysql table that contains the calculated column values with basic arithmetic operations."""
    mysql_node = self.context.mysql_node
    table_name = "table_" + getuid()

    a = 5
    b = 4

    with Given(f"I create a {table_name} table with calculated column"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"a INT, b INT, sum_col INT AS (a + b), diff_col INT AS (a - b), prod_col INT AS (a * b), div_col DOUBLE AS (a / b)",
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} (id, a, b) VALUES (1, {a}, {b});")

    with Then("I check that the data was replicated correctly"):
        check_replication(
            table_name=table_name,
            columns="sum_col, diff_col, prod_col, div_col",
            actual_data="9,1,20,1.25",
        )


@TestScenario
def complex_expressions(self):
    """Create mysql table that contains the calculated column values with complex expressions."""
    mysql_node = self.context.mysql_node
    table_name = "table_" + getuid()

    base_salary = "350.32"
    bonus_rate = "520.65"

    with Given(f"I create a {table_name} table with calculated column"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"base_salary DECIMAL(10,2), bonus_rate DECIMAL(5,2), total_compensation DECIMAL(12,2) AS (base_salary + (base_salary * bonus_rate / 100))",
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(
            f"INSERT INTO {table_name} (id, base_salary, bonus_rate) VALUES (1, {base_salary}, {bonus_rate});"
        )

    with Then("I check that the data was replicated correctly"):
        check_replication(
            table_name=table_name,
            columns="total_compensation",
            actual_data="2174.26",
        )


@TestScenario
def nested(self):
    """Create mysql table that contains nested column with calculated values."""
    mysql_node = self.context.mysql_node
    table_name = "table_" + getuid()

    a = "1"
    b = "2"

    with Given(f"I create a {table_name} table with calculated column"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"a INT, b INT, c INT AS (a + b), d INT AS (c * 2)",
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} (id, a, b) VALUES (1, {a}, {b});")

    with Then("I check that the data was replicated correctly"):
        check_replication(
            table_name=table_name,
            columns="a,b,c,d",
            actual_data="1,2,3,6",
        )


@TestModule
@Name("calculated columns")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that the table is replicated when the source table has a calculated columns.

    Types of calculated columns:

    - string concatenation
    - basic arithmetic operations
    - complex expressions
    - nested columns
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

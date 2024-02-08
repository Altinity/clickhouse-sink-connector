from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_ColumnNames_Special,
)
from integration.tests.steps.alter import drop_column
from integration.tests.steps.common import generate_sample_mysql_value
from integration.tests.steps.service_settings_steps import *
from integration.tests.steps.sql import *
from integration.tests.steps.statements import all_mysql_datatypes_dict


@TestStep(Given)
def create_table_with_calculated_column(self, table_name, datatype="int", data="5"):
    """Create mysql table that contains the calculated column values."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with By(f"creating a {table_name} table with calculated column"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"\`{table_name}\`",
            mysql_columns=f"first_name VARCHAR(50) NOT NULL,last_name VARCHAR(50) NOT NULL,fullname varchar(101) "
            f"GENERATED ALWAYS AS (CONCAT(first_name,' ',last_name)),email VARCHAR(100) NOT NULL",
            clickhouse_table_engine=self.context.clickhouse_table_engines[0],
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(
            f"INSERT INTO {table_name} (id, first_name, last_name, email) VALUES (1, 'test', 'test2', 'test@gmail.com')"
        )

    with And("I make sure that the table was replicated on the ClickHouse side"):
        for retry in retries(timeout=40):
            with retry:
                clickhouse_node.query(f"EXISTS test.{table_name}", message="1")


@TestScenario
def calculated_column_creation(self):
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    table_name = "tb_" + getuid()

    with Given("I create a table with calculated columns"):
        create_table_with_calculated_column(table_name=table_name)

        for retry in retries(timeout=40):
            with retry:
                data = clickhouse_node.query(
                    f"SELECT * FROM test.{table_name} FORMAT CSV"
                )
                assert "test test2" in data.output.strip(), error()


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
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

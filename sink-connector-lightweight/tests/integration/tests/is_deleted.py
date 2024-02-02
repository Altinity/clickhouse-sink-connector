from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestScenario
def check_is_deleted(self):
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    table_name = "tb_" + getuid()

    with Given(f"I create the {table_name} table"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"\`{table_name}\`",
            mysql_columns="col1 varchar(255), col2 int, is_deleted int",
            clickhouse_table_engine=self.context.clickhouse_table_engines[0],
        )

    with When(f"I insert data into a {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} VALUES (1, 'test', 1, 2)")

    with Then("I check that the data was inserted correctly into the ClickHouse table"):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_node.query(f"SELECT * FROM test.{table_name}")
                clickhouse_node.query(f"DESCRIBE TABLE test.{table_name}")


@TestModule
@Name("is deleted")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableNames_Valid("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that the table is replicated when teh source table has a column named is_deleted.
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    Scenario(run=check_is_deleted)

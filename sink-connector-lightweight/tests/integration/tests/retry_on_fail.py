from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Interruption_ClickHouse_Instance_Stopped,
)
from integration.tests.steps.mysql import *


@TestScenario
def retry_on_fail(self):
    """Check that when ClickHouse instance is interrupted and the new table is created in MySQL, after re-running ClickHouse instance the sink connector still transfers data from MySQL to ClickHouse."""
    table_name = "table_" + getuid()
    clickhouse_node = self.context.clickhouse_node
    mysql_node = self.context.mysql_node

    with Given("I stop the ClickHouse service"):
        clickhouse_node.stop_clickhouse(safe=False)

    with When("I creat a table in MySQL"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"retry VARCHAR(16)",
        )

    with And("I insert data into the MySQL table"):
        mysql_node.query(f"INSERT INTO {table_name} VALUES (1, 'retry on fail');")

    with Then("I start the ClickHouse instance again"):
        clickhouse_node.start_clickhouse()

    with And("Check that the data was still replicated form MySQL to ClickHouse"):
        for retry in retries(timeout=30):
            with retry:
                clickhouse_values = clickhouse_node.query(
                    f"SELECT count(retry) FROM {self.context.database}.{table_name} FORMAT CSV"
                )

                assert clickhouse_values.output.strip() != "0", error()

        clickhouse_data = clickhouse_node.query(
            f"SELECT retry FROM {self.context.database}.{table_name} FORMAT CSV"
        )

        assert (
            clickhouse_data.output.strip().replace('"', "") == "retry on fail"
        ), error()


@TestModule
@Name("retry on fail")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Interruption_ClickHouse_Instance_Stopped(
        "1.0"
    )
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that sink connector retries data replication when the ClickHouse is interrupted for some reason.

    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

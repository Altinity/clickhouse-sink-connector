from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI,
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StopReplication,
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StartReplication,
)
from integration.tests.steps.alter import drop_column
from integration.tests.steps.clickhouse import check_if_table_was_created
from integration.tests.steps.service_settings import *
from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import all_mysql_datatypes_dict


@TestStep(Given)
def stop_replication(self):
    sink_node = self.context.sink_node

    with Given("I stop the replication"):
        sink_node.stop_replication()


@TestStep(Given)
def start_replication(self):
    sink_node = self.context.sink_node

    with Given("I start the replication"):
        sink_node.start_replication()


@TestStep(Given)
def check_replication_status(self):
    sink_node = self.context.sink_node

    with Given("I check the replication status"):
        sink_node.show_replication_status()


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StopReplication("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StartReplication("1.0"),
)
def check_that_replication_can_be_stopped(self):
    table_name = f"tb_{getuid()}"

    try:
        with Given("I stop the replication"):
            stop_replication()

        with And("I create a table in MySQL"):
            create_mysql_to_clickhouse_replicated_table(
                name=f"\`{table_name}\`",
                mysql_columns=f"col1 varchar(255), col2 int",
                clickhouse_table_engine=self.context.clickhouse_table_engines[0],
            )

        with Then("I check that the table was not replicated on the ClickHouse side"):
            with By("waiting for 5 seconds to give the replication time to start"):
                time.sleep(5)

            with And("checking that the table was not replicated"):
                for retry in retries(timeout=40):
                    with retry:
                        check_if_table_was_created(table_name=table_name, message=0)
    finally:
        with Finally("I start the replication"):
            start_replication()
            for retry in retries(timeout=40):
                with retry:
                    check_if_table_was_created(table_name=table_name)


@TestModule
@Name("cli")
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI("1.0"))
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

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

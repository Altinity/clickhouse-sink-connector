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
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    test_values = "(1, 'test', 1)"
    try:
        with Given("I start replication on the sink connector node"):
            start_replication()

        with And(
            "I create a table in MySQL and check that it was also created in ClickHouse"
        ):
            create_mysql_to_clickhouse_replicated_table(
                name=f"\`{table_name}\`",
                mysql_columns=f"col1 varchar(255), col2 int",
                clickhouse_table_engine=self.context.clickhouse_table_engines[0],
            )

            check_if_table_was_created(table_name=table_name)

        with When("I stop replication"):
            stop_replication()

        with And("I insert data into the table"):
            mysql_node.query(f"INSERT INTO {table_name} VALUES {test_values}")

        with Then("I check that the table was not replicated on the ClickHouse side"):
            with By(
                "waiting for 5 seconds to make sure that the table was not replicated"
            ):
                time.sleep(5)

            with And("checking that the table was not replicated"):
                clickhouse_node.query(
                    f"SELECT * FROM test.{table_name} FORMAT TabSeparated"
                )
    finally:
        with Finally("I start the replication again"):
            start_replication()


@TestModule
@Name("cli")
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI("1.0"))
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that actions provided inside the sink-connector-client script work as intended

    List of available actions:
       - start_replica              Start the replication
       - stop_replica               Stop the replication
       - show_replica_status        Status of replication
       - change_replication_source  Update binlog file/position and gtids
       - lsn                        Update lsn (For postgreSQL)
       - help, h                    Shows a list of commands or help for one command
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

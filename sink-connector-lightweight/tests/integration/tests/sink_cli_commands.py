from integration.requirements.requirements import (
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI,
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StopReplication,
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StartReplication,
)
from integration.tests.steps.alter import drop_column
from integration.tests.steps.clickhouse import (
    check_if_table_was_created,
    validate_data_in_clickhouse_table,
)
from integration.tests.steps.service_settings import *
from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import all_mysql_datatypes_dict


@TestStep(Given)
def stop_replication(self):
    """Stop ClickHouse Sink Connector replication."""
    sink_node = self.context.sink_node

    with Given("I stop the replication"):
        sink_node.stop_replication()


@TestStep(Given)
def start_replication(self):
    """Start ClickHouse Sink Connector replication."""
    sink_node = self.context.sink_node

    with Given("I start the replication"):
        sink_node.start_replication()


@TestStep(Given)
def check_replication_status(self):
    """Check ClickHouse Sink Connector replication status."""
    sink_node = self.context.sink_node

    with Given("I check the replication status"):
        sink_node.show_replication_status()


@TestStep(Given)
def create_and_validate_table(self, table_name):
    """Create a table in MySQL and check that it was also created in ClickHouse."""
    with By(
        "creating a table in MySQL and checking that it was also created in ClickHouse"
    ):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"col1 varchar(255), col2 int",
        )

        check_if_table_was_created(table_name=table_name)


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StopReplication("1.0")
)
def check_that_replication_can_be_stopped(self):
    """Check that replication can be stopped via using the sink-connector-client script."""
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
            create_and_validate_table(table_name=table_name)

        with When("I stop replication"):
            stop_replication()

        with And("I insert data into the table"):
            mysql_node.query(f"INSERT INTO {table_name} VALUES {test_values}")

        with Then("I check that the table was not replicated on the ClickHouse side"):
            with By(
                "waiting for 5 seconds to make sure that the table was not replicated"
            ):
                time.sleep(5)

            with And("checking that the data on the table was not replicated"):
                data = clickhouse_node.query(
                    f"SELECT * FROM test.{table_name} FORMAT TabSeparated"
                )

                assert data.output.strip() == "", error()
    finally:
        with Finally("I start the replication again"):
            start_replication()


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI_StartReplication("1.0")
)
def check_that_replication_can_be_started(self):
    """Check that replication can be started via using the sink-connector-client script."""
    table_name = f"tb_{getuid()}"
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node
    test_values = "(1,'test',1)"
    try:
        with Given("I start replication on the sink connector node"):
            start_replication()

        with And("I stop the replication"):
            stop_replication()

        with And("I start the replication again"):
            start_replication()

        with When(
            "I create a table in MySQL and check that it was also created in ClickHouse"
        ):
            create_and_validate_table(table_name=table_name)

        with And("I insert data into the table"):
            mysql_node.query(f"INSERT INTO {table_name} VALUES {test_values}")

        with Then("I check that the table was replicated on the ClickHouse side"):
            expected_output = "1,test,1"

            validate_data_in_clickhouse_table(
                table_name=table_name,
                expected_output=expected_output,
                statement="id, col1, col2",
            )
    finally:
        with Finally("I start the replication again"):
            start_replication()


@TestFeature
@Name("cli")
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_CLI("1.0"))
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that actions provided inside the sink-connector-client script work as intended.
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

    Scenario(run=check_that_replication_can_be_stopped)
    Scenario(run=check_that_replication_can_be_started)

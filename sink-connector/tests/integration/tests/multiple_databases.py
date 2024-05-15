from integration.tests.steps.sql import *
from integration.tests.steps.statements import *
from integration.tests.steps.service_settings_steps import *


@TestScenario
def test_table_creation(self, database="test"):
    table_name = "tb_" + getuid()
    mysql_columns = "col1 varchar(255), col2 int"
    clickhouse_node = self.context.cluster.node("clickhouse")

    with Given(f"I create connection between kafka and sink connector"):
        init_sink_connector(database="test", table=table_name)

    with And("I create MySQL to ClickHouse replicated table"):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name,
            mysql_columns=mysql_columns,
        )

    with When("I insert data in MySQL table"):
        insert_values(table_name=table_name, values="(1, 'test', 1)")

    with Then("I check that the table was replicated"):
        clickhouse_node.query(f"SELECT * FROM {database}.{table_name}")


# @TestStep(Given)
# def create_source_database(self, database_name):
#     """Create a MySQL database."""
#     create_mysql_database(database_name=database_name)
#
#
# @TestStep(Given)
# def create_destination_database(self, database_name):
#     """Create a ClickHouse database."""
#     create_clickhouse_database(name=database_name)


@TestModule
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Inserts("1.0"))
@Name("multiple databases")
def module(self):
    """Check that sink connector can replicate data from multiple databases."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    Scenario(run=test_table_creation)

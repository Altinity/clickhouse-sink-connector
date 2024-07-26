from testflows.core import *
from integration.tests.steps.mysql.mysql import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.service_configurations import (
    init_sink_connector,
    init_debezium_connector,
)
from integration.tests.steps.statements import (
    all_ch_datatypes_dict,
    all_mysql_datatypes_dict,
)


@TestOutline
def auto_create_table(
    self,
    column_datatype,
    column_name,
    database_name="test",
    node=None,
    table_name=None,
    replicate=False,
):
    """Check that tables created on the source database are replicated on the destination."""

    if table_name is None:
        table_name = "table_" + getuid()

    if node is None:
        node = self.context.cluster.node("mysql-master")

    with Given("I initialize sink connector for the given database and table"):
        init_sink_connector(
            auto_create_tables=True,
            topics=f"SERVER5432.{database_name}.{table_name}",
            auto_create_replicated_tables=replicate,
        )

    with And("I create a table on the source database"):
        create_mysql_table(
            table_name=table_name,
            database_name=database_name,
            mysql_node=node,
            columns=f"{column_name} {column_datatype}",
        )

    with When("I insert values into the table"):
        insert(
            table_name=table_name,
            values=f"{generate_sample_mysql_value('INT')}, {generate_sample_mysql_value(column_datatype)}",
        )

    with Then("I check that the table is replicated on the destination database"):
        check_if_table_was_created(table_name=table_name)


@TestScenario
def check_auto_creation_all_datatypes(self):
    """Check that tables created on the source database are replicated on the destination."""

    for name, datatype in all_mysql_datatypes_dict.items():
        auto_create_table(
            column_name=name,
            column_datatype=datatype,
        )


@TestScenario
def alters(self):
    """Check that alter statements performed on the source are replicated to the destination."""
    note(1)


@TestScenario
def inserts(self):
    """Check that inserts are replicated to the destination."""
    note(1)


@TestScenario
def deletes(self):
    """Check that deletes are replicated to the destination."""
    note(1)


@TestScenario
def updates(self):
    """Check that updates are replicated to the destination."""
    note(1)


@TestFeature
@Name("replication")
def feature(self):
    """Check that actions performed on the source database are replicated on the destination database."""
    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()

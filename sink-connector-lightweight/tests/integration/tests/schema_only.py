from integration.tests.steps.mysql import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.sink_configurations import config_with_schema_only


@TestStep(Given)
def create_table_structure(self, table_name):
    """Create mysql table that is used to only replicate table schema."""
    mysql_node = self.context.mysql_node
    clickhouse_node = self.context.clickhouse_node

    with By(f"creating a {table_name} table"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"col1 varchar(255), col2 int",
        )

    with And(f"inserting data into the {table_name} table"):
        mysql_node.query(f"INSERT INTO {table_name} VALUES (1, 'test', 1)")

    with And("I make sure that the table was replicated on the ClickHouse side"):
        for retry in retries(timeout=40):
            with retry:
                clickhouse_node.query(f"EXISTS test.{table_name}", message="1")


@TestScenario
def check_schema_only(self):
    """Check that when schem_only mode is used in configurations, table is created but the data is not replicated."""
    table_name = "tb_" + getuid()

    with Given("I create table and populate it with data"):
        create_table_structure(table_name=table_name)

    with Then("I check that the data was not replicated into the ClickHouse table"):
        for retry in retries(timeout=40, delay=1):
            with retry:
                clickhouse_data = self.context.clickhouse_node.query(
                    f"SELECT * FROM test.{table_name}"
                )
                assert (
                    "1" and "test" and "1" not in clickhouse_data.output.strip()
                ), error()


@TestModule
@Name("schema only")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that it is possible to only replicate the schema of the table using snapshot mode: schema-only.
    """
    config_file = os.path.join("env", "auto", "configs", "schema_only.yml")

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    with Given(
        "I create a new ClickHouse Sink Connector configuration with schema-only mode"
    ):
        config_with_schema_only()

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

from faker import Faker
from random import randint

from integration.helpers.common import change_sink_configuration
from integration.tests.steps.clickhouse import (
    check_if_table_was_created, create_clickhouse_database
)
from integration.tests.steps.mysql import *
from integration.tests.steps.service_settings import *


@TestStep(When)
def create_table_with_random_data(self, table_name, database=None, node=None):
    """Create a MySQL table with random data."""
    if database is None:
        database = "test"

    if node is None:
        node = self.context.cluster.node("mysql-master")

    # Create table
    node.query(
        f"CREATE TABLE {database}.\`{table_name}\` (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), address VARCHAR(255), age INT);"
    )

    # Generate random data
    fake = Faker()
    for _ in range(10):
        name = fake.name()
        address = fake.address().replace("\n", ", ")
        age = randint(20, 60)

        # Insert data into table
        node.query(
            f"INSERT INTO {database}.{table_name} (name, address, age) VALUES ('{name}', '{address}', {age});"
        )

        return f"{name}, {address}, {age}"


@TestStep(Given)
def create_source_database(self, database_name):
    """Create a MySQL database."""
    create_mysql_database(database_name=database_name)


@TestStep(Given)
def create_destination_database(self, database_name):
    """Create a ClickHouse database."""
    create_clickhouse_database(name=database_name)


@TestStep(Given)
def create_source_and_destination(self, database_name):
    """Create a MySQL and ClickHouse databases."""
    create_source_database(database_name=database_name)
    create_destination_database(database_name=database_name)


@TestStep(Given)
def configuration_with_specific_databases(self, databases=None, config_name=None):
    """Specify the databases to replicate in the ClickHouse Sink Connector configuration."""
    if databases is None:
        databases = "test"

    config_file = self.context.config_file

    if config_name is None:
        config_name = os.path.join(config_file, "selected_databases.yml")
    else:
        config_name = os.path.join(config_file, config_name)

    change_sink_configuration(
        values={"database.include.list": databases},
        config_file=config_name,
    )


@TestStep(When)
def create_sample_table(self, table_name, database_name=None, message=None):
    """Create a sample table in MySQL."""

    if message is None:
        message = "1"

    if database_name is None:
        database_name = "test"

    table_values = "(1, 'test', 1)"

    with By("creating a sample table in MySQL"):
        create_mysql_to_clickhouse_replicated_table(
            name=f"\`{table_name}\`",
            mysql_columns=f"col1 varchar(255), col2 int",
            database_name=database_name,
            clickhouse_table_engine=self.context.clickhouse_table_engines[0],
        )

    with And("inserting data into the table"):
        insert(table_name=table_name, database_name=database_name, values=table_values)

    with And("validating that the table was replicated in ClickHouse"):
        for retry in retries(timeout=40):
            with retry:
                check_if_table_was_created(
                    table_name=table_name, database_name=database_name, message=message
                )


@TestStep(Given)
def create_source_and_destination_databases(self, database_name):
    """Create MySQL and ClickHouse databases."""
    with By(f"creating a ClickHouse database {database_name}"):
        create_clickhouse_database(name=database_name)

    with And(f"creating a a MySQL database {database_name}"):
        create_mysql_database(database_name=database_name)


@TestCheck
def check_if_tables_were_replicated(self, table_name, database_name=None):
    """Check if tables were replicated in ClickHouse."""
    if database_name is None:
        database_name = "test"

    check_if_table_was_created(
        table_name=table_name, database_name=database_name, message=1
    )

@TestModule
@Name("multiple databases")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDownstreamServers("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that auto table replication works when there are multiple databases in MySQL.
    """

    self.context.database_1 = "database_1"
    self.context.database_2 = "database_2"
    self.context.database_3 = "database_3"
    self.context.database_4 = "database_4"

    with Pool(4) as pool:
        for database_name in [
            self.context.database_1,
            self.context.database_2,
            self.context.database_3,
            self.context.database_4,
        ]:
            Scenario(
                test=create_source_and_destination_databases,
                parallel=True,
                executor=pool,
            )(database_name=database_name)

    self.context.config_file = os.path.join("env", "auto", "configs")

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    with Given("I create a new ClickHouse Sink Connector configuration with  mode"):
        remove_sink_configuration(
            key="database.include.list",
            config_file=os.path.join(
                self.context.config_file, "multiple_databases.yml"
            ),
        )

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

from testflows.core import *

from integration.helpers.common import getuid
from integration.tests.replication import replication
from integration.tests.steps.configurations import init_sink_connector_auto_created
from integration.tests.steps.mysql.mysql import (
    create_mysql_database,
    generate_special_case_names,
    create_sample_table,
)
from integration.tests.steps.clickhouse import (
    create_clickhouse_database,
    check_if_table_was_created,
)


@TestStep(Given)
def create_source_database(self, database_name):
    """Create a MySQL database."""
    create_mysql_database(database_name=database_name)


@TestStep(Given)
def create_destination_database(self, database_name):
    """Create a ClickHouse database."""
    create_clickhouse_database(name=database_name)


@TestStep(Given)
def create_source_and_destination_databases(self, database_name=None):
    """Create databases where MySQL database is source and ClickHouse database is destination."""
    if database_name is None:
        database_name = "test"

    with By(f"creating a ClickHouse database {database_name}"):
        create_clickhouse_database(name=database_name)

    with And(f"creating a a MySQL database {database_name}"):
        create_mysql_database(database_name=database_name)


@TestScenario
def check_database_creation(self):
    """Check if the databases are created."""
    databases = generate_special_case_names(
        self.context.number_of_databases, max_length=63
    )
    table_name = f"table_{getuid()}"

    with Given("I create databases with different combinations in names"):
        for database in databases:
            create_source_and_destination_databases(database_name=database)

    with And("I create a sample table on each of these databases"):
        for database in databases:
            with By("initializing sink connector with the database"):
                init_sink_connector_auto_created(
                    topics=f"SERVER5432.{database}.{table_name}"
                )
            with And("creating a sample table on that database"):
                create_sample_table(database=database, table_name=table_name)

    with Then("I validate that the table was created on each of these databases"):
        for database in databases:
            check_if_table_was_created(database_name=database, table_name=table_name)


@TestScenario
def create_databases_with_special_names(self):
    """Create databases with special names on source and destination manually."""
    update_config = {
        "auto.create.tables": "true",
        "auto.create.tables.replicated": "false",
    }
    databases = generate_special_case_names(
        self.context.number_of_databases, max_length=63
    )
    with Given("I create databases with special characters in the name"):
        for database in databases:
            create_source_and_destination_databases(database_name=rf"\`{database}\`")

    Check(test=replication)(databases=databases, update=update_config)


@TestScenario
def auto_create_databases_with_special_names(self):
    """Auto create databases with special characters in the name on source and wait and check it's created on destination."""
    databases = generate_special_case_names(
        self.context.number_of_databases, max_length=63
    )
    update_config = {
        "auto.create.tables": "true",
        "auto.create.tables.replicated": "false",
    }
    with Given("I create databases with special characters in the name"):
        for database in databases:
            create_source_database(database_name=rf"\`{database}\`")

    Check(test=replication)(databases=databases, update=update_config)


@TestFeature
@Name("multiple databases")
def feature(self, number_of_databases=100):
    """Validate sink connector with multiple databases."""
    self.context.number_of_databases = number_of_databases

    Scenario(run=check_database_creation)
    Scenario(run=create_databases_with_special_names)
    Scenario(run=auto_create_databases_with_special_names)

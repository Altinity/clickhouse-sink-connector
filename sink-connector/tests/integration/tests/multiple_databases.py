from testflows.core import *
from integration.tests.replication import replication
from integration.tests.steps.mysql.mysql import (
    create_mysql_database,
    generate_special_case_names,
)
from integration.tests.steps.clickhouse import create_clickhouse_database


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
def create_databases_with_special_names(self):
    """Create databases with special names on source and destination manually."""
    update_config = {
        "auto.create.tables": "true",
        "auto.create.tables.replicated": "false",
    }
    databases = generate_special_case_names(self.context.number_of_databases)

    for database in databases:
        create_source_and_destination_databases(database_name=rf"\`{database}\`")

    replication(databases=databases, update=update_config)


@TestScenario
def auto_create_databases_with_special_names(self):
    """Auto create databases with special characters in the name on source and wait and check it's created on destination."""
    databases = generate_special_case_names(self.context.number_of_databases)
    update_config = {
        "auto.create.tables": "true",
        "auto.create.tables.replicated": "false",
    }
    for database in databases:
        create_source_database(database_name=rf"\`{database}\`")

    replication(databases=databases, update=update_config)


@TestFeature
@Name("multiple databases")
def feature(self, number_of_databases=100):
    """Validate sink connector with multiple databases."""
    self.context.number_of_databases = number_of_databases

    Scenario(run=create_databases_with_special_names)
    Scenario(run=auto_create_databases_with_special_names)

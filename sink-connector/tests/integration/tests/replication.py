from testflows.core import *

from integration.helpers.common import getuid
from integration.tests.steps.clickhouse import (
    check_if_table_was_created,
    validate_data_in_clickhouse_table,
    check_column,
    select,
    get_random_value_from_column,
)
from integration.tests.steps.mysql.alters import (
    add_column,
    change_column,
    modify_column,
    drop_column,
    drop_primary_key,
    add_primary_key,
)
from integration.tests.steps.mysql.deletes import delete_all_records, delete
from integration.tests.steps.mysql.mysql import (
    create_mysql_table,
    insert,
    generate_sample_mysql_value,
)
from integration.tests.steps.mysql.updates import update
from integration.tests.steps.service_configurations import (
    init_sink_connector,
    init_debezium_connector,
)
from integration.tests.steps.sql import generate_interesting_table_names
from integration.tests.steps.statements import (
    all_mysql_datatypes_dict,
)


@TestOutline
def auto_create_table(
    self,
    column_datatype="VARCHAR(255)",
    column_name="name",
    node=None,
    table_name=None,
    replicate=False,
    validate_values=True,
    multiple_inserts=False,
):
    """Check that tables created on the source database are replicated on the destination."""
    databases = self.context.databases

    if type(databases) is not list:
        databases = [databases]

    if table_name is None:
        table_name = "table_" + getuid()

    if node is None:
        node = self.context.cluster.node("mysql-master")
    for database in databases:
        with Given("I initialize sink connector for the given database and table"):
            init_sink_connector(
                auto_create_tables=True,
                topics=f"SERVER5432.{database}.{table_name}",
                auto_create_replicated_tables=replicate,
            )

        with And("I create a table on the source database"):
            create_mysql_table(
                table_name=table_name,
                database_name=database,
                mysql_node=node,
                columns=f"{column_name} {column_datatype}",
            )

        with When("I insert values into the table"):
            if not multiple_inserts:
                table_values = f"{generate_sample_mysql_value('INT')}, {generate_sample_mysql_value(column_datatype)}"
                insert(table_name=table_name, values=table_values)
            else:
                for _ in range(10):
                    insert(
                        table_name=table_name,
                        values=f"{generate_sample_mysql_value('INT')}, {generate_sample_mysql_value(column_datatype)}",
                    )
        if not validate_values:
            table_values = ""

        with Then("I check that the table is replicated on the destination database"):
            with Check(f"table with {column_datatype} was replicated"):
                check_if_table_was_created(
                    database_name=database, table_name=table_name
                )
            if validate_values:
                validate_data_in_clickhouse_table(
                    table_name=table_name,
                    expected_output=table_values.replace("'", ""),
                    database_name=database,
                    statement=f"id, {column_name}",
                )


@TestStep(Given)
def auto_create_with_multiple_inserts(self, table_name=None):
    """Create a table with multiple inserts."""
    auto_create_table(
        table_name=table_name,
        multiple_inserts=True,
        validate_values=False,
    )


@TestScenario
def check_auto_creation_all_datatypes(self, table_name=None):
    """Check that tables created on the source database are replicated on the destination."""
    for name, datatype in all_mysql_datatypes_dict.items():
        (
            Check(
                test=auto_create_table,
                name=f"auto table creation with {datatype} datatype",
            )(
                column_name=name,
                column_datatype=datatype,
                table_name=table_name,
            )
        )


@TestScenario
def auto_creation_different_table_names(self):
    """Check that tables with all datatypes are replicated on the destination table when tables have names with special cases."""
    table_names = generate_interesting_table_names(self.context.number_of_tables)

    for table_name in table_names:
        Check(
            test=auto_create_table,
            name=f"auto table creation with {table_name} table name",
        )(
            table_name=table_name,
        )


@TestScenario
def add_column_on_source(self, database):
    """Check that the column is added on the table when we add a column on a database."""
    table_name = f"table_{getuid()}"
    column = "new_col"

    with Given("I create a table on multiple databases"):
        auto_create_table(table_name=table_name, databases=database)

    with When("I add a column on the table"):
        add_column(table_name=table_name, column_name=column, database=database)

    with Then("I check that the column was added on the table"):
        check_column(table_name=table_name, column_name=column, database=database)


@TestScenario
def change_column_on_source(self, database):
    """Check that the column is changed on the table when we change a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column = "new_col"
    new_column_type = "varchar(255)"

    with Given("I create a table on multiple databases"):
        auto_create_table(table_name=table_name, databases=database)

    with When("I change a column on the table"):
        change_column(
            table_name=table_name,
            database=database,
            column_name=column,
            new_column_name=new_column,
            new_column_type=new_column_type,
        )

    with Then("I check that the column was changed on the table"):
        check_column(table_name=table_name, column_name=new_column, database=database)


@TestScenario
def modify_column_on_source(self, database):
    """Check that the column is modified on the table when we modify a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column_type = "varchar(255)"

    with Given("I create a table on multiple databases"):
        auto_create_table(table_name=table_name, databases=database)

    with When("I modify a column on the table"):
        modify_column(
            table_name=table_name,
            database=database,
            column_name=column,
            new_column_type=new_column_type,
        )

    with Then("I check that the column was modified on the table"):
        check_column(
            table_name=table_name,
            database=database,
            column_name=column,
            column_type=new_column_type,
        )


@TestScenario
def drop_column_on_source(self, database):
    """Check that the column is dropped from the table when we drop a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"

    with Given("I create a table on multiple databases"):
        auto_create_table(table_name=table_name, databases=database)

    with When("I drop a column on the table"):
        drop_column(table_name=table_name, database=database, column_name=column)

    with Then("I check that the column was dropped from the table"):
        check_column(table_name=table_name, database=database, column_name="")


@TestScenario
def add_primary_key_on_a_database(self, database):
    """Check that the primary key is added to the table when we add a primary key on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"

    with Given("I create a table on multiple databases"):
        auto_create_table(table_name=table_name, databases=database)

    with When("I add a primary key on the table"):
        drop_primary_key(table_name=table_name, database=database)
        add_primary_key(table_name=table_name, database=database, column_name=column)

    with Then("I check that the primary key was added to the table"):
        check_column(table_name=table_name, database=database, column_name=column)


@TestScenario
def delete_all_records_from_source(self):
    """Check that records are deleted from the destination table when we delete all columns on the source."""
    table_name = f"table_{getuid()}"

    with Given("I create a table on multiple databases"):
        auto_create_with_multiple_inserts(table_name=table_name)

    for database in self.context.databases:
        with When("I delete columns on the source"):
            delete_all_records(table_name=table_name, database=database)

        with Then("I check that the primary key was added to the table"):
            select(table_name=table_name, database=database, manual_output="")


@TestScenario
def delete_specific_records(self):
    """Check that records are deleted from the destination table when we execute DELETE WHERE on source table."""
    table_name = f"table_{getuid()}"

    with Given("I create a table on multiple databases"):
        auto_create_with_multiple_inserts(table_name=table_name)

    for database in self.context.databases:
        with When("I delete columns on the source"):
            delete(table_name=table_name, database=database, condition="WHERE id > 0")

        with Then("I check that the primary key was added to the table"):
            select(table_name=table_name, database=database, manual_output="")


@TestScenario
def update_record_on_source(self):
    """Check that the record is updated on the destination table when we update a record on the source."""
    table_name = f"table_{getuid()}"

    with Given("I create a table on multiple databases"):
        auto_create_with_multiple_inserts(table_name=table_name)

    for database in self.context.databases:
        with When("I update a record on the source"):
            random_value = get_random_value_from_column(
                database=database, table_name=table_name, column_name="id"
            )

            update(
                table_name=table_name,
                database=database,
                set="id 5",
                condition=f"id = {random_value}",
            )

        with Then("I check that the primary key was added to the table"):
            select(
                table_name=table_name,
                database=database,
                where=f"id = {random_value}",
                manual_output=random_value,
            )


@TestSuite
def table_creation(self):
    """Check that tables created on the source database are correctly replicated on the destination."""
    Scenario(run=check_auto_creation_all_datatypes)
    Scenario(run=auto_creation_different_table_names)


@TestSuite
def alters(self):
    """Check that alter statements performed on the source are replicated to the destination."""
    databases = self.context.databases

    for database in databases:
        Scenario(run=add_column_on_source, database=database)
        Scenario(run=change_column_on_source, database=database)
        Scenario(run=modify_column_on_source, database=database)
        Scenario(run=drop_column_on_source, database=database)
        Scenario(run=add_primary_key_on_a_database, database=database)


@TestSuite
def deletes(self):
    """Check that deletes are replicated to the destination."""
    databases = self.context.databases

    for database in databases:
        Scenario(run=delete_all_records_from_source, database=database)
        Scenario(run=delete_specific_records, database=database)

@TestSuite
def updates(self):
    """Check that updates are replicated to the destination."""
    Scenario(run=update_record_on_source)


@TestFeature
@Name("replication")
def feature(self, number_of_tables=20, databases: list = None):
    """Check that actions performed on the source database are replicated on the destination database."""

    self.context.number_of_tables = number_of_tables

    if databases is None:
        self.context.databases = ["test"]
    else:
        self.context.databases = databases

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for suite in loads(current_module(), Suite):
        suite()

from integration.helpers.common import change_sink_configuration
from integration.tests.steps.clickhouse import (
    check_if_table_was_created,
    create_clickhouse_database,
    validate_data_in_clickhouse_table,
)
from integration.tests.steps.mysql import *
from integration.tests.steps.service_settings import *


@TestStep(Given)
def create_source_database(self, database_name):
    """Create a MySQL database."""
    create_mysql_database(database_name=database_name)


@TestStep(Given)
def create_destination_database(self, database_name):
    """Create a ClickHouse database."""
    create_clickhouse_database(name=database_name)


@TestStep(Given)
def configuration_with_specific_databases(self, databases=None, config_name=None):
    """Specify the list of databases to replicate in the ClickHouse Sink Connector configuration."""
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


@TestStep(Given)
def create_sample_values(self):
    """Create sample values to insert into the table."""
    return f'{generate_sample_mysql_value("INT")} ,{generate_sample_mysql_value("VARCHAR")}, {generate_sample_mysql_value("INT")}'


@TestOutline
def create_and_validate_sample_table(
    self, table_name, database_name=None, message=None
):
    """Create a sample table in MySQL and validate that it was replicated in ClickHouse."""

    if message is None:
        message = "1"

    if database_name is None:
        database_name = "test"

    table_values = create_sample_values()

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
        for retry in retries(timeout=10, delay=1):
            with retry:
                validate_data_in_clickhouse_table(
                    table_name=table_name,
                    expected_output=table_values,
                    database_name=database_name,
                )


@TestStep(Given)
def create_source_and_destination_databases(self, databases=None):
    """Create MySQL and ClickHouse databases."""
    if databases is None:
        databases = ["test"]

    for database in databases:
        with By(f"creating a ClickHouse database {database}"):
            create_clickhouse_database(name=databases)

        with And(f"creating a a MySQL database {database}"):
            create_mysql_database(database_name=database)


@TestStep(Given)
def create_databases(self, databases=None):
    """Create MySQL and ClickHouse databases."""
    if databases is None:
        databases = []

    with Pool(4) as pool:
        for database_name in databases:
            Scenario(
                test=create_source_and_destination_databases,
                parallel=True,
                executor=pool,
            )(database_name=database_name)


@TestCheck
def check_if_tables_were_replicated(self, table_name, database_name=None):
    """Check if tables were replicated in ClickHouse."""
    if database_name is None:
        database_name = "test"

    check_if_table_was_created(
        table_name=table_name, database_name=database_name, message=1
    )


@TestStep(Given)
def check_replication_database1(self, table_name):
    """Create a table on the database_1."""
    create_and_validate_sample_table(
        table_name=table_name, database_name=self.context.database_1
    )


@TestStep(Given)
def check_replication_database2(self, table_name):
    """Create a table on the database_2."""
    create_and_validate_sample_table(
        table_name=table_name, database_name=self.context.database_2
    )


@TestStep(Given)
def check_replication_database3(self, table_name):
    """Create a table on the database_3."""
    create_and_validate_sample_table(
        table_name=table_name, database_name=self.context.database_3
    )


@TestStep(Given)
def check_replication_database4(self, table_name):
    """Create a table on the database_4."""
    create_and_validate_sample_table(
        table_name=table_name, database_name=self.context.database_4
    )


@TestStep
def check_replication_all_databases(self, table1, table2, table3, table4):
    """Create tables on all databases."""
    create_and_validate_sample_table(
        table_name=table1, database_name=self.context.database_1
    )
    create_and_validate_sample_table(
        table_name=table2, database_name=self.context.database_2
    )
    create_and_validate_sample_table(
        table_name=table3, database_name=self.context.database_3
    )
    create_and_validate_sample_table(
        table_name=table4, database_name=self.context.database_4
    )


@TestCheck
def check_replication_on_multiple_databases(self, create_table_1, create_table_2):
    """Check that the tables are correctly replicated on different number of databases."""
    table_name_1 = "tb1_" + getuid()
    table_name_2 = "tb2_" + getuid()

    with Given(
        f"I create tables to replicate on databases",
        description=f"{create_table_1.__name__} and {create_table_2.__name__}",
    ):
        if create_table_1.__name__ != create_table_2.__name__:
            create_table_1(table_name=table_name_1)
            create_table_2(table_name=table_name_2)
        else:
            create_table_1(table_name=table_name_1)


@TestSketch
def multiple_databases(self):
    actions = {
        check_replication_database1,
        check_replication_database2,
        check_replication_database3,
        check_replication_database4,
        check_replication_all_databases,
    }

    check_replication_on_multiple_databases(
        create_table_1=either(*actions, i="table_1"),
        create_table_2=either(*actions, i="table_2"),
    )


@TestModule
@Name("multiple databases")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
    database_1="database_1",
    database_2="database_2",
    database_3="database_3",
    database_4="database_4",
):
    """
    Check that auto table replication works when there are multiple databases in MySQL.

    Combinations:
        - Create tables on different databases.
        - Create tables on all databases.
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    self.context.database_1 = database_1
    self.context.database_2 = database_2
    self.context.database_3 = database_3
    self.context.database_4 = database_4

    with Given(
        "I create the source and destination databases from a list",
        descriptopm=f"databases: {database_1, database_2, database_3, database_4}",
    ):
        create_databases(databases=[database_1, database_2, database_3, database_4])

    self.context.config_file = os.path.join("env", "auto", "configs")

    with And(
        "I create a new ClickHouse Sink Connector configuration with configuration to monitor all of the databases"
    ):
        remove_sink_configuration(
            key="database.include.list",
            config_file=os.path.join(
                self.context.config_file, "multiple_databases.yml"
            ),
        )

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

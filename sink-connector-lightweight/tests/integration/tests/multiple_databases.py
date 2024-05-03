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
def set_list_of_databases_to_replicate(self, databases=None, config_name=None):
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
    return f'{generate_sample_mysql_value("INT")},{generate_sample_mysql_value("VARCHAR")},{generate_sample_mysql_value("INT")}'


@TestOutline
def create_table_and_insert_values(
    self, table_name, database_name=None, exists=None, should_replicate=True
):
    """Create a sample table in MySQL and validate that it was replicated in ClickHouse."""

    if exists is None:
        exists = "1"

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

    if not should_replicate:
        table_values = ""

    with And("validating that the table was replicated in ClickHouse"):
        for retry in retries(timeout=40):
            with retry:
                check_if_table_was_created(
                    table_name=table_name, database_name=database_name, message=exists
                )
        if should_replicate:
            for retry in retries(timeout=10, delay=1):
                with retry:
                    validate_data_in_clickhouse_table(
                        table_name=table_name,
                        expected_output=table_values.replace("'", ""),
                        database_name=database_name,
                        statement="id, col1, col2",
                    )


@TestStep(Given)
def create_source_and_destination_databases(self, database_name=None):
    """Create MySQL and ClickHouse databases."""
    if database_name is None:
        databases = "test"

    with By(f"creating a ClickHouse database {database_name}"):
        create_clickhouse_database(name=database_name)

    with And(f"creating a a MySQL database {database_name}"):
        create_mysql_database(database_name=database_name)


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
def insert_on_database1(self, table_name):
    """Create a table on the database_1."""
    create_table_and_insert_values(
        table_name=table_name, database_name=self.context.database_1
    )


@TestStep(Given)
def insert_on_database2(self, table_name):
    """Create a table on the database_2."""
    create_table_and_insert_values(
        table_name=table_name, database_name=self.context.database_2
    )


@TestStep(Given)
def insert_on_database3(self, table_name):
    """Create a table on the database_3."""
    create_table_and_insert_values(
        table_name=table_name, database_name=self.context.database_3
    )


@TestStep(Given)
def insert_on_database4(self, table_name):
    """Create a table on the database_4."""
    create_table_and_insert_values(
        table_name=table_name, database_name=self.context.database_4
    )


@TestStep(Given)
def insert_on_two_databases(self):
    """Check that inserts are replicated when done on two databases."""
    table_name1 = "db1_" + getuid()
    table_name2 = "db2_" + getuid()
    with By("crating two table on two different databases"):
        insert_on_database1(table_name=table_name1)
        insert_on_database2(table_name=table_name2)


@TestStep(Given)
def insert_on_all_databases(self):
    """Create and insert values on all databases."""
    table_name1 = "db1_" + getuid()
    table_name2 = "db2_" + getuid()
    table_name3 = "db3_" + getuid()
    table_name4 = "db4_" + getuid()

    create_table_and_insert_values(
        table_name=table_name1, database_name=self.context.database_1
    )
    create_table_and_insert_values(
        table_name=table_name2, database_name=self.context.database_2
    )
    create_table_and_insert_values(
        table_name=table_name3, database_name=self.context.database_3
    )
    create_table_and_insert_values(
        table_name=table_name4, database_name=self.context.database_4
    )


@TestStep(Given)
def insert_on_all_databases_except_database_4(self):
    """Create and insert values on all databases except database_4."""
    table_name1 = "db1_" + getuid()
    table_name2 = "db2_" + getuid()
    table_name3 = "db3_" + getuid()
    table_name4 = "db4_" + getuid()

    with By(
        "creating three tables on three different databases and inserting values in them"
    ):
        create_table_and_insert_values(
            table_name=table_name1, database_name=self.context.database_1
        )
        create_table_and_insert_values(
            table_name=table_name2, database_name=self.context.database_2
        )
        create_table_and_insert_values(
            table_name=table_name3, database_name=self.context.database_3
        )
    with And(
        "creating table on database_4 and inserting values into it and checking that the values are not replicated to ClickHouse"
    ):
        create_table_and_insert_values(
            table_name=table_name4,
            database_name=self.context.database_4,
            exists=0,
            should_replicate=False,
        )


@TestStep(Given)
def insert_with_specific_database_config(self, databases=None):
    """Check that the data is correctly replicated when inserting with database.include.list parameter specified in
    ClickHouse Sink Connector configuration."""
    if databases is None:
        databases = f"{self.context.database_1},{self.context.database_2},{self.context.database_3}"
    with By(
        "setting specifying database.include.list configuration to include specific databases"
    ):
        set_list_of_databases_to_replicate(databases=databases)

    with And("creating and inserting to all databases in the list"):
        insert_on_all_databases_except_database_4()


@TestCheck
def check_replication_on_multiple_databases(self, action):
    """Check that the tables are correctly replicated on different number of databases."""

    with Given(f"I perform {action.__name__}"):
        action()


@TestSketch
def inserts(self):
    """Check that the inserts are correctly replicated on different number of databases."""

    actions = {
        insert_on_two_databases,
        insert_on_all_databases,
        insert_with_specific_database_config,
    }

    check_replication_on_multiple_databases(
        action=either(*actions),
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

    self.context.list_of_databases = [database_1, database_2, database_3, database_4]

    with Given(
        "I create the source and destination databases from a list",
        description=f"databases: {database_1, database_2, database_3, database_4}",
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

    Scenario(run=inserts)

from integration.helpers.common import change_sink_configuration
from integration.tests.steps.clickhouse import (
    check_if_table_was_created,
    create_clickhouse_database,
    validate_data_in_clickhouse_table,
    check_column,
)
from integration.tests.steps.mysql import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.alter import (
    add_column,
    rename_column,
    change_column,
    modify_column,
    drop_column,
    add_primary_key,
)


@TestStep(Given)
def replicate_all_databases(self):
    """Set ClickHouse Sink Connector configuration to replicate all databases."""
    with By(
        "creating a ClickHouse Sink Connector configuration without database.include.list values specified"
    ):
        remove_sink_configuration(
            key="database.include.list",
            config_file=os.path.join(
                self.context.config_file, "multiple_databases.yml"
            ),
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
def set_list_of_databases_to_replicate(self, databases=None, config_name=None):
    """Specify the list of databases to replicate in the ClickHouse Sink Connector configuration."""
    if databases is None:
        databases = "test"

    config_file = self.context.config_file

    if config_name is None:
        config_name = os.path.join(config_file, "selected_databases.yml")
    else:
        config_name = os.path.join(config_file, config_name)
    try:
        with By(
            "setting the list of databases to replicate in the ClickHouse Sink Connector configuration"
        ):
            change_sink_configuration(
                values={"database.include.list": databases}, config_file=config_name
            )
        yield
    finally:
        with Finally(
            "removing the list of databases from the ClickHouse Sink Connector configuration"
        ):
            replicate_all_databases()


def create_sample_values():
    """Create sample values to insert into the table."""
    return f'{generate_sample_mysql_value("INT")},{generate_sample_mysql_value("VARCHAR")},{generate_sample_mysql_value("INT")}'


@TestOutline
def create_table_and_insert_values(
    self, table_name, database_name=None, exists=None, validate_values=True
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

    if not validate_values:
        table_values = ""

    with And("validating that the table was replicated in ClickHouse"):
        for retry in retries(timeout=40):
            with retry:
                check_if_table_was_created(
                    table_name=table_name, database_name=database_name, message=exists
                )
        if validate_values:
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
        database_name = "test"

    with By(f"creating a ClickHouse database {database_name}"):
        create_clickhouse_database(name=database_name)

    with And(f"creating a a MySQL database {database_name}"):
        create_mysql_database(database_name=database_name)


@TestStep(Given)
def create_databases(self, databases=None):
    """Create MySQL and ClickHouse databases from a list of database names."""
    if databases is None:
        databases = []

    with Pool(4) as pool:
        for database_name in databases:
            Scenario(
                test=create_source_and_destination_databases,
                parallel=True,
                executor=pool,
            )(database_name=database_name)
        join()


@TestOutline
def create_tables_on_multiple_databases(self, databases=None, validate_values=True):
    """Create tables on multiple databases."""
    if databases is None:
        databases = []

    with By("creating databases from a list"):
        create_databases(databases=databases)

    with And("creating tables with data on multiple databases"):
        with Pool(4) as pool:
            for database_name in databases:
                Scenario(
                    test=create_table_and_insert_values,
                    parallel=True,
                    executor=pool,
                )(
                    table_name=f"table_{getuid()}",
                    database_name=database_name,
                    validate_values=validate_values,
                )
            join()


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


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases("1.0")
)
def insert_on_two_databases(self):
    """Check that inserts are replicated when done on two databases."""
    table_name1 = "db1_" + getuid()
    table_name2 = "db2_" + getuid()
    with By("crating two table on two different databases"):
        insert_on_database1(table_name=table_name1)
        insert_on_database2(table_name=table_name2)


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases("1.0")
)
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
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_SourceMultipleDestinationOne(
        "1.0"
    )
)
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
            validate_values=False,
        )


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_IncludeList(
        "1.0"
    )
)
def insert_with_specific_database_config(self, databases=None):
    """Check that the data is correctly replicated when inserting with database.include.list parameter specified in
    ClickHouse Sink Connector configuration."""
    if databases is None:
        databases = f"{self.context.database_1},{self.context.database_2},{self.context.database_3}"
    with Given(
        "I set the list of databases to replicate in the ClickHouse Sink Connector configuration"
    ):
        set_list_of_databases_to_replicate(databases=databases)

    with And("I insert values on all databases except database_4"):
        insert_on_all_databases_except_database_4()


@TestScenario
def check_replication_on_number_of_databases(self):
    """Check that the tables are replicated when we have a specific number of databases both on source and destination."""
    number_of_databases = self.context.number_of_databases
    databases = [f"db_{getuid()}" for _ in range(1, number_of_databases + 1)]

    with Given(
        "I tables on multiple databases and validate that they were replicated in ClickHouse"
    ):
        create_tables_on_multiple_databases(databases=databases)


@TestScenario
def add_column_on_a_database(self, database):
    """Check that the column is added on the table when we add a column on a database."""
    table_name = f"table_{getuid()}"
    column = "new_col"

    with Given("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I add a column on the table"):
        add_column(table_name=table_name, database=database, column_name=column)

    with Then("I check that the column was added on the table"):
        check_column(table_name=table_name, database=database, column_name=column)


@TestScenario
def rename_column_on_a_database(self, database):
    """Check that the column is renamed on the table when we rename a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column = "new_col_renamed"

    with Given("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I rename a column on the table"):
        rename_column(
            table_name=table_name,
            database=database,
            column_name=column,
            new_column_name=new_column,
        )

    with Then("I check that the column was renamed on the table"):
        check_column(table_name=table_name, database=database, column_name=new_column)


@TestScenario
def change_column_on_a_database(self, database):
    """Check that the column is changed on the table when we change a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column = "new_col"
    new_column_type = "int"

    with Given("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I change a column on the table"):
        change_column(
            table_name=table_name,
            database=database,
            column_name=column,
            new_column_name=new_column,
            new_column_type=new_column_type,
        )

    with Then("I check that the column was changed on the table"):
        check_column(table_name=table_name, database=database, column_name=new_column)


@TestScenario
def modify_column_on_a_database(self, database):
    """Check that the column is modified on the table when we modify a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column_type = "int"

    with Given("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

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
def drop_column_on_a_database(self, database):
    """Check that the column is dropped from the table when we drop a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"

    with Given("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I drop a column on the table"):
        drop_column(table_name=table_name, database=database, column_name=column)

    with Then("I check that the column was dropped from the table"):
        check_column(table_name=table_name, database=database, column_name="")


@TestCheck
def check_alters(self, alter_1, alter_2, database_1, database_2):
    """Run multiple alter statements on different databases."""

    with Given(
        "I run multiple alter statements on different databases",
        description=f"alter_1: {alter_1.__name__}, alter_2: {alter_2.__name__}, database_1: {database_1}, database_2: {database_2}",
    ):
        alter_1(database=database_1)
        alter_2(database=database_2)


@TestSketch(Scenario)
def check_alters_on_different_databases(self):
    """Check that the tables are replicated when we alter them on different databases."""
    databases = self.context.list_of_databases

    alter_statements = [
        add_column_on_a_database,
        rename_column_on_a_database,
        change_column_on_a_database,
        modify_column_on_a_database,
        drop_column_on_a_database,
    ]

    check_alters(
        alter_1=either(*alter_statements),
        alter_2=either(*alter_statements),
        database_1=either(*databases),
        database_2=either(*databases),
    )


@TestSuite
def inserts(self):
    """Check that the inserts are correctly replicated on different number of databases.

    Combinations:
        - Check replication when there are more than 1 database on source and destination.
        - Check replication when there are 4 databases on source and destination, and we insert values on all databases.
        - Check replication when we insert values on all databases except database_4, and we have database.include.list parameter specified in ClickHouse Sink Connector configuration.
        - Check replication when we have a specific number of databases both on source and destination.
    """
    Scenario(run=insert_on_two_databases)
    Scenario(run=insert_on_all_databases),
    Scenario(run=insert_with_specific_database_config)
    Scenario(run=check_replication_on_number_of_databases)


@TestSuite
def alters(self):
    """Check that the tables are replicated when we alter them on different databases.

    Combinations:
        - Alter statements on different databases.
        - Alter statements on the same database.
        - Add column on multiple databases.
        - Rename column on multiple databases.
        - Change column on multiple databases.
        - Modify column on multiple databases.
        - Drop column on multiple databases.
    """

    Scenario(run=check_alters_on_different_databases)


@TestFeature
@Name("multiple databases")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
    number_of_databases=10,
    parallel_cases=1,
    database_1="database_1",
    database_2="database_2",
    database_3="database_3",
    database_4="database_4",
):
    """
    Check that auto table replication works when there are multiple databases in MySQL.
    """

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    self.context.database_1 = database_1
    self.context.database_2 = database_2
    self.context.database_3 = database_3
    self.context.database_4 = database_4

    self.context.list_of_databases = [database_1, database_2, database_3, database_4]
    self.context.number_of_databases = number_of_databases

    with Given(
        "I create the source and destination databases from a list",
        description=f"databases: {database_1, database_2, database_3, database_4}",
    ):
        create_databases(databases=[database_1, database_2, database_3, database_4])

    self.context.config_file = os.path.join("env", "auto", "configs")

    with And(
        "I create a new ClickHouse Sink Connector configuration with configuration to monitor all of the databases"
    ):
        replicate_all_databases()

    with Pool(parallel_cases) as executor:
        Feature(run=inserts, parallel=True, executor=executor)
        Feature(run=alters, parallel=True, executor=executor)
        join()

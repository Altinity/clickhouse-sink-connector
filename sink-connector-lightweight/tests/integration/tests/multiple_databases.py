from integration.helpers.common import change_sink_configuration
from integration.helpers.create_config import include_all_databases_with_rrmt
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
    drop_primary_key,
)


def get_n_random_items(lst, n):
    """Get n random items from the list of databases."""
    if n >= len(lst):
        return lst
    else:
        return random.sample(lst, n)


@TestOutline
def remove_configuration_and_restart_sink(
    self, configuration, configuration_name="multiple_databases.yml"
):
    """Remove the ClickHouse Sink Connector configuration and restart the sink connector."""
    with By(
        f"creating a ClickHouse Sink Connector configuration without {configuration} values specified"
    ):
        remove_sink_configuration(
            key=configuration,
            config_file=os.path.join(self.context.config_file, configuration_name),
        )


@TestStep(Given)
def replicate_all_databases(self):
    """Set ClickHouse Sink Connector configuration to replicate all databases."""

    remove_configuration_and_restart_sink(configuration="database.include.list")


@TestStep(Given)
def remove_database_map(self):
    """Remove the database map from the ClickHouse Sink Connector configuration."""
    remove_configuration_and_restart_sink(
        configuration="clickhouse.database.override.map"
    )


@TestStep(Given)
def replicate_all_databases_rrmt(self):
    """Set ClickHouse Sink Connector configuration to replicate all databases when tables have ReplicatedReplacingMergeTree engine."""
    with By(
        "creating a ClickHouse Sink Connector configuration without database.include.list values specified"
    ):
        include_all_databases_with_rrmt(
            config_file=os.path.join(
                self.context.config_file, "multiple_databases_rrmt.yml"
            )
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


@TestStep(Given)
def create_map_for_database_names(self, databases_map: dict = None, config_name=None):
    """Create a map for the database names.
    Example:
        clickhouse.database.override.map: "employees:employees2, products:productsnew
    """
    config_file = self.context.config_file

    new_database_map = []

    for key, value in databases_map.items():
        key = key.strip(r"\`")
        value = value.strip(r"\`")
        new_database_map.append(rf"{key}:{value}")

    configuration_value = ", ".join(new_database_map)

    if config_name is None:
        config_name = os.path.join(config_file, "override_multiple_database_names.yml")
    else:
        config_name = os.path.join(config_file, config_name)
    try:
        with By(
            "setting the list of databases to replicate in the ClickHouse Sink Connector configuration",
            description=f"MySQL to ClickHouse database map: {configuration_value}",
        ):
            change_sink_configuration(
                values={"clickhouse.database.override.map": f"{configuration_value}"},
                config_file=config_name,
            )
        yield
    finally:
        with Finally(
            "removing the list of databases from the ClickHouse Sink Connector configuration"
        ):
            remove_database_map()


@TestOutline
def create_table_mapped(
    self,
    source_database=None,
    destination_database=None,
    exists=None,
    validate_values=True,
):
    """Create a sample table in MySQL and validate that it was replicated in ClickHouse."""

    table_name = f"table_{getuid()}"

    if exists is None:
        exists = "1"

    if source_database is None:
        source_database = "test"

    if destination_database is None:
        destination_database = "test"

    table_values = create_sample_values()

    with By("creating a sample table in MySQL"):
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"col1 varchar(255), col2 int",
            database_name=source_database,
        )

    with And("inserting data into the table"):
        insert(
            table_name=table_name, database_name=source_database, values=table_values
        )

    if not validate_values:
        table_values = ""

    with And("validating that the table was replicated in ClickHouse"):
        for retry in retries(timeout=40):
            with retry:
                check_if_table_was_created(
                    table_name=table_name,
                    database_name=destination_database,
                    message=exists,
                )
        if validate_values:
            for retry in retries(timeout=10, delay=1):
                with retry:
                    validate_data_in_clickhouse_table(
                        table_name=table_name,
                        expected_output=table_values.replace("'", ""),
                        database_name=destination_database,
                        statement="id, col1, col2",
                    )


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
        create_mysql_table(
            table_name=rf"\`{table_name}\`",
            columns=f"col1 varchar(255), col2 int",
            database_name=database_name,
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
def create_diff_name_dbs(self, databases):
    """Create MySQL and ClickHouse databases with different names."""

    with Pool(2) as pool:
        for source, destination in databases.items():
            Step(test=create_mysql_database, parallel=True, executor=pool)(
                database_name=source
            )
            Step(test=create_clickhouse_database, parallel=True, executor=pool)(
                name=destination
            )

        join()


@TestStep(Given)
def create_databases(self, databases: list = None):
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
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Add("1.0")
)
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
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Rename("1.0")
)
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
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Change_Multiple(
        "1.0"
    )
)
def change_column_on_a_database(self, database):
    """Check that the column is changed on the table when we change a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column = "new_col"
    new_column_type = "varchar(255)"

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
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Modify("1.0")
)
def modify_column_on_a_database(self, database):
    """Check that the column is modified on the table when we modify a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column_type = "varchar(255)"

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
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Alter_Columns_Drop("1.0")
)
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


@TestScenario
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_PrimaryKey_Simple("1.0")
)
def add_primary_key_on_a_database(self, database):
    """Check that the primary key is added to the table when we add a primary key on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"

    with Given("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I add a primary key on the table"):
        drop_primary_key(table_name=table_name, database=database)
        add_primary_key(table_name=table_name, database=database, column_name=column)

    with Then("I check that the primary key was added to the table"):
        check_column(table_name=table_name, database=database, column_name=column)


@TestOutline
def check_different_database_names(self, database_map):
    """Check that the tables are replicated when we have source and destination databases with different names."""

    with Given("I create the source and destination databases from a list"):
        create_diff_name_dbs(databases=database_map)

    with When("I set the new map between source and destination database names"):
        create_map_for_database_names(databases_map=database_map)

    with Then("I create table on each database and validate data"):
        for source_database, destination_database in database_map.items():
            create_table_mapped(
                source_database=source_database,
                destination_database=destination_database,
            )


@TestScenario
def different_database_names(self):
    """Check that the tables are replicated when we have source and destination databases with different names."""
    database_map = {"mysql1": "ch1", "mysql2": "ch2", "mysql3": "ch3", "mysql4": "ch4"}
    check_different_database_names(database_map=database_map)


@TestScenario
def different_database_names_with_source_backticks(self):
    """Check that the tables are replicated when we have source and destination databases with different names and source database name contains backticks."""
    database_map = {
        "\`mysql1\`": "ch1",
        "\`mysql2\`": "ch2",
        "\`mysql3\`": "ch3",
        "\`mysql4\`": "ch4",
    }
    check_different_database_names(database_map=database_map)


@TestScenario
def different_database_names_with_destination_backticks(self):
    """Check that the tables are replicated when we have source and destination databases with different names and destination database name contains backticks."""
    database_map = {
        "mysql1": "\`ch1\`",
        "mysql2": "\`ch2\`",
        "mysql3": "\`ch3\`",
        "mysql4": "\`ch4\`",
    }
    check_different_database_names(database_map=database_map)


@TestScenario
def different_database_names_with_backticks(self):
    """Check that the tables are replicated when we have source and destination databases with the same names and they contain backticks."""
    database_map = {
        "\`mysql1\`": "\`ch1\`",
        "\`mysql2\`": "\`ch2\`",
        "\`mysql3\`": "\`ch3\`",
        "\`mysql4\`": "\`ch4\`",
    }
    check_different_database_names(database_map=database_map)


@TestScenario
def same_database_names(self):
    """Check that the tables are replicated when we have source and destination databases with the same names."""
    database_map = {
        "mysql1": "mysql1",
        "mysql2": "mysql2",
        "mysql3": "mysql3",
        "mysql4": "mysql4",
    }
    check_different_database_names(database_map=database_map)


@TestCheck
def check_alters(self, alter_1, alter_2, database_1, database_2):
    """Run multiple alter statements on different databases."""

    with Given(
        "I run multiple alter statements on different databases",
        description=f"""
        alter_1: {alter_1.__name__}, 
        alter_2: {alter_2.__name__}, 
        databases: {database_1}, {database_2}
        """,
    ):
        with Pool(2) as executor:
            Check(test=alter_1, parallel=True, executor=executor)(database=database_1)
            Check(test=alter_2, parallel=True, executor=executor)(database=database_2)
            join()


@TestStep(When)
def check_concurrent_actions(
    self,
    actions,
    number_of_concurrent_actions=None,
    number_of_iterations=None,
    databases=None,
):
    """Concurrently perform different actions on multiple source databases that are randomly picked from list of created databases."""

    if databases is None:
        databases = self.context.list_of_databases

    if number_of_concurrent_actions is None:
        number_of_concurrent_actions = self.context.number_of_concurrent_actions

    if number_of_iterations is None:
        number_of_iterations = self.context.number_of_iterations

    with By("running concurrent actions on multiple databases"):
        for i in range(number_of_iterations):
            for action in get_n_random_items(actions, number_of_concurrent_actions):
                if action.__name__ in [
                    "insert_on_all_databases",
                    "insert_on_two_databases",
                ]:
                    Check(name=f"{action} #{i}", test=action, parallel=True)()
                else:
                    Check(name=f"{action} #{i}", test=action, parallel=False)(
                        database=random.choice(databases)
                    )


@TestSketch(Scenario)
def check_alters_on_different_databases(self):
    """Check that the tables are replicated when we alter them on different databases.

    Combinations:
        - ADD COLUMN
        - RENAME COLUMN
        - CHANGE COLUMN
        - MODIFY COLUMN
        - DROP COLUMN
        - ADD PRIMARY KEY
    """

    alter_statements = [
        add_column_on_a_database,
        rename_column_on_a_database,
        change_column_on_a_database,
        modify_column_on_a_database,
        drop_column_on_a_database,
        add_primary_key_on_a_database,
    ]

    check_alters(
        alter_1=either(*alter_statements),
        alter_2=either(*alter_statements),
        database_1="database_1",
        database_2="database_2",
    )


@TestSuite
def inserts(self):
    """Check that the inserts are correctly replicated on different number of databases.

    Combinations:
        - Check replication when there are more than 1 database on source and destination.
        - Check replication when there are 4 databases on source and destination, and we insert values on all databases.
        - Check replication when we insert values on all databases except database_4, and we have database.include.list
          parameter specified in ClickHouse Sink Connector configuration.
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


@TestSuite
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConcurrentActions(
        "1.0"
    )
)
def concurrent_actions(self, number_of_iterations=None):
    """Check that the tables are replicated when we have concurrent actions on different databases.

    Actions:
        - ALTERs
        - INSERTs
        - SELECTs
    """

    actions = [
        add_column_on_a_database,
        rename_column_on_a_database,
        change_column_on_a_database,
        modify_column_on_a_database,
        drop_column_on_a_database,
        insert_on_two_databases,
        insert_on_all_databases,
    ]

    check_concurrent_actions(actions=actions)


@TestSuite
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_OverrideMap(
        "1.0"
    ),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MultipleDatabases_ConfigValues_OverrideMap_MultipleValues(
        "1.0"
    ),
)
def source_destination_overrides(self):
    """Check that the tables are replicated when we have source and destination databases with different names.
    For example,
        mysql1:ch1; mysql2:ch2
    """
    Scenario(run=different_database_names)
    Scenario(run=different_database_names_with_source_backticks)
    Scenario(run=different_database_names_with_destination_backticks)
    Scenario(run=different_database_names_with_backticks)
    Scenario(run=same_database_names)


@TestFeature
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
    number_of_databases=10,
    parallel_cases=2,
    number_of_concurrent_actions=5,
    number_of_iterations=10,
):
    """
    Check that auto table replication works when there are multiple databases in MySQL.
    """

    engine = self.context.clickhouse_table_engine

    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    self.context.database_1 = database_1
    self.context.database_2 = database_2
    self.context.database_3 = database_3
    self.context.database_4 = database_4

    self.context.list_of_databases = [database_1, database_2, database_3, database_4]
    self.context.number_of_databases = number_of_databases
    self.context.number_of_concurrent_actions = number_of_concurrent_actions
    self.context.number_of_iterations = number_of_iterations

    with Given(
        "I create the source and destination databases from a list",
        description=f"databases: {self.context.list_of_databases}",
    ):
        create_databases(databases=self.context.list_of_databases)

    if engine == "ReplacingMergeTree":
        self.context.config_file = os.path.join("env", "auto", "configs")
    elif engine == "ReplicatedReplacingMergeTree":
        self.context.config_file = os.path.join("env", "auto_replicated", "configs")

    with And(
        "I create a new ClickHouse Sink Connector configuration to monitor all of the databases"
    ):
        if engine == "ReplacingMergeTree":
            replicate_all_databases()
        elif engine == "ReplicatedReplacingMergeTree":
            replicate_all_databases_rrmt()

    Feature(run=inserts)
    Feature(run=alters)
    Feature(run=concurrent_actions)
    Feature(run=source_destination_overrides)

from integration.tests.steps.sql import *
from integration.tests.steps.alter import *
from integration.tests.steps.service_settings_steps import *
from integration.tests.steps.steps_global import create_database
from integration.tests.steps.clickhouse import (
    validate_data_in_clickhouse_table,
    check_if_table_was_created,
    check_column,
)


@TestOutline
def create_table_and_insert_values(
    self, table_name, database_name=None, exists=None, validate_values=True
):
    mysql_columns = "col1 varchar(255), col2 int"

    if exists is None:
        exists = "1"

    if database_name is None:
        database_name = "test"

    table_values = "1,'test',1"

    with Given("I create MySQL to ClickHouse replicated table"):
        create_mysql_to_clickhouse_replicated_table(
            name=table_name, mysql_columns=mysql_columns, database=database_name
        )

    with When("I insert data in MySQL table"):
        insert_values(
            table_name=table_name, values=table_values, database=database_name
        )

    with And("I check if the table was created"):
        check_if_table_was_created(
            table_name=table_name, database_name=database_name, message=exists
        )

    if not validate_values:
        table_values = ""

    if validate_values:
        with Then("I check that the table was replicated"):
            for retry in retries(timeout=40, delay=1):
                with retry:
                    validate_data_in_clickhouse_table(
                        table_name=table_name,
                        expected_output=table_values.replace("'", ""),
                        database_name=database_name,
                        statement="id, col1, col2",
                    )


@TestStep(Given)
def create_source_database(self, database_name):
    """Create a MySQL database."""
    create_mysql_database(database_name=database_name)


@TestStep(Given)
def create_destination_database(self, database_name):
    """Create a ClickHouse database."""
    create_database(name=database_name)


@TestStep(Given)
def create_source_and_destination_databases(self, database_name=None):
    """Create MySQL and ClickHouse databases."""
    if database_name is None:
        database_name = "test"

    with By(f"creating a a MySQL database {database_name}"):
        create_mysql_database(database_name=database_name)

    with And(f"creating a ClickHouse database {database_name}"):
        create_database(name=database_name)


@TestStep(Given)
def create_databases(self, databases=None):
    """Create MySQL and ClickHouse databases from a list of database names."""

    if databases is None:
        databases = []

    with By("executing the create database from a list"):
        for database_name in databases:
            create_source_and_destination_databases(database_name=database_name)


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
def insert_on_two_databases(self):
    """Check that inserts are replicated when done on two databases."""
    table_name1 = "db1_" + getuid()
    table_name2 = "db2_" + getuid()

    databases = ["database_1", "database_2"]

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(
            topics=f"SERVER5432.{databases[0]}.{table_name1},SERVER5432.{databases[1]}.{table_name2}"
        )

    with And("crating two table on two different databases"):
        insert_on_database1(table_name=table_name1)
        insert_on_database2(table_name=table_name2)


@TestScenario
def insert_on_all_databases(self):
    """Create and insert values on all databases."""
    table_name1 = "db1_" + getuid()
    table_name2 = "db2_" + getuid()
    table_name3 = "db3_" + getuid()
    table_name4 = "db4_" + getuid()

    databases = ["database_1", "database_2", "database_3", "database_4"]

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(
            topics=f"SERVER5432.{databases[0]}.{table_name1},SERVER5432.{databases[1]}.{table_name2}, SERVER5432.{databases[2]}.{table_name3}, SERVER5432.{databases[3]}.{table_name4}"
        )

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
            validate_values=False,
        )


@TestScenario
def add_column_on_a_database(self, database):
    """Check that the column is added on the table when we add a column on a database."""
    table_name = f"table_{getuid()}"
    column = "new_col"

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(topics=f"SERVER5432.{database}.{table_name}")

    with And("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I add a column on the table"):
        add_column(table_name=table_name, database=database, column_name=column)

    with And("I insert values in the new column"):
        insert_values(
            table_name=table_name,
            values="'test_name'",
            database=database,
            columns=f"({column})",
        )

    with Then("I check that the column was added on the table"):
        check_column(table_name=table_name, database=database, column_name=column)


@TestScenario
def rename_column_on_a_database(self, database):
    """Check that the column is renamed on the table when we rename a column on a database."""
    table_name = f"table_{getuid()}"
    column = "col1"
    new_column = "new_col_renamed"

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(topics=f"SERVER5432.{database}.{table_name}")

    with And("I create a table on multiple databases"):
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
    new_column_type = "varchar(255)"

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(topics=f"SERVER5432.{database}.{table_name}")

    with And("I create a table on multiple databases"):
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
    new_column_type = "varchar(255)"

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(topics=f"SERVER5432.{database}.{table_name}")

    with And("I create a table on multiple databases"):
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

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(topics=f"SERVER5432.{database}.{table_name}")

    with And("I create a table on multiple databases"):
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

    with Given(f"creating connection between kafka and sink connector"):
        init_sink_connector(topics=f"SERVER5432.{database}.{table_name}")

    with And("I create a table on multiple databases"):
        create_table_and_insert_values(table_name=table_name, database_name=database)

    with When("I add a primary key on the table"):
        add_primary_key(table_name=table_name, database=database, column_name=column)

    with Then("I check that the primary key was added to the table"):
        check_column(table_name=table_name, database=database, column_name=column)


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
    Scenario(run=insert_on_all_databases)


@TestSuite
def alters(self):
    """Check that the tables are replicated when we alter them on different databases.

    Combinations:
        - ADD COLUMN
        - RENAME COLUMN
        - CHANGE COLUMN
        - MODIFY COLUMN
        - DROP COLUMN
        - ADD PRIMARY KEY
    """
    databases = self.context.list_of_databases

    alter_statements = [
        add_column_on_a_database,
        rename_column_on_a_database,
        change_column_on_a_database,
        modify_column_on_a_database,
        drop_column_on_a_database,
        add_primary_key_on_a_database,
    ]

    for database in databases:
        for alter_statement in alter_statements:
            Scenario(test=alter_statement)(database=database)


@TestFeature
@Name("multiple databases")
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
    database_1="database_1",
    database_2="database_2",
    database_3="database_3",
    database_4="database_4",
    number_of_databases=10,
    parallel_cases=1,
    number_of_concurrent_actions=5,
    number_of_iterations=10,
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
    self.context.number_of_concurrent_actions = number_of_concurrent_actions
    self.context.number_of_iterations = number_of_iterations

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    with And(
        "I create the source and destination databases from a list",
        description=f"databases: {self.context.list_of_databases}",
    ):
        create_databases(databases=self.context.list_of_databases)

    Feature(run=inserts)
    Feature(run=alters)

    # with Pool(parallel_cases) as executor:
    #     Feature(run=inserts, parallel=True, executor=executor)
    #     # Feature(run=alters, parallel=True, executor=executor)
    #     # Feature(run=concurrent_actions, parallel=True, executor=executor)
    #     join()

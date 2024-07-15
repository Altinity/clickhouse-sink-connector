from integration.tests.steps.mysql import *
from integration.tests.steps.datatypes import *
from integration.tests.steps.service_settings import *
from integration.tests.steps.clickhouse import *


@TestScenario
def databases_tables(
    self,
    version_column="_version",
    mysql_columns=" MyData DATETIME",
):
    """Check correctness of virtual column names."""
    database_1 = "test"
    database_2 = "test2"

    mysql = self.context.cluster.node("mysql-master")

    table_name = f"databases_{getuid()}"

    with Given(f"I create MySQL table {table_name})"):
        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
            database_name=database_1,
        )

    with And(
        f"I create another database in Clickhouse and table with the same name {table_name} in it"
    ):
        create_clickhouse_database(name=database_2)
        create_mysql_database(database_name=database_2)

        create_mysql_table(
            table_name=table_name,
            columns=mysql_columns,
            database_name=database_2,
        )

    with When(f"I insert data in MySQL table {table_name} on {database_1}"):
        mysql.query(
            f"INSERT INTO {database_1}.{table_name} VALUES (1, '2018-09-08 17:51:05.777')"
        )
        mysql.query(
            f"INSERT INTO {database_1}.{table_name} VALUES (2, '2018-09-08 17:51:05.777')"
        )
        mysql.query(
            f"INSERT INTO {database_1}.{table_name} VALUES (3, '2018-09-08 17:51:05.777')"
        )

    with And(f"I insert data in MySQL table {table_name} on {database_2}"):
        mysql.query(
            f"INSERT INTO {database_2}.{table_name} VALUES (1, '2018-09-08 17:51:05.777')"
        )
        mysql.query(
            f"INSERT INTO {database_2}.{table_name} VALUES (2, '2018-09-08 17:51:05.777')"
        )
        mysql.query(
            f"INSERT INTO {database_2}.{table_name} VALUES (3, '2018-09-08 17:51:05.777')"
        )

    with Then(f"I check that data is replicated to the correct table"):
        Check(test=check_if_table_was_created)(
            table_name=table_name, database_name=database_1
        )
        Check(test=check_if_table_was_created)(
            table_name=table_name, database_name=database_2
        )


@TestModule
@Name("databases")
def module(self):
    """Section to check behavior replication in different databases."""

    Scenario(run=databases_tables)

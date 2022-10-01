import time

from testflows.core import *
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *


@TestOutline
def mysql_to_clickhouse_insert(self):
    """`INSERT` check section"""
    xfail("`SELECT ... FINAL` eats rows")


    table_name = "users"
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} (col1 int4, col2 int4 NOT NULL, col3 int4 default 777)"
            f" ENGINE = InnoDB;",
        )
        clickhouse.query("SYSTEM STOP MERGES")

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} (col1,col2,col3) VALUES (DEFAULT,DEFAULT,DEFAULT);"
        )

        mysql.query(
            f"INSERT INTO {table_name} (col2,col3) VALUES (4,DEFAULT);"
        )

        mysql.query(
            f"INSERT INTO {table_name} (col1,col2,col3) VALUES (DEFAULT,5,DEFAULT);"
        )

        mysql.query(
            f"INSERT INTO {table_name} VALUES (DEFAULT,5,333);"
        )

        mysql.query(
            f"INSERT INTO {table_name} VALUES (DEFAULT,7);"
        )

    with Then(f"I wait unique values from CLickHouse table equal to MySQL table"):
        select(insert="\\N,4,777\n\\N,5,777\n\\N,5,333", table_name=table_name, statement="col1,col2,col3", with_final=True, timeout=50)


@TestScenario
def null_default_insert(self, auto_create_tables=True):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_insert()


@TestFeature
@Name("insert")
def feature(self):
    """Different `INSERT` tests section."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()
        init_sink_connector(auto_create_tables=True)

    for scenario in loads(current_module(), Scenario):
        scenario()

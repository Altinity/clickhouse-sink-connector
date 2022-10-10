import time

from testflows.core import *
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *


@TestOutline
def mysql_to_clickhouse_postgres_inserts(self, input, output):
    """`INSERT` check section"""
    # xfail("`SELECT ... FINAL` eats rows")

    table_name = "users"
    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} (id INT AUTO_INCREMENT,col1 int4, col2 int4 NOT NULL,"
                      f" col3 int4 default 777, PRIMARY KEY (id))"
            f" ENGINE = InnoDB;",
        )
        # clickhouse.query("SYSTEM STOP MERGES")

    with When("I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} (col1,col2,col3) VALUES {input};"
        )
        pause()

    with Then("I check data inserted correct"):
        mysql_rows_after_delete = mysql.query(f"select col1,col2,col3 from {table_name}").output.strip()[90:]
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                clickhouse.query(
                    f"SELECT col1,col2,col3 FROM test.{table_name} FINAL FORMAT CSV",
                    message=output
                )


@TestScenario
def null_default_insert(self):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_postgres_inserts(input="(DEFAULT,5,DEFAULT)", output="\\N,5,777")


@TestScenario
def null_default_insert_2(self):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_postgres_inserts(input="(DEFAULT,5,333)", output="\\N,5,333")


@TestScenario
def select_insert(self, auto_create_tables=True):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_postgres_inserts(input="((select 2),7,DEFAULT)", output="2,7,777")


@TestScenario
def select_insert(self, auto_create_tables=True):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_postgres_inserts(input="((select 2),7,DEFAULT)", output="2,7,777")


@TestScenario
def select_insert_2(self, auto_create_tables=True):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_postgres_inserts(input="((select 2),(select i from (values(3)) as foo (i)),DEFAULT)",
                                         output="2,3,777")

@TestScenario
def select_insert_3(self, auto_create_tables=True):
    """NULL and DEFAULT `INSERT` check."""
    mysql_to_clickhouse_postgres_inserts(input="(2,3,777)",
                                         output="2,3,777")




@TestFeature
@Name("insert")
def feature(self):
    """Different `INSERT` tests section."""

    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()

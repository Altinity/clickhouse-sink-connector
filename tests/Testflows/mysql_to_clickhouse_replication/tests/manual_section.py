import time

from testflows.core import *
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *
from testflows.connect import Shell
from helpers.common import *


@TestOutline
def mysql_to_clickhouse_connection(self, auto_create_tables):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""

    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
                      "(id int(11) NOT NULL AUTO_INCREMENT,"
                      "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL DEFAULT '',"
                      "pad char(60) NOT NULL DEFAULT '',PRIMARY KEY (id,k))"
                      " ENGINE= InnoDB"
                      " PARTITION BY RANGE (k)"
                      " (PARTITION p1 VALUES LESS THAN (499999),PARTITION p2 VALUES LESS THAN MAXVALUE);"

        )
        pause()

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');"
        )
        pause()

    if auto_create_tables:
        with And("I check table creation"):
            retry(clickhouse.query, timeout=30, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            pause()


    with And(f"I check that ClickHouse table has same number of rows as MySQL table"):
        pass


@TestOutline
def mysql_to_clickhouse_connection2(self, auto_create_tables):
    """Basic check MySQL to Clickhouse connection by small and simple data insert."""


    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
                      "(id int(11) NOT NULL AUTO_INCREMENT,"
                      "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL DEFAULT '',"
                      "pad char(60) NOT NULL DEFAULT '',PRIMARY KEY (id))"
                      " ENGINE= InnoDB;"
                      # " PARTITION BY RANGE (k)"
                      # " (PARTITION p1 VALUES LESS THAN (499999),PARTITION p2 VALUES LESS THAN MAXVALUE);"

        )
        pause()

    with When("Print clickhouse version"):
        print(self.context.clickhouse_version)

        if check_clickhouse_version("<22.3")(self):
            print("only supported on ClickHouse version >= 22.9")
            pause()
        else:
            print("upper then 22.3")
            pause()

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');"
        )
        pause()

    if auto_create_tables:
        with And("I check table creation"):
            retry(clickhouse.query, timeout=30, delay=3)(
                "SHOW TABLES FROM test", message=f"{table_name}"
            )
            pause()


    with And(f"I check that ClickHouse table has same number of rows as MySQL table"):
        pass


@TestScenario
def mysql_to_clickhouse_connection_ac(self, auto_create_tables=True):
    """Basic check MySQL to Clickhouse connection by small and simple data insert with auto table creation."""
    mysql_to_clickhouse_connection2(auto_create_tables=auto_create_tables)
    # with Given("I collect Sink logs"):
    #     with Shell() as bash:
    #         cmd = bash("docker-compose logs sink > sink.log")


@TestFeature
@Name("manual section")
def feature(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()

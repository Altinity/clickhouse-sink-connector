from testflows.core import *
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *
from testflows.connect import Shell
from helpers.common import *


@TestOutline
def update(self, primary_key, timeout=60):
    """`UPDATE` outline."""

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
                      "(id int(11) NOT NULL,"
                      "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL DEFAULT '',"
                      f"pad char(60) NOT NULL DEFAULT ''{primary_key})"
        )

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');"
        )
    with Then(f"I update data in MySql table"):
        mysql.query(
            f"UPDATE {table_name} SET k=k+5 WHERE id=1;"
        )

    with And("I check that ClickHouse table has same number of rows as MySQL table"):
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                clickhouse.query(
                    f"SELECT * FROM test.{table_name} FINAL where _sign !=-1 FORMAT CSV",
                    message='1,7,"a","b"'
                )


@TestScenario
def no_primary_key(self):
    """Check for `DELETE` with no primary key.
    """
    xfail("make delete")
    update(primary_key="")


@TestScenario
def simple_primary_key(self):
    """Check for `DELETE` with simple primary key.)
    """
    update(primary_key=", PRIMARY KEY (id)",)


@TestScenario
def complex_primary_key(self):
    """Check for `DELETE` with complex primary key.
    """
    update(primary_key=", PRIMARY KEY (id,k)")


@TestFeature
@Name("update")
def feature(self):
    """MySql to ClickHouse replication update tests to test `UPDATE` queries."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
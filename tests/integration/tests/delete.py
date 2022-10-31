from requirements import *
from tests.steps import *


@TestOutline
def delete(self, primary_key, engine=True):
    """Check `DELETE` query replicating from MySQl table to CH with different primary keys.
    """

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
                      f"pad char(60) NOT NULL DEFAULT ''{primary_key}){' ENGINE = InnoDB;' if engine else ''}"
        )

    with When(f"I insert data in MySql table"):
        mysql.query(
            f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');"
        )
    with Then(f"I delete data in MySql table"):
        mysql.query(
            f"DELETE FROM {table_name} WHERE id=1;"
        )

    with And("I check that ClickHouse table has same number of rows as MySQL table"):
        mysql_rows_after_delete = mysql.query(f"select count(*) from {table_name}").output.strip()[90:]
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                clickhouse.query(
                    f"SELECT count(*) FROM test.{table_name} FINAL where _sign !=-1 FORMAT CSV",
                    message=mysql_rows_after_delete
                )


@TestScenario
def no_primary_key(self):
    """Check for `DELETE` with no primary key with InnoDB engine.
    """
    xfail("doesn't work in row")
    delete(primary_key="", engine=True)


@TestScenario
def no_primary_key_innodb(self):
    """Check for `DELETE` with no primary key without InnoDB engine.
    """
    xfail("doesn't work in row")
    delete(primary_key="", engine=False)


@TestScenario
def simple_primary_key(self):
    """Check for `DELETE` with simple primary key without InnoDB engine.
    """
    delete(primary_key=", PRIMARY KEY (id)", engine=False)


@TestScenario
def simple_primary_key_innodb(self):
    """Check for `DELETE` with simple primary key with InnoDB engine.
    """
    delete(primary_key=", PRIMARY KEY (id)", engine=True)


@TestScenario
def complex_primary_key(self):
    """Check for `DELETE` with complex primary key without engine InnoDB.
    """
    delete(primary_key=", PRIMARY KEY (id,k)", engine=False)


@TestScenario
def complex_primary_key_innodb(self):
    """Check for `DELETE` with complex primary key with engine InnoDB.
    """
    delete(primary_key=", PRIMARY KEY (id,k)", engine=True)


@TestFeature
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Deletes("1.0"))
@Name("delete")
def feature(self):
    """MySql to ClickHouse replication delete tests to test `DELETE` queries."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()

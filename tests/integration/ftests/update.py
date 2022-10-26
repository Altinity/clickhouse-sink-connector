from requirements import *
from ftests.steps import *


@TestOutline
def update(self, primary_key, engine):
    """Check `UPDATE` query replicating from MySQl table to CH with different primary keys."""

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
    with Then(f"I update data in MySql table"):
        mysql.query(
            f"UPDATE {table_name} SET k=k+5 WHERE id=1;"
        )

    with And("I check that ClickHouse has updated data as MySQL"):
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                clickhouse.query(
                    f"SELECT * FROM test.{table_name} FINAL where _sign !=-1 FORMAT CSV",
                    message='1,7,"a","b"'
                )


@TestScenario
def no_primary_key(self):
    """Check for `UPDATE` with no primary key without table engine.
    """
    xfail("makes delete")
    update(primary_key="", engine=False)


@TestScenario
def no_primary_key_innodb(self):
    """Check for `UPDATE` with no primary key with table engine InnoDB.
    """
    xfail("makes delete")
    update(primary_key="", engine=True)


@TestScenario
def simple_primary_key(self):
    """Check for `UPDATE` with simple primary key without table engine.
    """
    update(primary_key=", PRIMARY KEY (id)", engine=False)


@TestScenario
def simple_primary_key_innodb(self):
    """Check for `UPDATE` with simple primary key with table engine InnoDB.
    """
    update(primary_key=", PRIMARY KEY (id)", engine=True)


@TestScenario
def complex_primary_key(self):
    """Check for `UPDATE` with complex primary key without table engine.
    """
    update(primary_key=", PRIMARY KEY (id,k)", engine=False)


@TestScenario
def complex_primary_key_innodb(self):
    """Check for `UPDATE` with complex primary key with table engine InnoDB.
    """
    update(primary_key=", PRIMARY KEY (id,k)", engine=True)


@TestFeature
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Queries_Updates("1.0"))
@Name("update")
def feature(self):
    """MySql to ClickHouse replication update tests to test `UPDATE` queries."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
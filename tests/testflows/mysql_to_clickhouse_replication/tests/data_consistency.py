import time

from testflows.core import *
from mysql_to_clickhouse_replication.requirements import *
from mysql_to_clickhouse_replication.tests.steps import *
from testflows.connect import Shell
from helpers.common import *
from itertools import combinations


@TestOutline
def unstable_network_connection(self, service):
    """Check for data consistency with unstable network connection to some service."""

    with Given("Receive UID"):
        time.sleep(25)
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} (col1 int4, col2 int4 NOT NULL, col3 int4 default 777)"
            f" ENGINE = InnoDB;",
        )

        with When("I insert data in MySql table with concurrent network fault"):
            with Shell() as bash:
                bash("docker network disconnect <NETWORK> <CONTAINER>", timeout=100)
            mysql.query(
                f"INSERT INTO {table_name} (col1,col2,col3) VALUES (5,6,777);"
            )

        with And("Enable network"):
            self.context.cluster.node(f"{service}").start()

        with Then("I wait unique values from CLickHouse table equal to MySQL table"):
            select(insert="5,6,777", table_name=table_name, statement="col1,col2,col3",
                   with_final=True, timeout=100)


@TestOutline
def restart(self, services, query=None):
    """Check for data consistency with concurrently service restart."""

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
                      f"pad char(60) NOT NULL DEFAULT '', PRIMARY KEY (id)) ENGINE = InnoDB;"
        )

    with When("I insert data in MySql table with concurrently service restart"):
        for node in services:
            self.context.cluster.node(f"{node}").stop()

        with Step(f"I insert data in MySql table"):
            mysql.query(
                f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');"
            )

        if query == "update":
            with Then(f"I update data in MySql table"):
                mysql.query(
                    f"UPDATE {table_name} SET k=k+5 WHERE id=1;"
                )
        elif query == "delete":
            with Then(f"I delete data in MySql table"):
                mysql.query(
                    f"DELETE FROM {table_name} WHERE id=1;"
                )

    with And(f"Enable all services {services}"):
        for node in services:
            self.context.cluster.node(f"{node}").start()

    if query == "update":
        with And("I check that ClickHouse has updated data as MySQL"):
            for attempt in retries(count=10, timeout=100, delay=5):
                with attempt:
                    clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                    clickhouse.query(
                        f"SELECT * FROM test.{table_name} FINAL where _sign !=-1 FORMAT CSV",
                        message='1,7,"a","b"'
                    )
    elif query == "delete":
        with And("I check that ClickHouse table has same number of rows as MySQL table"):
            mysql_rows_after_delete = mysql.query(f"select count(*) from {table_name}").output.strip()[90:]
            for attempt in retries(count=10, timeout=100, delay=5):
                with attempt:
                    clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                    clickhouse.query(
                        f"SELECT count(*) FROM test.{table_name} FINAL where _sign !=-1 FORMAT CSV",
                        message=mysql_rows_after_delete
                    )
    else:
        for attempt in retries(count=10, timeout=100, delay=5):
            with attempt:
                clickhouse.query(f"OPTIMIZE TABLE test.{table_name} FINAL DEDUPLICATE")

                clickhouse.query(
                    f"SELECT id,k,c,pad FROM test.{table_name} FINAL where _sign !=-1 FORMAT CSV",
                    message='1,2,"a","b"\n2,3,"a","b"'
                )


@TestScenario
def kafka_restart(self):
    """Kafka restart"""
    restart(services=["kafka"])


@TestScenario
def debezium_restart(self):
    """Debezium restart"""
    restart(services=["debezium"], query="update")


@TestScenario
def clickhouse_restart(self):
    """ClickHouse restart"""
    restart(services=["clickhouse"])


@TestScenario
def schemaregistry_restart(self):
    """Schemaregistry restart"""
    xfail("doesn't create table")
    restart(services=["schemaregistry"])


@TestScenario
def sink_restart(self):
    """Sink connector restart"""
    restart(services=["sink"])


@TestScenario
def combinatoric_restart(self):
    """Check all possibilities of unavailable services"""
    xfail("some timing problems")
    nodes_list = ["sink", "debezium", "schemaregistry", "kafka", "clickhouse"]
    with Given("Check for correct data replication with all possibilities of unavailable services"):
        for i in range(2, 6):
            service_combinations = list(combinations(nodes_list, i))
            for combination in service_combinations:
                restart(services=combination)


@TestOutline
def restart(self, services, loops=100):
    """Check for data consistency with concurrently service restart 100 times."""
    xfail("Doesn't finished")

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
                      f"pad char(60) NOT NULL DEFAULT '', PRIMARY KEY (id)) ENGINE = InnoDB;"
        )

    with When("I insert data in MySql table with concurrently service restart"):
        for node in services:
            self.context.cluster.node(f"{node}").stop()

        with Step(f"I insert data in MySql table"):
            mysql.query(
                f"INSERT INTO {table_name} values (1,2,'a','b'), (2,3,'a','b');"
            )
    for i in range(loops):
        with Given(f"LOOP STEP {i}"):
            When(
                "I insert data in MySql table",
                test=insert_step,
                parallel=True,
            )(
                table_name=table_name,
            )
            When(
                "I make service concurrently unavailable",
                test=service_unavailable,
                parallel=True,
            )(table_name=table_name_d)
            join()


@TestFeature
@Name("data consistency")
def feature(self):
    """Сheck data consistency when network or service faults are introduced."""
    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
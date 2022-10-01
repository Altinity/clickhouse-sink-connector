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
def restart(self, services):
    """Check for data consistency with concurrently service restart."""

    with Given("Receive UID"):
        uid = getuid()

    with And("I create unique table name"):
        table_name = f"test{uid}"

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        pause()
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} (col1 int4, col2 int4 NOT NULL, col3 int4 default 777)"
            f" ENGINE = InnoDB;",
        )

    with When("I insert data in MySql table with concurrently service restart"):
        for node in services:
            self.context.cluster.node(f"{node}").stop()
        pause("after stop")
        mysql.query(
            f"INSERT INTO {table_name} (col1,col2,col3) VALUES (5,6,777);"
        )

    with And("Enable all services"):
        for node in services:
            self.context.cluster.node(f"{node}").start()

    pause(print(services))

    with Then("I wait unique values from CLickHouse table equal to MySQL table"):
        retry(
            clickhouse.query,
            timeout=60,
            delay=10,
        )(f"SELECT col1,col2,col3 FROM test.{table_name} FINAL FORMAT CSV", message=f"5,6,777")


@TestScenario
def kafka_restart(self):
    """Kafka restart"""
    xfail("doesn't create table")
    restart(services=["kafka"])


@TestScenario
def debezium_restart(self):
    """Debezium restart"""
    restart(services=["debezium"])


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
    for i in range(2, 6):
        pairs = list(combinations(nodes_list, i))
        for pair in pairs:
            restart(services=pair)


@TestFeature
@Name("data consistency")
def feature(self):
    """Сheck data consistency when network or service faults are introduced."""
    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for scenario in loads(current_module(), Scenario):
        scenario()
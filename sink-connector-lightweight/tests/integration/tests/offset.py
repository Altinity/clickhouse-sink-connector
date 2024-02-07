import time
from itertools import combinations
from testflows.connect import Shell
from integration.tests.steps.sql import *
from integration.tests.steps.service_settings_steps import *


@TestOutline
def stop_start_parallel(self, services, loops=10):
    """Check for data consistency with concurrently service is stopping and starting after 5 sec."""
    uid = getuid()

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test{uid}"

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
            "(id int(11) NOT NULL,"
            "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL DEFAULT '',"
            f"pad char(60) NOT NULL DEFAULT '', PRIMARY KEY (id)) ENGINE = InnoDB;",
        )

    with When(
        "I insert, update, delete  data in MySql table with concurrently unavailable service"
    ):
        Given(
            "I insert, update, delete data in MySql table",
            test=concurrent_queries,
            parallel=True,
        )(
            table_name=table_name,
            first_insert_number=1,
            last_insert_number=3000,
            first_insert_id=3001,
            last_insert_id=6000,
            first_delete_id=1,
            last_delete_id=1500,
            first_update_id=1501,
            last_update_id=3000,
        )

        for i in range(loops):
            with Step(f"LOOP STEP {i}"):
                time.sleep(10)
                for node in services:
                    with Shell() as bash:
                        self.context.cluster.node(f"{node}").stop()
                time.sleep(5)
                for node in services:
                    with Shell() as bash:
                        self.context.cluster.node(f"{node}").start()

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_optimize=True)


@TestSuite
def debezium_stop_start_parallel(self):
    """Check replication with debezium unavailable concurrently."""
    nodes_list = ["debezium"]

    Scenario(f"{nodes_list} unavailable", test=stop_start_parallel, flags=TE)(
        services=nodes_list, loops=5
    )


@TestOutline
def stop_start(self, services):
    """Check for data consistency with service is stopping and starting."""

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test{getuid()}"

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
            "(id int(11) NOT NULL,"
            "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL,"
            f"pad char(60) NOT NULL, PRIMARY KEY (id)) ENGINE = InnoDB;",
        )

    with When("I insert 4 rows in MySQL"):
        for i in range(4):
            retry(
                mysql.query,
                timeout=300,
                delay=10,
            )(f"INSERT INTO {table_name} VALUES ({i+1},1,1,1)", exitcode=0)

        retry(
            clickhouse.query,
            timeout=300,
            delay=10,
        )(f"SELECT count(*) FROM test.{table_name}", message="4")

        for node in services:
            with Shell() as bash:
                self.context.cluster.node(f"{node}").stop()

        for i2 in range(2):
            retry(
                mysql.query,
                timeout=300,
                delay=10,
            )(f"INSERT INTO {table_name} VALUES ({i2 + 6},1,1,1)", exitcode=0)

        for i2 in range(2):
            retry(
                mysql.query,
                timeout=300,
                delay=10,
            )(f"select * from {table_name}", exitcode=0)

        for node in services:
            with Shell() as bash:
                self.context.cluster.node(f"{node}").start()

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_finale=True)

    with And("Drop system tables"):
        clickhouse.query(f"DROP DATABASE altinity_sink_connector")


@TestSuite
def debezium_stop_start(self):
    """Check replication with debezium unavailable."""

    nodes_list = ["debezium"]

    Scenario(f"{nodes_list} unavailable", test=stop_start, flags=TE)(
        services=nodes_list
    )


@TestSuite
def clickhouse_stop_start(self):
    """Check replication with debezium unavailable."""
    nodes_list = ["clickhouse"]

    Scenario(f"{nodes_list} unavailable", test=stop_start, flags=TE)(
        services=nodes_list
    )


@TestSuite
def clickhouse_stop_start_parallel(self):
    """Check replication with clickhouse unavailable."""
    nodes_list = ["clickhouse"]

    Scenario(f"{nodes_list} unavailable", test=stop_start_parallel, flags=TE)(
        services=nodes_list, loops=5
    )


@TestModule
@Name("offset")
def module(self):
    """Check the consistency of data replication while a service is inactive, but the process of inserting
    data continues."""

    for suite in loads(current_module(), Suite):
        Suite(run=suite)

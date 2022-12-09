from itertools import combinations
from testflows.connect import Shell

from integration.tests.steps import *


@TestOutline
def restart(self, services, loops=10, delete_number=1500):
    """Check for data consistency with concurrently service restart 10 times."""
    uid = getuid()

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test"

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")
    pause()

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
            "(id int(11) NOT NULL,"
            "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL DEFAULT '',"
            f"pad char(60) NOT NULL DEFAULT '', PRIMARY KEY (id)) ENGINE = InnoDB;",
        )

    with When(
        "I insert, update, delete data in MySql table concurrently with services restart"
    ):
        Step(
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

        # for i in range(loops):
        #     with Step(f"LOOP STEP {i}"):
        #         for node in services:
        #             self.context.cluster.node(f"{node}").restart()

    with And("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_optimize=True)


@TestSuite
def combinatoric_restart_test(self):
    """Check all possibilities of restart services."""
    nodes_list = ["debezium"]
    service_combinations = list(combinations(nodes_list, 1))
    for combination in service_combinations:
        Scenario(f"{combination} restart", test=restart, flags=TE)(
            services=combination, loops=5
        )


@TestFeature
@Name("manual section")
def feature(self):
    """MySql to ClickHouse replication sanity test that checks
    basic replication using a simple table."""

    with Given("I enable debezium connector after kafka starts up"):
        init_debezium_connector()

    for suite in loads(current_module(), Suite):
        Suite(run=suite)

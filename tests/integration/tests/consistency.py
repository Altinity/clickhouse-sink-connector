import time
from itertools import combinations
from testflows.connect import Shell

from integration.tests.steps import *


@TestOutline
def unavailable(self, services, loops=10):
    """Check for data consistency with concurrently service unavailable."""
    uid = getuid()

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test{uid}"

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

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
                for node in services:
                    with Shell() as bash:
                        self.context.cluster.node(f"{node}").stop()
                        time.sleep(5)
                        self.context.cluster.node(f"{node}").start()

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_optimize=True)


@TestSuite
def combinatoric_unavailable(self):
    """Check all possibilities of unavailable services."""
    nodes_list = ["sink", "debezium", "schemaregistry", "kafka", "clickhouse"]
    for i in range(1, 6):
        service_combinations = list(combinations(nodes_list, i))
        for combination in service_combinations:
            Scenario(f"{combination} unavailable", test=unavailable, flags=TE)(
                services=combination
            )


@TestOutline
def restart(self, services, loops=10):
    """Check for data consistency with concurrently service restart 10 times."""
    uid = getuid()

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test{uid}"

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

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
                for node in services:
                    self.context.cluster.node(f"{node}").restart()

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_optimize=True)


@TestSuite
def combinatoric_restart(self):
    """Check all possibilities of restart services."""
    nodes_list = ["sink", "debezium", "schemaregistry", "kafka", "clickhouse"]
    for i in range(1, 6):
        service_combinations = list(combinations(nodes_list, i))
        for combination in service_combinations:
            Scenario(f"{combination} restart", test=restart, flags=TE)(
                services=combination
            )


@TestOutline
def unstable_network_connection(self, services, loops=10):
    """Check for data consistency with unstable network connection to some services."""
    uid = getuid()

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test{uid}"

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

    with Given(f"I create MySQL table {table_name}"):
        create_mysql_table(
            name=table_name,
            statement=f"CREATE TABLE {table_name} "
                      "(id int(11) NOT NULL,"
                      "k int(11) NOT NULL DEFAULT 0,c char(120) NOT NULL DEFAULT '',"
                      f"pad char(60) NOT NULL DEFAULT '', PRIMARY KEY (id)) ENGINE = InnoDB;",
        )

    with When("I add network fault"):
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
                for node in services:
                    with Shell() as bash:
                        bash(
                            f"docker network disconnect mysql_to_clickhouse_replication_env_default {node}",
                            timeout=100,
                        )
                        time.sleep(5)
                        bash(
                            f"docker network connect mysql_to_clickhouse_replication_env_default {node}",
                            timeout=100,
                        )

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_optimize=True)


@TestSuite
def combinatoric_unstable_network_connection(self):
    """Check all possibilities of unstable network connection services."""
    nodes_list = ["sink", "debezium", "schemaregistry", "kafka", "clickhouse"]
    for i in range(1, 6):
        service_combinations = list(combinations(nodes_list, i))
        for combination in service_combinations:
            Scenario(
                f"{combination} unstable network connection",
                test=unstable_network_connection,
                flags=TE,
            )(services=combination)


@TestOutline
def hard_restart(self, services, loops=10):
    """Check for data consistency with concurrently service hard "kill -9" restart 10 times."""
    uid = getuid()

    clickhouse = self.context.cluster.node("clickhouse")
    mysql = self.context.cluster.node("mysql-master")

    with Given("I create unique table name"):
        table_name = f"test{uid}"

    init_sink_connector(auto_create_tables=True, topics=f"SERVER5432.test.{table_name}")

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
                for node in services:
                    with Shell() as bash:
                        self.context.cluster.node(f"{node}").kill()
                        time.sleep(5)
                        self.context.cluster.node(f"{node}").start()

    with Then("I check that ClickHouse table has same number of rows as MySQL table"):
        select(statement="count(*)", table_name=table_name, with_optimize=True)


@TestSuite
def combinatoric_hard_restart_test(self):
    """Check all possibilities of restart services."""
    nodes_list = ["sink"]
    service_combinations = list(combinations(nodes_list, 1))
    for combination in service_combinations:
        Scenario(
            f"{combination} unstable network connection",
            test=hard_restart,
            flags=TE,
        )(services=combination)


@TestSuite
def combinatoric_hard_restart(self):
    """Check all possibilities of restart services."""
    nodes_list = ["sink", "debezium", "schemaregistry", "kafka", "clickhouse"]
    for i in range(1, 6):
        service_combinations = list(combinations(nodes_list, i))
        for combination in service_combinations:
            Scenario(f"{combination} restart", test=hard_restart, flags=TE)(
                services=combination
            )


@TestFeature
@Name("consistency")
def feature(self):
    """Сheck data consistency when network or service faults are introduced."""
    with Given("I enable debezium and sink connectors after kafka starts up"):
        init_debezium_connector()

    for suite in loads(current_module(), Suite):
        Suite(run=suite)

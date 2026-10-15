import os
import time

from integration.tests.steps.mysql import *
from integration.tests.steps.clickhouse import *
from integration.tests.steps.service_settings import *

# Imported after the star imports on purpose: they bring a test step named
# `copy` into this namespace, which shadowed the standard-library module
# ("'TestStep' object has no attribute 'deepcopy'").
from copy import deepcopy as _deepcopy


def base_config_file(env):
    """The configuration file the suite started the connector with."""
    name = "replicated_config.yml" if "replicated" in env else "config.yml"
    return os.path.join(env, "configs", name)


@TestScenario
def check_schema_only(self):
    """Check that with `snapshot.mode: no_data` the initial snapshot creates the
    table in ClickHouse but does not copy the rows that already exist in MySQL,
    and that rows written after the snapshot are replicated.

    `no_data` only affects the initial snapshot, so the rows that must stay
    behind are written BEFORE the connector starts, and the connector starts
    with offset and schema-history tables of its own so that it really takes a
    new snapshot (with the suite's offsets it would resume streaming instead).
    """
    uid = getuid()
    database = f"schema_only_{uid}"
    table_name = f"tb_{uid}"
    mysql = self.context.mysql_node
    clickhouse = self.context.clickhouse_node
    sink = self.context.sink_node
    config_file = os.path.join(self.context.env, "configs", "schema_only.yml")
    saved_config = _deepcopy(self.context.config.data)

    try:
        with Given("the connector is stopped"):
            if sink.sink_connector_pid():
                sink.stop_sink_connector()

        with And("a MySQL table that already holds a row"):
            create_mysql_database(database_name=database)
            create_clickhouse_database(name=database)
            mysql.query(
                f"CREATE TABLE {database}.{table_name} "
                f"(id INT NOT NULL, col1 varchar(255), col2 int, PRIMARY KEY (id)) ENGINE = InnoDB;"
            )
            mysql.query(f"INSERT INTO {database}.{table_name} VALUES (1, 'before', 1);")

        with When("the connector starts in schema-only mode with fresh offsets and history"):
            self.context.config.update(
                {
                    "snapshot.mode": "no_data",
                    "database.include.list": database,
                    "offset.storage.jdbc.table.name": f"altinity_sink_connector.replica_source_info_{uid}",
                    "schema.history.internal.jdbc.table.name": f"altinity_sink_connector.replicate_schema_history_{uid}",
                }
            )
            self.context.config.save(filename=config_file)
            sink.start_sink_connector(config_file=config_file)

        with Then("the table is created in ClickHouse"):
            for attempt in retries(timeout=120, delay=3):
                with attempt:
                    clickhouse.query(f"EXISTS {database}.{table_name}", message="1")

        with And("the row that existed before the snapshot was not copied"):
            for _ in range(10):
                assert (
                    clickhouse.query(
                        f"SELECT count() FROM {database}.{table_name} FINAL"
                    ).output.strip()
                    == "0"
                ), error()
                time.sleep(1)

        with When("a row is inserted after the snapshot"):
            mysql.query(f"INSERT INTO {database}.{table_name} VALUES (2, 'after', 2);")

        with Then("only the new row is replicated"):
            for attempt in retries(timeout=120, delay=3):
                with attempt:
                    clickhouse.query(
                        f"SELECT id, col1, col2 FROM {database}.{table_name} FINAL FORMAT CSV",
                        message='2,"after",2',
                    )
            assert (
                clickhouse.query(
                    f"SELECT count() FROM {database}.{table_name} FINAL"
                ).output.strip()
                == "1"
            ), error()

    finally:
        with Finally("the connector runs the suite's configuration again"):
            self.context.config.data = saved_config
            sink.restart_sink_connector(config_file=base_config_file(self.context.env))


@TestModule
@Name("schema only")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_TableSchemaCreation("1.0")
)
def module(
    self,
    clickhouse_node="clickhouse",
    mysql_node="mysql-master",
):
    """
    Check that it is possible to only replicate the schema of the table using snapshot mode: no_data.
    """
    self.context.clickhouse_node = self.context.cluster.node(clickhouse_node)
    self.context.mysql_node = self.context.cluster.node(mysql_node)

    for scenario in loads(current_module(), Scenario):
        Scenario(run=scenario)

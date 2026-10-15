#!/usr/bin/env python3

import os
import sys
import time

from testflows.core import *

from integration.tests.steps.sink_configurations import (
    config_with_replicated_table,
)

append_path(sys.path, "..")

from integration.helpers.argparser import argparser
from integration.helpers.common import check_clickhouse_version
from integration.helpers.common import create_cluster
from integration.helpers.create_config import *
from integration.requirements.requirements import *
from integration.tests.steps.clickhouse import *

ffails = {}

xflags = {}


@TestModule
@ArgumentParser(argparser)
@FFails(ffails)
@XFlags(xflags)
@Name("auto replicated table creation")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplicatedReplacingMergeTree(
        "1.0"
    ),
)
@Specifications(SRS030_MySQL_to_ClickHouse_Replication)
def regression(
    self,
    local,
    clickhouse_binary_path,
    clickhouse_version,
    hikari_pool,
    env="env/auto_replicated",
    stress=None,
    thread_fuzzer=None,
    collect_service_logs=None,
):
    """ClickHouse regression for MySQL to ClickHouse replication with auto replicated table creation."""
    nodes = {
        "clickhouse-sink-connector-lt": ("clickhouse-sink-connector-lt",),
        "mysql-master": ("mysql-master",),
        "clickhouse": ("clickhouse", "clickhouse1", "clickhouse2", "clickhouse3"),
        "zookeeper": ("zookeeper",),
    }

    if not hikari_pool:
        default_config["connection.pool.disable"] = "true"
        default_config["clickhouse.jdbc.params"] = (
            "max_open_connections=100,keepalive.timeout=3,max_buffer_size=1000000,socket_timeout=30000,connection_timeout=30000"
        )
    else:
        default_config["connection.pool.disable"] = "false"

    self.context.nodes = nodes
    self.context.clickhouse_version = clickhouse_version
    self.context.config = SinkConfig()
    create_default_sink_config_replicated()

    if stress is not None:
        self.context.stress = stress

    if collect_service_logs is not None:
        self.context.collect_service_logs = collect_service_logs

    if current_cpu() == "aarch64":
        env = f"env/auto_replicated_arm64"
    else:
        env = "env/auto_replicated"

    with Given("docker-compose cluster"):
        cluster = create_cluster(
            local=local,
            clickhouse_binary_path=clickhouse_binary_path,
            thread_fuzzer=thread_fuzzer,
            collect_service_logs=collect_service_logs,
            stress=stress,
            nodes=nodes,
            docker_compose_project_dir=os.path.join(current_dir(), env),
            caller_dir=os.path.join(current_dir()),
        )

    self.context.cluster = cluster
    
    self.context.env = env

    self.context.clickhouse_table_engine = "ReplicatedReplacingMergeTree"
    self.context.clickhouse_table_engines = ["ReplicatedReplacingMergeTree"]

    self.context.database = "test"

    if check_clickhouse_version("<21.4")(self):
        skip(reason="only supported on ClickHouse version >= 21.4")

    self.context.node = cluster.node("clickhouse")

    with And("I create test database in ClickHouse"):
        create_clickhouse_database(name="test")

    with And("I start sink-connector-lightweight"):
        self.context.sink_node = cluster.node("clickhouse-sink-connector-lt")
        
        self.context.sink_node.start_sink_connector(
            config_file=os.path.join(current_dir(), self.context.env, "configs", "replicated_config.yml")
        )

    with Pool(1) as executor:
        Feature(
            run=load("tests.sanity", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.autocreate", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.insert", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.alter", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.compound_alters", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.parallel_alters", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.truncate", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.deduplication", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.types", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.columns_inconsistency", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.snowflake_id", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.databases", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.table_names", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.is_deleted", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.calculated_columns", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.datatypes", "module"),
            parallel=True,
            executor=executor,
        )
        Feature(
            run=load("tests.retry_on_fail", "module"),
            parallel=True,
            executor=executor,
        )

        join()

    Feature(run=load("tests.databases", "module"))
    Feature(
        run=load("tests.schema_only", "module"),
    )
    Feature(
        run=load("tests.sink_cli_commands", "module"),
    )
    Feature(
        run=load("tests.multiple_databases", "module"),
    )


if __name__ == "__main__":
    regression()

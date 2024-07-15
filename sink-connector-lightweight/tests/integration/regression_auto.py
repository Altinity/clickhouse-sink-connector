#!/usr/bin/env python3

import os
import sys
import time

from testflows.core import *

append_path(sys.path, "..")

from integration.helpers.argparser import argparser
from integration.helpers.common import check_clickhouse_version
from integration.helpers.common import create_cluster
from integration.helpers.create_config import *
from integration.requirements.requirements import *
from integration.tests.steps.clickhouse import *

ffails = {
    "primary keys/no primary key": (
        Skip,
        "https://github.com/Altinity/clickhouse-sink-connector/issues/39",
    ),
    "delete/no primary key innodb": (Skip, "doesn't work in raw"),
    "delete/no primary key": (Skip, "doesn't work in raw"),
    "update/no primary key innodb": (Skip, "makes delete"),
    "update/no primary key": (Skip, "makes delete"),
    "/mysql to clickhouse replication/auto table creation/truncate/no primary key innodb/{'ReplacingMergeTree'}/*": (
        Skip,
        "doesn't work",
    ),
    "/mysql to clickhouse replication/auto table creation/truncate/no primary key/{'ReplacingMergeTree'}/*": (
        Skip,
        "doesn't work",
    ),
    "/mysql to clickhouse replication/auto table creation/truncate/no primary key": (
        Skip,
        "doesn't work",
    ),
    "partition limits": (Skip, "doesn't ready"),
    "delete/many partition many parts/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "delete/one million datapoints/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "delete/many partition one part/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "delete/one partition one part/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "delete/one partition mixed parts/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "delete/many partition mixed parts/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "delete/parallel/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "update/many partition many parts": (
        Skip,
        "doesn't work without primary key and doesn't insert duplicates of primary key",
    ),
    "update/one million datapoints": (
        Skip,
        "doesn't work without primary key and doesn't insert duplicates of primary key",
    ),
    "update/many partition one part": (
        Skip,
        "doesn't work without primary key and doesn't insert duplicates of primary key",
    ),
    "insert/many partition many parts/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "insert/one million datapoints/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "insert/many partition one part/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "insert/one partition one part/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "insert/one partition mixed parts/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "insert/many partition mixed parts/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "insert/parallel/*_no_primary_key": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "/mysql to clickhouse replication/auto table creation/insert/*": (
        Skip,
        "doesn't work without primary key as only last row of insert is replicated",
    ),
    "/mysql to clickhouse replication/auto table creation/partitions/*": (
        Skip,
        "https://github.com/Altinity/clickhouse-sink-connector/issues/461",
    ),
    "/mysql to clickhouse replication/auto table creation/truncate/no primary key innodb/*": (
        Skip,
        "Sometimes when inserting two values, only one values is replicated. Seems to be a config issue.",
    ),
    "/mysql to clickhouse replication/auto table creation/truncate/no primary key/*": (
        Skip,
        "Sometimes when inserting two values, only one values is replicated. Seems to be a config issue.",
    ),
    "/mysql to clickhouse replication/auto table creation/schema only/*": (
        Skip,
        "Seems to be broken in CI/CD. need to fix.",
    ),
    "/mysql to clickhouse replication/auto table creation/cli/*": (
        Skip,
        "Seems to be broken in CI/CD. need to fix.",
    ),
    "/mysql to clickhouse replication/auto table creation/parallel alters/multiple parallel add modify drop column": (
        Skip,
        "Test requires fixing.",
    ),
}

xflags = {}


@TestModule
@ArgumentParser(argparser)
@FFails(ffails)
@XFlags(xflags)
@Name("auto table creation")
@Requirements(
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions("1.0"),
    RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_InnoDB(
        "1.0"
    ),
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
    env="env/auto",
    stress=None,
    thread_fuzzer=None,
    collect_service_logs=None,
):
    """ClickHouse regression for MySQL to ClickHouse replication with auto table creation."""
    nodes = {
        "clickhouse-sink-connector-lt": ("clickhouse-sink-connector-lt",),
        "mysql-master": ("mysql-master",),
        "clickhouse": ("clickhouse", "clickhouse1", "clickhouse2", "clickhouse3"),
        "zookeeper": ("zookeeper",),
    }

    self.context.nodes = nodes
    self.context.clickhouse_version = clickhouse_version
    self.context.config = SinkConfig()
    create_default_sink_config()

    if stress is not None:
        self.context.stress = stress

    if collect_service_logs is not None:
        self.context.collect_service_logs = collect_service_logs

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

    self.context.clickhouse_table_engines = ["ReplacingMergeTree"]
    self.context.clickhouse_table_engine = "ReplacingMergeTree"

    self.context.database = "test"

    if check_clickhouse_version("<21.4")(self):
        skip(reason="only supported on ClickHouse version >= 21.4")

    self.context.node = cluster.node("clickhouse")

    with And("I create test database in ClickHouse"):
        create_clickhouse_database(name="test")

    with And("I start sink-connector-lightweight"):
        self.context.sink_node = cluster.node("clickhouse-sink-connector-lt")

        self.context.sink_node.start_sink_connector()

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
            run=load("tests.virtual_columns", "module"),
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

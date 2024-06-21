#!/usr/bin/env python3

import os
import sys


from testflows.core import *


append_path(sys.path, "..")

from integration.helpers.argparser import argparser
from integration.helpers.common import check_clickhouse_version
from integration.helpers.common import create_cluster
from integration.requirements.requirements import *
from integration.tests.steps.clickhouse import *

xfails = {
    "schema changes/table recreation with different datatypes": [
        (Fail, "debezium data conflict crash")
    ],
    "schema changes/consistency": [(Fail, "doesn't finished")],
    "primary keys/no primary key": [
        (Fail, "https://github.com/Altinity/clickhouse-sink-connector/issues/39")
    ],
    "delete/no primary key innodb": [(Fail, "doesn't work in raw")],
    "delete/no primary key": [(Fail, "doesn't work in raw")],
    "update/no primary key innodb": [(Fail, "makes delete")],
    "update/no primary key": [(Fail, "makes delete")],
    "truncate/no primary key innodb": [(Fail, "doesn't work")],
    "truncate/no primary key": [(Fail, "doesn't work")],
    "consistency": [(Fail, "doesn't finished")],
    "partition limits": [(Fail, "doesn't ready")],
    "types/json": [(Fail, "doesn't work in raw")],
    "types/double": [
        (Fail, "https://github.com/Altinity/clickhouse-sink-connector/issues/170")
    ],
    "types/bigint": [
        (Fail, "https://github.com/Altinity/clickhouse-sink-connector/issues/15")
    ],
    "delete/many partition many parts": [
        (
            Fail,
            "doesn't work without primary key and doesn't insert duplicates of primary key",
        )
    ],
    "delete/one million datapoints": [
        (
            Fail,
            "doesn't work without primary key and doesn't insert duplicates of primary key",
        )
    ],
    "delete/many partition one part": [
        (
            Fail,
            "doesn't work without primary key and doesn't insert duplicates of primary key",
        )
    ],
    "update/many partition many parts": [
        (
            Fail,
            "doesn't work without primary key and doesn't insert duplicates of primary key",
        )
    ],
    "update/one million datapoints": [
        (
            Fail,
            "doesn't work without primary key and doesn't insert duplicates of primary key",
        )
    ],
    "update/many partition one part": [
        (
            Fail,
            "doesn't work without primary key and doesn't insert duplicates of primary key",
        )
    ],
    "insert/many partition many parts/*_no_primary_key": [
        (Fail, "doesn't work without primary key only last row of insert is replicated")
    ],
    "insert/one million datapoints/*_no_primary_key": [
        (Fail, "doesn't work without primary key only last row of insert is replicated")
    ],
    "insert/many partition one part/*_no_primary_key": [
        (Fail, "doesn't work without primary key only last row of insert is replicated")
    ],
    "insert/one partition one part/*_no_primary_key": [
        (Fail, "doesn't work without primary key only last row of insert is replicated")
    ],
    "insert/one partition mixed parts/*_no_primary_key": [
        (Fail, "doesn't work without primary key only last row of insert is replicated")
    ],
    "insert/many partition mixed parts/*_no_primary_key": [
        (Fail, "doesn't work without primary key only last row of insert is replicated")
    ],
    "insert/parallel": [(Fail, "different results in MySQL and Clickhouse")],
    "types/enum": [(Fail, "doesn't replicate data")],
}
xflags = {}


@TestModule
@ArgumentParser(argparser)
@XFails(xfails)
@XFlags(xflags)
@Name("mysql to clickhouse replication manual")
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
    env="env/manual",
    stress=None,
    thread_fuzzer=None,
    collect_service_logs=None,
):
    """ClickHouse regression for MySQL to ClickHouse replication with manual table creation."""
    nodes = {
        "debezium": ("debezium",),
        "mysql-master": ("mysql-master",),
        "clickhouse": ("clickhouse", "clickhouse1", "clickhouse2", "clickhouse3"),
        "zookeeper": ("zookeeper",),
    }

    self.context.clickhouse_version = clickhouse_version

    if stress is not None:
        self.context.stress = stress

    if collect_service_logs is not None:
        self.context.collect_service_logs = collect_service_logs

    env = env

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

    self.context.clickhouse_table_engines = [
        "ReplacingMergeTree",
    ]

    if check_clickhouse_version("<21.4")(self):
        skip(reason="only supported on ClickHouse version >= 21.4")

    self.context.node = cluster.node("clickhouse1")

    with And("I create test database in ClickHouse"):
        create_clickhouse_database(name="test")

    modules = [
        "sanity",
        "autocreate",
        "insert",
        "update",
        "delete",
        "parallel",
        "alter",
        "compound_alters",
        "parallel_alters",
        "truncate",
        "deduplication",
        "types",
        "primary_keys",
        "schema_changes",
        "multiple_tables",
        "virtual_columns",
        "partition_limits",
        "columns_inconsistency",
        "snowflake_id",
    ]
    for module in modules:
        Feature(run=load(f"tests.{module}", "module"))

    Feature(run=load("tests.consistency", "module"))
    Feature(run=load("tests.sysbench", "module"))
    Feature(run=load("tests.manual_section", "module"))


if __name__ == "__main__":
    regression()

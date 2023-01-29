#!/usr/bin/env python3

import os
import sys


from testflows.core import *


append_path(sys.path, "..")

from integration.helpers.argparser import argparser
from integration.helpers.common import check_clickhouse_version
from integration.helpers.common import create_cluster
from integration.requirements.requirements import *
from integration.tests.steps_global import *


xfails = {
    "schema changes/table recreation with different datatypes": [
        (Fail, "debezium data conflict crash")
    ],
    "schema changes/consistency": [
        (Fail, "doesn't finished")
    ],
    "primary keys/no primary key": [
        (Fail, "https://github.com/Altinity/clickhouse-sink-connector/issues/39")
    ],
    "delete/no primary key innodb": [
        (Fail, "doesn't work in raw")
    ],
    "delete/no primary key": [
        (Fail, "doesn't work in raw")
    ],
    "update/no primary key innodb": [
        (Fail, "makes delete")
    ],
    "update/no primary key": [
        (Fail, "makes delete")
    ],
    "types": [
        (Fail, "xfailed while mapping is changing")
    ],
    "insert": [
        (Fail, "doesn't work for latest version `SELECT ... FINAL` eats rows")
    ],
    "consistency": [
        (Fail, "doesn't finished")
    ],
    "sysbench": [
        (Fail, "doesn't have updates long time, need to recheck")
    ],
    "partition limits": [
        (Fail, "doesn't ready")
    ],
    "types autocreate/json": [
        (Fail, "doesn't work in raw")
    ],
    "types autocreate/double": [
        (Fail, "https://github.com/Altinity/clickhouse-sink-connector/issues/170")
    ],
    "types autocreate/bigint": [
        (Fail, "https://github.com/Altinity/clickhouse-sink-connector/issues/15")
    ],
}
xflags = {}


@TestModule
@ArgumentParser(argparser)
@XFails(xfails)
@XFlags(xflags)
@Name("mysql to clickhouse replication")
@Requirements(RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication("1.0"),
              RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_Consistency_Select("1.0"),
              RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLVersions("1.0"),
              RQ_SRS_030_ClickHouse_MySQLToClickHouseReplication_MySQLStorageEngines_ReplacingMergeTree("1.0"))
@Specifications(SRS030_MySQL_to_ClickHouse_Replication)
def regression(
    self,
    local,
    clickhouse_binary_path,
    clickhouse_version,
    stress=None,
    thread_fuzzer=None,
    collect_service_logs=None,
):
    """ClickHouse regression for MySql to ClickHouse replication."""
    nodes = {
        "debezium": ("debezium",),
        "mysql-master": ("mysql-master",),
        "clickhouse": ("clickhouse", "clickhouse1", "clickhouse2", "clickhouse3"),
        "bash-tools": ("bash-tools",),
        "schemaregistry": ("schemaregistry",),
        "sink": ("sink",),
        "zookeeper": ("zookeeper",),
    }

    self.context.clickhouse_version = clickhouse_version

    if stress is not None:
        self.context.stress = stress

    if collect_service_logs is not None:
        self.context.collect_service_logs = collect_service_logs

    env = "env"

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

    if check_clickhouse_version("<21.4")(self):
        skip(reason="only supported on ClickHouse version >= 21.4")

    self.context.node = cluster.node("clickhouse1")

    with And("I create test database in ClickHouse"):
        create_database(name="test")

    features = ["sanity", "types_autocreate", "deduplication", "primary_keys", "autocreate", "schema_changes",
                "multiple_tables", "multiple_tables", "virtual_columns", "delete", "update", "truncate",
                "partition_limits", "replicated_engine"]
    for feature in features:
        Feature(run=load(f"tests.{feature}", "feature"))

    Feature(run=load("tests.types", "feature"))
    Feature(run=load("tests.insert", "feature"))
    Feature(run=load("tests.consistency", "feature"))
    Feature(run=load("tests.sysbench", "feature"))
    # Feature(run=load("tests.manual_section", "feature"))


if __name__ == "__main__":
    regression()

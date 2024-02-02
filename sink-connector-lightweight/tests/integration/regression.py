#!/usr/bin/env python3

import os
import sys


from testflows.core import *


append_path(sys.path, "..")

from integration.helpers.argparser import argparser
from integration.helpers.common import check_clickhouse_version
from integration.helpers.common import create_cluster
from integration.requirements.requirements import *
from integration.tests.steps.steps_global import *

ffails = {}


@TestModule
@Name("mysql to clickhouse replication")
@FFails(ffails)
@ArgumentParser(argparser)
def regression(
    self,
    local,
    clickhouse_binary_path,
    clickhouse_version,
    stress=None,
    thread_fuzzer=None,
    collect_service_logs=None,
):
    """Mysql to clickhouse replication regression."""
    args = {
        "local": local,
        "clickhouse_binary_path": clickhouse_binary_path,
        "clickhouse_version": clickhouse_version,
        "stress": stress,
    }

    self.context.stress = stress

    with Pool(2) as pool:
        try:
            Feature(
                test=load("regression_auto", "regression"),
                parallel=True,
                executor=pool,
            )(**args)
        finally:
            join()


if main():
    regression()

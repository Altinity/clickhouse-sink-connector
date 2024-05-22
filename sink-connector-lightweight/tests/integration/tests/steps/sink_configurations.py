import os

from integration.helpers.create_config import *
from integration.helpers.common import change_sink_configuration

default_config_path = os.path.join("env", "auto", "configs")


@TestStep(Given)
def config_with_replicated_table(
    self, config_file=default_config_path + "replicated_replacing_merge_tree.yml"
):
    """Create the Sink Connector configuration with the ReplicatedReplacingMergeTree table."""
    change_sink_configuration(
        values={"auto.create.tables.replicated": "true", "auto.create.tables": "true"},
        config_file=config_file,
    )


@TestStep(Given)
def config_with_replicated_table_and_disabled_auto_create(
    self,
    config_file=default_config_path
    + "replicated_replacing_merge_tree_no_auto_create.yml",
):
    """Create the Sink Connector configuration with the ReplicatedReplacingMergeTree table."""
    change_sink_configuration(
        values={
            "auto.create.tables.replicated": "true",
            "auto.create.tables": "false",
            "enable.snapshot.ddl": "false",
        },
        config_file=config_file,
    )


@TestStep(Given)
def config_with_schema_only(self, config_file=default_config_path + "schema_only.yml"):
    """Create the Sink Connector configuration with the schema only."""
    change_sink_configuration(
        values={"snapshot.mode": "schema_only"}, config_file=config_file
    )

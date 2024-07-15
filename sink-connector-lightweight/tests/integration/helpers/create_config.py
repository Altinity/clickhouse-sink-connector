import yaml

from integration.helpers.default_config import default_config
from testflows.core import *


def literal_unicode_representer(dumper, data):
    """Remove newline from the string and represent it as a literal block."""
    if "\n" in data:
        return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")
    return dumper.represent_scalar("tag:yaml.org,2002:str", data)


class SinkConfig:
    def __init__(self, initial_data=None):
        """Initialize the sink connector configuration with the configuration values."""
        if initial_data is None:
            initial_data = default_config
        self.data = initial_data
        yaml.add_representer(str, literal_unicode_representer)

    def update(self, new_data):
        """Update the ClickHouse Sink Connector configuration."""
        for key, value in new_data.items():
            self.data[key] = value

    def remove(self, key):
        """Remove the ClickHouse Sink Connector configuration key."""
        if key in self.data:
            self.data.pop(key)

    def display_config(self):
        """Print out the ClickHouse Sink Connector configuration."""
        print(
            yaml.dump(
                self.data, default_flow_style=False, sort_keys=False, allow_unicode=True
            )
        )

    def save(self, filename="config.yaml"):
        """Save the ClickHouse Sink Connector configuration to the file."""
        with open(filename, "w") as file:
            yaml.dump(
                self.data,
                file,
                default_flow_style=False,
                allow_unicode=True,
                sort_keys=False,
            )


@TestStep(Given)
def create_default_sink_config(self, path="env/auto/configs/config.yml"):
    """Create the default sink connector configuration."""
    config = self.context.config

    with By(f"creating the default sink connector configuration file"):
        config.save(filename=path)


@TestStep(Given)
def create_default_sink_config_replicated(
    self, path="env/auto_replicated/configs/replicated_config.yml"
):
    """Create the default sink connector configuration."""
    config = self.context.config

    with By(f"creating the default sink connector configuration file"):
        config.update(
            {"auto.create.tables.replicated": "true", "auto.create.tables": "true"}
        )
        config.save(filename=path)


@TestStep(Given)
def update_sink_config(self, new_data: dict, path="env/auto/configs/config.yml"):
    """Update the sink connector configuration."""
    config = self.context.config

    with By(f"updating the sink connector configuration file"):
        config.update(new_data)
        config.save(filename=path)


@TestStep(Given)
def remove_configuration(self, key, path):
    """Remove the sink connector configuration."""
    config = self.context.config

    with By(f"removing the sink connector configuration key {key}"):
        config.remove(key)
        config.save(filename=path)


@TestStep(Given)
def include_all_databases_with_rrmt(self, node=None, config_file=None):
    """Create and use ClickHouse Sink Connector configuration which allows the following:
    - Monitors all databases in source and replicates them in destination.
    - Tables created automatically are with ReplicatedReplacingMergeTree engine.
    """
    config = self.context.config

    if node is None:
        node = self.context.sink_node

    with By("removing the ClickHouse Sink Connector configuration"):
        config.remove("database.include.list")
        config.update(
            {"auto.create.tables.replicated": "true", "auto.create.tables": "true"}
        )
        config.save(filename=config_file)

    with And(
        "restarting the ClickHouse Sink Connector and using the new configuration file"
    ):
        node.restart_sink_connector(config_file=config_file)

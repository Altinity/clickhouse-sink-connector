import yaml

from integration.helpers.default_config import default_config
from testflows.core import *


def literal_unicode_representer(dumper, data):
    if "\n" in data:
        return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")
    return dumper.represent_scalar("tag:yaml.org,2002:str", data)


class SinkConfig:
    def __init__(self, initial_data=None):
        if initial_data is None:
            initial_data = default_config
        self.data = initial_data
        yaml.add_representer(str, literal_unicode_representer)

    def update(self, new_data):
        for key, value in new_data.items():
            self.data[key] = value

    def remove(self, key):
        self.data.pop(key)

    def display_config(self):
        print(
            yaml.dump(
                self.data, default_flow_style=False, sort_keys=False, allow_unicode=True
            )
        )

    def save(self, filename="config.yaml"):
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

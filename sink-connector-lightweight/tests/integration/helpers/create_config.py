import yaml
from integration.helpers.default_config import default_config


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

    def update(self, key, value):
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

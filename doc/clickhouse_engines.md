### Supported ClickHouse Engine Types.

The sink connector supports the following ClickHouse Engine types:
- **ReplacingMergeTree**: See [Architecture] (doc/mutable_data.md) for more information.
    - ReplacingMergeTree is a variant of MergeTree that allows for updates and deletes.
    - It is the default engine type used with the sink connector when tables are auto created.
- **ReplicatedReplacingMergeTree**:
    - ReplicatedReplacingMergeTree is a variant of ReplicatedMergeTree that allows for updates and deletes.
    - To enable this engine type, set the `clickhouse.table.engine` to `ReplicatedReplacingMergeTree`.
### Supported ClickHouse Table Engine Types.

The sink connector supports the following ClickHouse Engine types:
- **ReplacingMergeTree**: See [Architecture](mutable_data.md) more information.
    - ReplacingMergeTree is a variant of MergeTree that allows for updates and deletes.
    - It is the default engine type used with the sink connector when tables are auto created using
    - `auto.create.tables` set to `true`.
- **ReplicatedReplacingMergeTree**:
    - ReplicatedReplacingMergeTree is a variant of ReplicatedMergeTree that allows for updates and deletes.
    - To enable this engine type, set the `"auto.create.tables.replicated` to `true`, sink connector will
      create tables with this engine type.

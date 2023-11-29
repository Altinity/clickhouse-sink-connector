## Kafka MetaData

These are the columns that are added when the `store.kafka.metadata` configuration is enabled.

| Column           | Data Type                | Description                                                                                                                                                           |
|------------------|--------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `_offset`        | Nullable(UInt64),        | Kafka offset                                                                                                                                                          |
| `_key`           | Nullable(String),        | Key of Record                                                                                                                                                         |
| `_topic`         | Nullable(String),        | Topic (Sink)                                                                                                                                                          |
| `_partition`     | Nullable(UInt64),        | Kafka Partition                                                                                                                                                       |
| `_timestamp`     | Nullable(DateTime),      | Timestamp of receiving record in Sink Connector                                                                                                                       |
| `_timestamp_ms`  | Nullable(DateTime64(3)), |                                                                                                                                                                       |
| `_ts_ms`         | Nullable(DateTime),      | Source timestamp of receiving record in Source Connector(Useful for calculating lag)                                                                                  |
| `server_id`      | Nullable(Int32),         | Source Server id(Example MySQL server ID)                                                                                                                             |
| `_gtid`          | Nullable(Int32),         | Source Transaction Id(Mysql Replica), gtid needs to be enabled in server(https://debezium.io/documentation/reference/stable/connectors/mysql.html#enable-mysql-gtids) |
| `_binlog_file`   | Nullable(String),        | Source binlog file name                                                                                                                                               |
| `_binlog_pos`    | Nullable(Int32),         | Source binlog file position                                                                                                                                           |
| `_binlog_row`    | Nullable(Int32),         | Source row                                                                                                                                                            |
| `_server_thread` | Nullable(Int32)          | Source server Thread id                                                                                                                                               |
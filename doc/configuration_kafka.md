

## Kafka Sink Connector for ClickHouse

### Recommended Memory limits.
**Production Usage**
In `docker-compose.yml` file, its recommended to set Xmx to atleast 5G `-Xmx5G` when using in Production and
if you encounter a `Out of memory/Heap exception` error.
for both **Debezium** and **Sink**

```
- KAFKA_HEAP_OPTS=-Xms2G -Xmx5G
```

### Kafka Sink Connector Configuration

|  Property                        |   Default | Description                                                                                                                                                           |
|----------------------------------|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| tasks.max                        | No        | SinkConnector task(essentially threads), ideally this needs to be the same as the Kafka partitions.                                                                   |
| topics.regex                     | No        | Regex of matching topics.  Example: "SERVER5432.test.(.*)" matches SERVER5432.test.employees and SERVER5432.test.products                                             |
| topics                           | No        | The list of topics. topics or topics.regex has to be provided.                                                                                                        |
| clickhouse.server.url            |           | ClickHouse Server URL                                                                                                                                                 |
| clickhouse.server.user           |           | ClickHouse Server username                                                                                                                                            |
| clickhouse.server.password           |           | ClickHouse Server password                                                                                                                                            |
| clickhouse.server.port           | 8123      | ClickHouse Server port                                                                                                                                                |
| clickhouse.topic2table.map       | No        | Map of Kafka topics to table names, <topic_name1>:<table_name1>,<topic_name2>:<table_name2> This variable will override the default mapping of topics to table names. |
| store.kafka.metadata             | false     | If set to true, kafka metadata columns will be added to Clickhouse                                                                                                    |
| store.raw.data                   | false     | If set to true, the entire row is converted to JSON and stored in the column defined by the  ` store.raw.data.column ` field                                          |
| store.raw.data.column            | No        | Clickhouse table column to store the raw data in JSON form(String Clickhouse DataType)                                                                                |
| metrics.enable                   | true      | Enable Prometheus scraping                                                                                                                                            |
| metrics.port                     | 8084      | Metrics port                                                                                                                                                          |
| buffer.flush.time.ms             | 30        | Buffer(Batch of records) flush time in milliseconds                                                                                                                   |
| thread.pool.size                 | 10        | Number of threads that is used to connect to ClickHouse                                                                                                               |
| auto.create.tables               | false     | Sink connector will create tables in ClickHouse (If it does not exist)                                                                                                |
| snowflake.id                     | true      | Uses SnowFlake ID(Timestamp + GTID) as the version column for ReplacingMergeTree                                                                                      |
| replacingmergetree.delete.column | "sign"    | Column used as the sign column for ReplacingMergeTree.

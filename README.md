# Altinity ClickHouse Replicator(Lightweight version)
New tool to replicate data from MySQL, PostgreSQL, MariaDB and Mongo without additional dependencies.
Single executable and lightweight.
##### Supports DDL in MySQL.

### Release
Images are published in Gitlab.

`registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:latest`

[Setup instructions](sink-connector-lightweight/README.md)
![](doc/img/kafka_replication_tool.jpg)

# Altinity Sink Connector for ClickHouse

Sink connector is used to transfer data from Kafka to Clickhouse using the Kafka connect framework.
The connector is tested with the following converters
- JsonConverter
- AvroConverter (Using [Apicurio Schema Registry](https://www.apicur.io/registry/) or Confluent Schema Registry)

![](doc/img/sink_connector_mysql_architecture.jpg)
# Features
- Inserts, Updates and Deletes using ReplacingMergeTree - [Updates/Deletes](doc/mutable_data.md)
- Auto create tables in ClickHouse
- Exactly once semantics 
- Bulk insert to Clickhouse. 
- Store Kafka metadata [Kafka Metadata](doc/Kafka_metadata.md)
- Kafka topic to ClickHouse table mapping, use case where MySQL table can be mapped to a different CH table name.
- Store raw data in JSON(For Auditing purposes)
- Monitoring(Using Grafana/Prometheus) Dashboard to monitor lag.
- Kafka Offset management in ClickHouse
- Increased Parallelism(Customize thread pool for JDBC connections)

# Source Databases
- MySQL (Debezium)
  **Note:GTID Enabled - Highly encouraged for Updates/Deletes** 
  Refer enabling Gtid in Replica for non-GTID sources - https://www.percona.com/blog/useful-gtid-feature-for-migrating-to-mysql-gtid-replication-assign_gtids_to_anonymous_transactions/
- PostgreSQL (Debezium)

|  Component    |   Version(Tested) |
|---------------|-------------------|
| Redpanda      | 22.1.3, 22.3.9    |
| Kafka-connect | 1.9.5.Final       |
| Debezium      | 2.1.0.Alpha1      |
| MySQL         | 8.0               |
| ClickHouse    | 22.9, 22.10       |
| PostgreSQL    | 15                |


### Quick Start (Docker-compose)
Docker image for Sink connector (Updated December 12, 2022)
`altinity/clickhouse-sink-connector:latest`
https://hub.docker.com/r/altinity/clickhouse-sink-connector

### Recommended Memory limits.
**Production Usage**
In `docker-compose.yml` file, its recommended to set Xmx to atleast 5G `-Xmx5G` when using in Production and 
if you encounter a `Out of memory/Heap exception` error. 
for both **Debezium** and **Sink**

```
- KAFKA_HEAP_OPTS=-Xms2G -Xmx5G
```


### Kubernetes
Docker Image for Sink connector(with Strimzi)
https://hub.docker.com/repository/docker/subkanthi/clickhouse-kafka-sink-connector-strimzi

Docker Image for Debezium MySQL connector(with Strimzi)
https://hub.docker.com/repository/docker/subkanthi/debezium-mysql-source-connector


Recommended to atleast set 5Gi as memory limits to run on kubernetes using strimzi.

```   resources:
      limits:
        memory: 6Gi
      requests:
        memory: 6Gi

```
#### MySQL:
```bash
cd deploy/docker
./start-docker-compose.sh 
```
#### PostgreSQL:
```
export SINK_VERSION=latest
cd deploy/docker
docker-compose -f docker-compose.yaml -f docker-compose-postgresql.override.yaml up
```

For Detailed setup instructions - [Setup](doc/setup.md)

## Development:
Requirements
- Java JDK 11 (https://openjdk.java.net/projects/jdk/11/)
- Maven (mvn) (https://maven.apache.org/download.cgi)
- Docker and Docker-compose
```
mvn install -DskipTests=true
```

## Data Types
#### Note: Using float data types are highly discouraged, because of the behaviour in ClickHouse with handing precision.(Decimal is a better choice)

| MySQL              | Kafka<br>Connect                                     | ClickHouse                      |
|--------------------|------------------------------------------------------|---------------------------------|
| Bigint             | INT64\_SCHEMA                                        | Int64                           |
| Bigint Unsigned    | INT64\_SCHEMA                                        | UInt64                          |
| Blob               |                                                      | String + hex                    |
| Char               | String                                               | String / LowCardinality(String) |
| Date               | Schema: INT64<br>Name:<br>debezium.Date              | Date(6)                         |
| DateTime(6)        | Schema: INT64<br>Name: debezium.Timestamp            | DateTime64(6)                   |
| Decimal(30,12)     | Schema: Bytes<br>Name:<br>kafka.connect.data.Decimal | Decimal(30,12)                  |
| Double             |                                                      | Float64                         |
| Int                | INT32                                                | Int32                           |
| Int Unsigned       | INT64                                                | UInt32                          |
| Longblob           |                                                      | String + hex                    |
| Mediumblob         |                                                      | String + hex                    |
| Mediumint          | INT32                                                | Int32                           |
| Mediumint Unsigned | INT32                                                | UInt32                          |
| Smallint           | INT16                                                | Int16                           |
| Smallint Unsigned  | INT32                                                | UInt16                          |
| Text               | String                                               | String                          |
| Time               |                                                      | String                          |
| Time(6)            |                                                      | String                          |
| Timestamp          |                                                      | DateTime64                      |
| Tinyint            | INT16                                                | Int8                            |
| Tinyint Unsigned   | INT16                                                | UInt8                           |
| varbinary(\*)      |                                                      | String + hex                    |
| varchar(\*)        |                                                      | String                          |
| JSON               |                                                      | String                          |
| BYTES              | BYTES, io.debezium.bits                              | String                          |
| YEAR               | INT32                                                | INT32                           |
| GEOMETRY           | Binary of WKB                                        | String                          |

### Sink Connector Configuration
|  Property                        |   Default | Description                                                                                                                                                           |
|----------------------------------|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| tasks.max                        | No        | SinkConnector task(essentially threads), ideally this needs to be the same as the Kafka partitions.                                                                   |
| topics.regex                     | No        | Regex of matching topics.  Example: "SERVER5432.test.(.*)" matches SERVER5432.test.employees and SERVER5432.test.products                                             |
| topics                           | No        | The list of topics. topics or topics.regex has to be provided.                                                                                                        |
| clickhouse.server.url            |           | ClickHouse Server URL                                                                                                                                                 |
| clickhouse.server.user           |           | ClickHouse Server username                                                                                                                                            |
| clickhouse.server.pass           |           | ClickHouse Server password                                                                                                                                            |
| clickhouse.server.database       |           | ClickHouse Database name                                                                                                                                              |
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

## ClickHouse Loader(Load Data from MySQL to CH for Initial Load)
[Clickhouse Loader](python/README.md) is a program that loads data dumped in MySQL into a CH database compatible the sink connector (ReplacingMergeTree with virtual columns _version and _sign)


### Grafana Dashboard
![](doc/img/Grafana_dashboard.png)


![](doc/img/Grafana_dashboard_2.png)



## Documentation
- [Architecture](doc/architecture.md)
- [Local Setup - Docker Compose](doc/setup.md)
- [Debezium Setup](doc/debezium_setup.md)
- [Kubernetes Setup](doc/k8s_pipeline_setup.md)
- [Sink Configuration](doc/sink_configuration.md)
- [Testing](doc/TESTING.md)
- [Performance Benchmarking](doc/Performance.md)
- [Confluent Schema Registry(REST API)](doc/schema_registry.md)

## Blog articles
- [ClickHouse as an analytic extension for MySQL](https://altinity.com/blog/using-clickhouse-as-an-analytic-extension-for-mysql?utm_campaign=Brand&utm_content=224583767&utm_medium=social&utm_source=linkedin&hss_channel=lcp-10955938)
- [Altinity Sink connector for ClickHouse](https://altinity.com/blog/fast-mysql-to-clickhouse-replication-announcing-the-altinity-sink-connector-for-clickhouse)

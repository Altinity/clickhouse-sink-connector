# Altinity Sink Connector for ClickHouse

Sink connector is used to transfer data from Kafka to Clickhouse using the Kafka connect framework.
The connector is tested with the following converters
- JsonConverter
- AvroConverter (Using [Apicurio Schema Registry](https://www.apicur.io/registry/) or Confluent Schema Registry)

![](doc/img/sink_connector_mysql_architecture.jpg)
# Features
- Inserts, Updates and Deletes using ReplacingMergeTree/CollapsingMergeTree - [Updates/Deletes](doc/mutable_data.md)
- Deduplication logic to dedupe records from Kafka topic.(Based on Primary Key)
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
- PostgreSQL (Debezium) (Testing in progress)

|  Component    |   Version(Tested) |
|---------------|-------------------|
| Redpanda      | 22.1.3            |
| Kafka-connect | 1.9.5.Final       |
| Debezium      | 1.9.5.Final       |
| MySQL         | 8.0               |
| ClickHouse    | 22.9              |


### Quick Start (Docker-compose)
Docker image for Sink connector `altinity/clickhouse-sink-connector:latest`
https://hub.docker.com/r/altinity/clickhouse-sink-connector

#### MySQL:
```bash
cd deploy/docker
./start-docker-compose.sh 
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

## DataTypes

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

## ClickHouse Loader(Load Data from MySQL to CH for Initial Load)
[Clickhouse Loader](python/README.md) is a program that loads data dumped in MySQL into a CH database compatible the sink connector (ReplacingMergeTree with virtual columns _version and _sign)


### Grafana Dashboard
![](doc/img/Grafana_dashboard.png)


## Documentation
- [Architecture](doc/architecture.md)
- [Local Setup - Docker Compose](doc/setup.md)
- [Debezium Setup](doc/debezium_setup.md)
- [Kubernetes Setup](doc/k8s_pipeline_setup.md)
- [Sink Configuration](doc/sink_configuration.md)
- [Testing](doc/TESTING.md)
- [Performance Benchmarking](doc/Performance.md)
- [Confluent Schema Registry(REST API)](doc/schema_registry.md)


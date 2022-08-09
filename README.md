# Altinity Sink Connector for ClickHouse

Sink connector sinks data from Kafka into Clickhouse.
The connector is tested with the following converters
- JsonConverter
- AvroConverter (Using [Apicurio Schema Registry](https://www.apicur.io/registry/) and Confluent Schema Registry)

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



### Grafana Dashboard
![](doc/img/Grafana_dashboard.png) \
# Source Databases
- MySQL (Debezium)
- PostgreSQL (Debezium) (Testing in progress)

## Documentation
- [Data Types](doc/DataTypes.md)
- [Architecture](doc/architecture.md)
- [Local Setup - Docker Compose](doc/setup.md)
- [Kubernetes Setup](doc/k8s_pipeline_setup.md)
- [Sink Configuration](doc/sink_configuration.md)
- [Testing](doc/TESTING.md)
- [Performance Benchmarking](doc/Performance.md)
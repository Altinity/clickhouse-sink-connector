# ClickHouse Sink Connector

Sink connector sinks data from Kafka into Clickhouse.
The connector is tested with the following converters
- JsonConverter
- AvroConverter (Using [Apicurio Schema Registry](https://www.apicur.io/registry/))


- Currently the connector only supports <b>Insert</b> operations.
- Deduplication logic to dedupe records from Kafka topic.
- Exactly once semantics 
- Bulk insert to Clickhouse.



## Documentation
- [Data Types](doc/DataTypes.md)
- [Architecture](doc/architecture.md)
- [Local Setup](doc/setup.md)
- [Sink Configuration](doc/sink_configuration.md)
- [Testing](doc/TESTING.md)
- [Glossary](doc/glossary.md)

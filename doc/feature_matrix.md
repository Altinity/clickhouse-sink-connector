## Features

| Feature | Description |
| ------- | --------- |
| Single Binary     | No additional dependencies or infrastructure required     |
| Exactly Once Processing| Offsets are committed to ClickHouse after the messages are written to ClickHouse     |
| Supported Databases     | MySQL, MariaDB, PostgreSQL, MongoDB(Experimental)     |
| Supported ClickHouse Versions     | 24.8 and above     |  
| Clickhouse Tables Types     | ReplacingMergeTree, MergeTree, ReplicatedReplacingMergeTree     |
| Replication Start positioning     | Using sink-connector-client to start replication from a specific offset or LSN(MySQL Binlog Position, PostgreSQL LSN)     |
| Supported Datatypes| Refer [Datatypes](data_types.md)     |
| Initial Data load     | Scripts to perform initial data load (MySQL)    |
| Fault Tolerance     | Sink Connector Client to continue replication from the last committed offset/LSN in case of a failure     |
| Update, Delete     | Supported with ReplacingMergeTree
| Monitoring     | Prometheus Metrics, Grafana Dashboard     |
| Schema Evolution| DDL support for MYSQL.
| Deployment Models| Docker Compose, Java JAR file, Kubernetes
| Start, Stop, Pause, Resume Replication     | Supported using sink-connector-client
| Filter sources databases, tables, columns     | Supported using debezium configuration.
| Map source databases to different ClickHouse databases     | Database name overrides supported.
| Column name overrides     | Planned
| MySQL extensive DDL support     | Full list of DDL(sink-connector-lightweight/docs/mysql-ddl-support.md)
| Replication Lag Monitoring| Grafana Dashboard and view to monitor lag
| Batch inserts to ClickHouse     | Configurable batch size/thread pool size to achieve high throughput/low latency
| MySQL Generated/Alias/Materialized Columns     | Supported
| Auto create tables| Tables are automatically created in ClickHouse based on the source table structure.

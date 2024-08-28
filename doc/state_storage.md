## State Storage

Sink connector is designed to store data about the replication state.
Currently the only supported state storage is by persisting the information in ClickHouse tables.

### ClickHouse State Storage

To use ClickHouse as a state storage, you need to specify the following configuration properties:

![State Storage](img/state_storage.jpg)

# Offsets table(MySQL)
The offsets table defined by the `offset.storage.jdbc.offset.table.name`
Default: **"altinity_sink_connector.replica_source_info"**
This table is used to store the binlog file, position and gtids.
| Column Name | Description                                                          | Example |
|-------------|----------------------------------------------------------------------|---------|
| id          | UUID                                                                 |         | 
| offset_key  | This is the Unique key for every connector. Its a combination of `name` configuration variable and the `topic.prefix` configuration variable               | [\"debezium-embedded-postgres\",{\"server\":\"embeddedconnector\"}]"| 
| offset_val  | This column stores the offset information for MySQL, **file**- binlog file, **pos**- binlog position, **gtids**- GTID                                    | {"ts_sec":1724849901,"file":"mysql-bin.000003","pos":197,"gtids":"03d24fcc-6567-11ef-9978-0242ac130003:1-56"}  
| record_insert_seq  | Timestamp when record is inserted.                                                               | 2024-08-28 12:58:22         |
| record_insert_ts  |  Monotonically increasing number                                                              |    174      |

# Schema History table(MySQL)
The schema history table defined by the `schema.history.internal.jdbc.schema.history.table.name`
Default: **"altinity_sink_connector.replicate_schema_history"**
This table is used by Debezium to store historical DDL statements so the DDL statements can be parsed. 
| Column Name | Description                                                          | Example |
|-------------|----------------------------------------------------------------------|---------|
| id          | UUID                                                                 |         | 
| history_data          | UUID |  {"source":{"server":"embeddedconnector"},"position":{"ts_sec":1724867891,"file":"mysql-bin.000003","pos":197,"gtids":"03d24fcc-6567-11ef-9978-0242ac130003:1-56","snapshot":true},"ts_ms":1724867891697,"databaseName":"test","ddl":"DROP TABLE IF EXISTS `test`.`orders`","tableChanges":[{"type":"DROP","id":"\"test\".\"orders\""}]}        | 
| history_data_seq          | Monotonically increasing sequence number                                                                   |         | 
| record_insert_seq  | Timestamp when record is inserted.                                                               | 2024-08-28 12:58:22         |
| record_insert_ts  |  Monotonically increasing number                                                              |    174      |



# Offsets table.(PostgreSQL)
| Column Name | Description                                                          |
|-------------|----------------------------------------------------------------------|
| id          | UUID                                                                 |
| offset_key  | [\"debezium-embedded-postgres\",{\"server\":\"embeddedconnector\"}]" |
| offset_val  |                                     |
| record_insert_seq  |                                                                      |
| record_insert_ts  |                                                                      |

### offsets_value
- **lsn_proc** - Last processed LSN
- **lsn_commit** - Last committed LSN
- **messageType** - Type of message(INSERT, UPDATE, DELETE)
- 

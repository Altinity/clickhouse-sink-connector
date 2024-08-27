## State Storage

Sink connector is designed to store data about the replication state.
Currently the only supported state storage is by persisting the information in ClickHouse tables.

### ClickHouse State Storage

To use ClickHouse as a state storage, you need to specify the following configuration properties:

![State Storage](img/state_storage.jpg)





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

## Handling PostgreSQL WAL Growth with Debezium Connectors
Credits: https://medium.com/@pawanpg0963/postgres-replication-lag-using-debezium-connector-4ba50e330cd6

One of the common problems with PostgreSQL is the WAL size increasing. This issue can be observed when using Debezium connectors for change data capture.
The WAL size increases due to the connector not sending any data to the replication slot. 
This can be observed by checking the replication slot lag using the following query:
```sql
postgres=# SELECT slot_name, pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS replicationSlotLag, 
pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS confirmedLag, active FROM pg_replication_slots;
 slot_name | replicationslotlag | confirmedlag | active
-----------+--------------------+--------------+--------
 db1_slot  | 20 GB              | 16 GB        | t
 db2_slot  | 62 MB              | 42 MB        | t
(2 rows)
```

This issue can be addressed using the `heartbeat.interval.ms` configuration 

### Solution
Create a new table in postgres (Heartbeat) table. 
```sql
CREATE TABLE heartbeat.pg_heartbeat (
random_text TEXT,
last_update TIMESTAMP
);
```
Add the table to the existing publisher used by the connector:
```sql
INSERT INTO heartbeat.pg_heartbeat (random_text, last_update) VALUES ('test_heartbeat', NOW());
```
Add the table to the existing publisher used by the connector:  
```
ALTER PUBLICATION db1_pub ADD TABLE heartbeat.pg_heartbeat;
ALTER PUBLICATION db2_pub ADD TABLE heartbeat.pg_heartbeat;
```
Grant privileges to the schema heartbeat and table pg_heartbeat to the replication user used by the connector.  
Add the following configuration to `config.yml`
```
heartbeat.interval.ms=10000
heartbeat.action.query="UPDATE heartbeat.pg_heartbeat SET last_update=NOW();"
```
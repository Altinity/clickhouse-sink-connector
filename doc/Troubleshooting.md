# Troubleshooting.

### Some tables are not being replicated.
This can be caused by the following:
1. The table is not being captured by the Debezium connector.
2. The database is not being captured by the Debezium connector.

Check the following configuration in the yaml file.
```
table.include.list: "sbtest.table1,sbtest.table2,sbtest.table3"
database.include.list: "sbtest"
```
You will see the list of tables/databases that are being captured by the Debezium connector in the logs.
```
[2021-09-29 14:00:00,000] INFO  [io.debezium.connector.mysql.MySqlConnector] (task-0) Snapshot step 1 - Preparing to snapshot the following 3 tables: sbtest.table1, sbtest.table2, sbtest.table3

```

### Caused by: io.debezium.DebeziumException: java.sql.SQLSyntaxErrorException: Access denied; you need (at least one of) 
### the SUPER, REPLICATION CLIENT privilege(s) for this operation
```bash
mysql> GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'user' IDENTIFIED BY 'password';

```
#### Debezium error: Handle Unable to register metrics as an old set with the same name exists
For every connector the `database.server.name` should be unique.


### High CPU usage.
This can be caused by the high number of GC threads created by the JVM.
This can be limited by passing this configuration parameter to the JVM:
```bash
#N can be 50.
-XX:ParallelGCThreads=<N>.
```

### Slow startup when replicating Database with high number of tables.
Use the following configuration to limit the number of tables that Debezium would capture in the schema_only mode.
See: https://github.com/Altinity/clickhouse-sink-connector/issues/507
```
table.include.list: "sbtest.table1,sbtest.table2,sbtest.table3"
schema.history.internal.store.only.captured.tables.ddl: "true"
schema.history.internal.store.only.captured.databases.ddl: "true"
```

### Error with binlog no longer available on the server: com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture  - Error starting connectorio.debezium.DebeziumException: The connector is trying to read binlog starting at SourceInfo [currentGtid=null, currentBinlogFilename=mysql-bin-changelog, currentBinlogPosition=, currentRowNumber=0, serverId=0, sourceTime=null, threadId=-1, currentQuery=null, tableIds=[], databaseName=null], but this is no longer available on the server
This happens when the binlog is purged and its no longer available on the server.
There are two solutions to get past this error.
1) Change `snapshot.mode` to `schema.recovery`
2) Delete the contents of the table `offset.storage.jdbc.offset.table.name: "altinity_sink_connector.replica_source_info`. and restart sink connector.

###  A slave with the same server_uuid/server_id as this slave has connected to the master;
https://stackoverflow.com/questions/63523998/multiple-debezium-connector-for-one-mysql-db , As mentioned here the `database.server.id` configuration variable has to be unique if there are multiple connectors connecting to the same database.


###  PostgreSQL - ERROR - Error starting connectorio.debezium.DebeziumException: Creation of replication slot failed; when setting up multiple connectors for the same database host, please make sure to use a distinct replication slot name for each.  
Make sure to add `slot.name` to the configuration(config.yml) and change it to a unique name.

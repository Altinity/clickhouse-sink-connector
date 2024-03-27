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

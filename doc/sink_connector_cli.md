# Sink Connector Lightweight (CLI)

## Design Overview 

![arch](img/sink_connector_cli.drawio.png)

The [CLI application](../sink-connector-client/sink-connector-client) will translate the CLI commands to REST payload messages.

Option in sink connector lightweight to not start automatically unless
the user specifies the **start_replica** flag.(skip-replica-start) in the yaml file.
This will give users option to set the binlog status/position, gtid
**change_replication_source** before starting the replication.

## Operations

1. **Start_replica**, CLI application will send a REST API call to the server to start replication(start Debezium event loop)
2. **Stop_replica**, CLI application will send a REST API call to the server to stop replication(stop Debezium event loop)
3. **change_replication_source**, CLI application will send the gtid, binlog file, and binlog position to the server to change the replication source
 Server will update the table with this information will restart the debezium event loop.
4. **show_replica_status** Return the information from the **replica_status** table.
5. **restart** Restart replication, this is also useful to reload the configuration file from disk.

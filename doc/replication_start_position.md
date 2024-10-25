## Replication Start Position



#### Setting MySQL binlog position/file.

Using the sink connector client(`sink-connector-cli`) you can specify the replication start position.
```
./sink-connector-client change_replication_source --binlog_file binary.074080  
--binlog_position 4 --source_host localhost --source_port 3306 --source_username root --source_password root
```

#### Setting Postgres(LSN) position.
The lsn needs to be converted from hex to number, the part after the slash.

Example: 0/**1A371A0** to **27488672**

```
sink-connector-client lsn -- lsn 27488672
```

Running the above commands will set the replication start position for the sink connector 
in the `source_replica_info` Debezium will use this information to start the replication from the specified position.
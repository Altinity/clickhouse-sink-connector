## Set the binlog position manually(Debezium connector)

As documented in the following link, user might use other methods(like MyDumper) to perform
the initial snapshot or dump of data from MySQL.

After the initial load, debezium can be configured to start streaming from a particular binlog
position.
https://debezium.io/documentation/faq/#how_to_change_the_offsets_of_the_source_database

Note the debezium connector should be stopped before performing the following steps
```
debezium          | 2022-08-27 10:26:32,823 INFO   MySQL|SERVER5432|snapshot  	 using binlog 'mysql-bin.000003' at position '197' and gtid '23a17d59-25f2-11ed-b442-0242ac170006:1-56'   [io.debezium.connector.mysql.MySqlSnapshotChangeEventSource]
```
### Required Parameters.
1. #### binlog file and position
bin_log file: `file`\
bin_log position: `pos` \
(This can be retrieved by running
the 
`show binlog events in mysql-bin.000001` 
command in mysql prompt.)
2. ##### Connector name: 
The name of the debezium connector, this is defined
in the connector configuration JSON.
`        "name": "${CONNECTOR_NAME}",`
[debezium-connector](../deploy/debezium-connector-setup-schema-registry.sh)
`
3. ##### Debezium offset topic.
`offset-storage-topic-debezium`

This topic is created after debezium is started,
using `rpk topic list`
the topic can be identified.

4. ##### Edit the payload to set the above parameters

In this example, we set the binlog file to `mysql-bin.000002`
binlog position to `8592550`
and gtids to `063c62eb-25e1-11ed-b41c-0242ac160004:37`

```
{
"topic": "offset-storage-topic-debezium",
"key": "[\"test-connector\",{\"server\":\"SERVER5432\"}]",
"value": "{\"ts_sec\":1661595997,\"file\":\"mysql-bin.000002\”,\”pos\":8592550,\"gtids\":\"063c62eb-25e1-11ed-b41c-0242ac160004:37\"}",
"timestamp": 1661596039799,
"partition": 22,
"offset": 0
}
```

5. ##### Set the snapshot.mode in the connector.
Set the snapshot.mode to `schema_only_recovery`


6. ##### Use kafkacat to produce the payload(created in step 4) to the offset storage topic
For mac replace kafkacat with kcat.
```
echo '["test-connector",{"server":"SERVER5432"}]|{"ts_sec":1530168950,"file":"mysql-bin.000002","pos":8592550, "server_id": 494}' | \
kafkacat -P -b localhost:19092 -t offset-storage-topic-debezium -K \| -p 22
```

7. ##### Start the connector and monitor the logs.

The logs should mention the binlog file and position.

```
debezium          | 2022-08-28 16:03:02,545 INFO   MySQL|SERVER5432|binlog  Connected to MySQL binlog at mysql-master:3306, starting at MySqlOffsetContext [sourceInfoSchema=Schema{io.debezium.connector.mysql.Source:STRUCT}, sourceInfo=SourceInfo [currentGtid=null, currentBinlogFilename=mysql-bin.000002, currentBinlogPosition=8592550, currentRowNumber=0, serverId=0, sourceTime=2022-08-28T16:03:02.134Z, threadId=-1, currentQuery=null, tableIds=[test.products], databaseName=test], snapshotCompleted=true, transactionContext=TransactionContext [currentTransactionId=null, perTableEventCount={}, totalEventCount=0], restartGtidSet=null, currentGtidSet=null, restartBinlogFilename=mysql-bin.000002, restartBinlogPosition=8592550, restartRowsToSkip=0, restartEventsToSkip=0, currentEventLengthInBytes=0, inTransaction=false, transactionId=null, incrementalSnapshotContext =IncrementalSnapshotContext [windowOpened=false, chunkEndPosition=null, dataCollectionsToSnapshot=[], lastEventKeySent=null, maximumKey=null]]   [io.debezium.connector.mysql.MySqlStreamingChangeEventSource]
debezium          | 2022-08-28 16:03:02,548 INFO   MySQL|SERVER5432|streaming  Waiting for kee
```

```
debezium          | 2022-08-28 16:03:02,105 INFO   ||  18 records sent during previous 00:00:16.413, last recorded offset: {ts_sec=1661702581, file=mysql-bin.000002, pos=8592550, snapshot=true}   [io.debezium.connector.common.BaseSourceTask]
```
https://debezium.io/documentation/faq/#how_to_change_the_offsets_of_the_source_database

```
debezium          | 2022-08-27 10:26:32,823 INFO   MySQL|SERVER5432|snapshot  	 using binlog 'mysql-bin.000003' at position '197' and gtid '23a17d59-25f2-11ed-b442-0242ac170006:1-56'   [io.debezium.connector.mysql.MySqlSnapshotChangeEventSource]
```


```
{
"topic": "offset-storage-topic-debezium",
"key": "[\"test-connector\",{\"server\":\"SERVER5432\"}]",
"value": "{\"ts_sec\":1661595997,\"file\":\"mysql-bin.000003\",\"pos\":197,\"gtids\":\"23a17d59-25f2-11ed-b442-0242ac170006:1-56\"}",
"timestamp": 1661596039799,
"partition": 22,
"offset": 0
}
```

Edit the topic to set the new values\
bin_log file: `file`\
bin_log position: `pos` \
gtid: `gtids`

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

Set the snapshot.mode to `schema_only`
or `when_needed`

```
echo '{  
  "topic": "offset-storage-topic-debezium",
  "key": "[\"test-connector\",{\"server\":\"SERVER5432\"}]",
  "value": "{\"ts_sec\":1661595997,\"file\":\"mysql-bin.000002\”,\”pos\":8592550,\"gtids\":\"063c62eb-25e1-11ed-b41c-0242ac160004:37\"}",
  "timestamp": 1661596039799,
  "partition": 22,
  "offset": 0
}'| kcat -P -b localhost:19092 -t offset-storage-topic-debezium -K\| -p 22
```

# Mutable Data Handling in Sink Connector

## How Updates/Deletes are Handled - ReplacingMergeTree
Sink Connector will attempt to read the `engine_full` column from system.tables for the corresponding table and will 
identify the `engine` and the `ver` column.

### Updates:
For **inserts**, record will be inserted with `sign` set to `1`
For **updates**, a new row will be inserted with a higher **version** value and on merge, clickhouse will drop the row with the older **version** value.
For **deletes**, a new record will be inserted with `sign` set to `-1` and a higher **version** value. Clickhouse will drop the row with the older **version** value. 
A Row policy can be created to hide all the rows with `sign` set to -1.
```
create row policy table on db.table using sign != -1 to all;
```

![](img/replacingmergetree_update_delete.jpg) \

When `optimize table <table_name> final` of `select .. final` is performed and when the merges are performed by
ClickHouse in the background, the initial insert record will be merged along the `before` record.

### Updates on non primary keys: Debezium
Non Primary key updates create a record with operation as 'u'
```
SinkRecord{kafkaOffset=62984, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.sbtest.sbtest1', kafkaPartition=0, key=Struct{id=2317,k=3739}, keySchema=Schema{SERVER5432.sbtest.sbtest1.Key:STRUCT}, value=Struct{before=Struct{id=2317,k=3739,c=20488251985-66135155553-00362235007-72249840112-70784105787-84584360668-65106023418-49140058226-99031281108-48426083028,pad=18846546959-44726413785-66695616247-63594911107-83062207348},after=Struct{id=2317,k=3739,c=20488251985-66135155553-00362235007-72249840112-70784105787-84584360668-65106023418-49140058226-99031281108-48426083029,pad=18846546959-44726413785-66695616247-63594911107-83062207348},source=Struct{version=1.9.2.Final,connector=mysql,name=SERVER5432,ts_ms=1657658606000,snapshot=false,db=sbtest,table=sbtest1,server_id=842,file=mysql-bin.000003,pos=16210729,row=0,thread=22},op=u,ts_ms=1657658606611,transaction=Struct{id=file=mysql-bin.000003,pos=16210580,total_order=1,data_collection_order=1}}, valueSchema=Schema{SERVER5432.sbtest.sbtest1.Envelope:STRUCT}, timestamp=1657658607050, headers=ConnectHeaders(headers=)}

```
### Updates on Primary Key: Debezium

Debezium handles updates on Primary key in the same way as Primary Key changes.

Table Schema:
```
use sbtest;
CREATE TABLE `sbtest1` (
`id` int(11) NOT NULL AUTO_INCREMENT,
`k` int(11) NOT NULL DEFAULT '0',
`c` char(120) NOT NULL DEFAULT '',
`pad` char(60) NOT NULL DEFAULT '',
PRIMARY KEY (`id`,`k`)
)
PARTITION BY RANGE (k) (
PARTITION p1 VALUES LESS THAN (499999),
PARTITION p2 VALUES LESS THAN MAXVALUE
);
````

The following update statement in MySQL, will create 3 Debezium records

`update sbtest.sbtest1 set k=k+1 where id=2317`

Record 1: A Delete record with the old values with key== __debezium.newkey
```
SinkRecord{kafkaOffset=62978, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.sbtest.sbtest1', kafkaPartition=0, key=Struct{id=2317,k=3737},
 keySchema=Schema{SERVER5432.sbtest.sbtest1.Key:STRUCT}, 
 value=Struct{before=Struct{id=2317,k=3737,c=20488251985-66135155553-00362235007-72249840112-70784105787-84584360668-65106023418-49140058226-99031281108-48426083028,
 pad=18846546959-44726413785-66695616247-63594911107-83062207348},source=Struct{version=1.9.2.Final,connector=mysql,name=SERVER5432,ts_ms=1657655632000,
 snapshot=false,db=sbtest,table=sbtest1,server_id=842,file=mysql-bin.000003,pos=16209369,row=0,thread=172},
 op=d,ts_ms=1657655632066,transaction=Struct{id=file=mysql-bin.000003,pos=16209220,total_order=1,data_collection_order=1}}, 
 valueSchema=Schema{SERVER5432.sbtest.sbtest1.Envelope:STRUCT}, timestamp=1657655632487, 
 headers=ConnectHeaders(headers=[ConnectHeader(key=__debezium.newkey, value={id=2317, k=3738}, schema=Schema{MAP})])}
```

Record 2: No Operation: key= __debezium.newkey
```
SinkRecord{kafkaOffset=62979, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.sbtest.sbtest1', kafkaPartition=0, 
key=Struct{id=2317,k=3737}, keySchema=Schema{SERVER5432.sbtest.sbtest1.Key:STRUCT}, value=null, valueSchema=null, 
timestamp=1657655632487, headers=ConnectHeaders(headers=[ConnectHeader(key=__debezium.newkey, value={id=2317, k=3738}, schema=Schema{MAP})])}
```

Record 3: A create record with new values
```
SinkRecord{kafkaOffset=62980, timestampType=CreateTime} ConnectRecord{topic='SERVER5432.sbtest.sbtest1', kafkaPartition=0, 
key=Struct{id=2317,k=3738}, keySchema=Schema{SERVER5432.sbtest.sbtest1.Key:STRUCT}, value=Struct{after=Struct{id=2317,k=3738,
c=20488251985-66135155553-00362235007-72249840112-70784105787-84584360668-65106023418-49140058226-99031281108-48426083028,
pad=18846546959-44726413785-66695616247-63594911107-83062207348},source=Struct{version=1.9.2.Final,connector=mysql,name=SERVER5432,ts_ms=1657655632000,
snapshot=false,db=sbtest,table=sbtest1,server_id=842,file=mysql-bin.000003,pos=16209369,row=0,thread=172},op=c,ts_ms=1657655632066,
transaction=Struct{id=file=mysql-bin.000003,pos=16209220,total_order=2,data_collection_order=2}}, valueSchema=Schema{SERVER5432.sbtest.sbtest1.Envelope:STRUCT}, 
timestamp=1657655632487, headers=ConnectHeaders(headers=[ConnectHeader(key=__debezium.oldkey, value={id=2317, k=3737}, schema=Schema{MAP})])}
````

#### Current behavior
The sink connector performs a delete first of the old values and an insert with the new values, Record 2 is not handled currently.

### Deletes:
For deletes, record will be inserted with `sign` set to `-1`

## ReplacingMergeTree
For updates, only the `after` record will be inserted with `version` set to timestamp in milliseconds.
After merging, ClickHouse will drop the previous insert since the update `version` column value is greater
than the insert record `version` value.

For Deletes, the user provided `replacingmergetree.delete.column` will be set to `-1`


## Other table Engines(MergeTree ...)


   

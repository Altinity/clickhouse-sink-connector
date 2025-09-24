# Version History

To enable persistence of version history, the `replication.history.enable` configuration variable has to be set to true.
When its enabled, the **history** table and the replicated tables are persisted to the database defined in this
configuration variable `replication.history.database`
The history table is defined by the `replication.history.table` config variable.

**History table(Schema)**
```
CREATE TABLE binlog_history.history
(
    `gtid` String,
    `database` String,
    `table` String,
    `ddl` String,
    `before` String,
    `after` String,
    `_raw` String,
    `_time` UInt64,
    `is_deleted` UInt8,
    `operation` String,
    `_version` UInt64,
    `host` String,
    `logfile` String,
    `position` UInt64,
    `primary_host` String
)
ENGINE = MergeTree
PARTITION BY toDate(_time)
ORDER BY gtid
TTL toDate(_time) + toIntervalDay(30)
SETTINGS index_granularity = 8192
```

**History table(DML)**
```
gtid:         2290064
database:     sbtest
table:        embeddedconnector.sbtest.sbtest1
ddl:          
before:       
after:        [{"name":"id","index":0,"schema":{"type":"INT32","optional":false},"value":1},{"name":"k","index":1,"schema":{"type":"INT32","optional":false},"value":50},{"name":"c","index":2,"schema":{"type":"STRING","optional":false},"value":"31451373586-15688153734-79729593694-96509299839-83724898275-86711833539-78981337422-35049690573-51724173961-87474696253"},{"name":"pad","index":3,"schema":{"type":"STRING","optional":false},"value":"98996621624-36689827414-04092488557-09587706818-65008859162"}]
_raw:         {"key":{"id":1},"value":{"op":"c","before":null,"ts_us":1757199874527207,"after":{"pad":"98996621624-36689827414-04092488557-09587706818-65008859162","c":"31451373586-15688153734-79729593694-96509299839-83724898275-86711833539-78981337422-35049690573-51724173961-87474696253","id":1,"k":50},"source":{"ts_us":1757111993608980,"query":null,"thread":27172,"server_id":940,"version":"3.1.3.Final","sequence":null,"file":"mysql-bin.000004","connector":"mysql","pos":23226335,"name":"embeddedconnector","gtid":"ed8a2f96-8919-11f0-b8c4-8e913c21687b:2290064","row":0,"ts_ns":1757111993608980000,"ts_ms":1757111993608,"snapshot":"false","db":"sbtest","table":"sbtest1"},"ts_ns":1757199874527207770,"transaction":null,"ts_ms":1757199874527},"topic":"embeddedconnector.sbtest.sbtest1","sourceOffset":{"ts_sec":1757111993,"file":"mysql-bin.000004","pos":23226191,"gtids":"ed8a2f96-8919-11f0-b8c4-8e913c21687b:1-2290063","row":1,"server_id":940,"event":2},"sourcePartition":{"server":"embeddedconnector"}}
_time:        1757111993608
is_deleted:   0
operation:    CREATE
_version:     0
host:         940
logfile:      mysql-bin.000004
position:     23226335
primary_host: 940
```

**History table(DDL)**
```
gtid:         2316537
database:     sbtest
table:        
ddl:          alter table sbtest1 add column o varchar(100)
before:       
after:        
_raw:         
_time:        1757216371080
is_deleted:   0
operation:    
_version:     0
host:         940
logfile:      mysql-bin.000004
position:     35924824
primary_host: 940
```
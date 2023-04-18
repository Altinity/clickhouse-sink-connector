
### Configuration
**Configuration**
```
export database.hostname="mysql-master"
export database.port="3306"
export database.user="root"
export database.password="root"
export database.include.list=sbtest
#export table.include.list=sbtest1
export database.allowPublicKeyRetrieval"="true"
export snapshot.mode="schema_only"
export connector.class="io.debezium.connector.mysql.MySqlConnector"
export clickhouse.server.database="test"
export auto.create.tables=true
export replacingmergetree.delete.column="_sign"
export metrics.port=8083
export clickhouse.server.url="clickhouse"
export clickhouse.server.user="root"
export clickhouse.server.pass="root"
export clickhouse.server.port="8123"

```

In `deploy\docker.env`, Update **MySQL** and **ClickHouse** configuration.


**MySQL**
```
export database.hostname="localhost"
export database.port="3306"
export database.user="root"
export database.password="root"
export database.whitelist="test"
```
**ClickHouse**
```
export clickhouse.server.url="clickhouse"
export clickhouse.server.user="root"
export clickhouse.server.pass="root"
export clickhouse.server.port="8123"
```

**PostgreSQL**
```

export database.hostname="postgres"
export database.port="5432"
export database.user="root"
export database.password="root"
export snapshot.mode="schema_only"
export connector.class="io.debezium.connector.postgresql.PostgresConnector"
export plugin.name="pgoutput"
export table.include.list="public.tm"
export clickhouse.server.database="test"
export auto.create.tables=true
export replacingmergetree.delete.column="_sign"
export metrics.port=8083
export clickhouse.server.url="clickhouse"
export clickhouse.server.user="root"
export clickhouse.server.pass="root"
export clickhouse.server.port="8123"
```

PostgreSQL specific configuration:
```
export connector.class="io.debezium.connector.postgresql.PostgresConnector"
export database.dbname="public"
export plugin.name="pgoutput"
```

#### Helm Charts
Update the MySQL/PostgreSQL and ClickHouse configuration values 
`
```
  - name: database.hostname
    value: "127.0.0.1"
  - name: database.port
    value: "3306"
  - name: database.dbname
    value: "public"
  - name: database.user
    value: "root"
  - name: database.password
    value: "adminpass"
  - name: snapshot.mode
    value: "initial"
  - name: connector.class
    value: "io.debezium.connector.postgresql.PostgresConnector"
  - name: plugin.name
    value: "pgoutput"
  - name: table.include.list
    value: "public.tm"
  - name: offset.storage
    value: "org.apache.kafka.connect.storage.FileOffsetBackingStore"
  - name: offset.storage.file.filename
    value: "filename"
  - name: offset.flush.interval.ms
    value: "60000"
  - name: auto.create.tables
    value: "true"
  - name: clickhouse.server.url
    value: "localhost"
  - name: clickhouse.server.port
    value: "8123"
  - name: clickhouse.server.user
    value: "default"
  - name: clickhouse.server.pass
    value: "2"
  - name: clickhouse.server.database
    value: "public"
  - name: replacingmergetree.delete.column
    value: "_sign"
  - name: database.allowPublicKeyRetrieval
    value: "true"
  - name: metrics.enable
    value: "true"
  - name: metrics.port
    value: "8083"
```


```
cd helm
helm install clickhouse-debezium-embedded .

```
### Getting started


**Start application**
```
cd docker
docker-compose up
```

### Building from sources
Build the JAR file
`mvn clean install`



#### DDL Support:
With Datatype conversion(From MySQL to ClickHouse)

ALTER TABLE

| MySQL                                                  | ClickHouse                                                      |
|--------------------------------------------------------|-----------------------------------------------------------------|
| ADD COLUMN                                             |                                                                 |
| ADD COLUMN NULL/NOT NULL                               |                                                                 |
| ADD COLUMN DEFAULT                                     |                                                                 |
| ADD COLUMN FIRST, AFTER                                |                                                                 |
| DROP COLUMN                                            |                                                                 |
| MODIFY COLUMN data_type                                |                                                                 |
| MODIFY COLUMN data_type NULL/NOT NULL                  |                                                                 |
| MODIFY COLUMN data_type DEFAULT                        |                                                                 |
| MODIFY COLUMN FIRST, AFTER                             |                                                                 |
| MODIFY COLUMN old_name new_name datatype NULL/NOT NULL |                                                                 |
| RENAME COLUMN col1 to col2                             |                                                                 |
| CHANGE COLUMN FIRST, AFTER                             | MODIFY COLUMN                                                   |
| ALTER COLUMN col_name ADD DEFAULT                      | Not supported by grammar                                        |
| ALTER COLUMN col_name ADD DROP DEFAULT                 | Not supported by grammar                                        |
| ADD PRIMARY KEY                                        | Cannot modify primary key in CH                                 |

###  Not supported:

| MySQL                                                  | ClickHouse                                                      |
|--------------------------------------------------------|-----------------------------------------------------------------|
| ADD INDEX                                              | Secondary indexes in CH, what about type and index granularity? |
| ADD CONSTRAINT  (CHECK)                                |                                                                 |
| ADD CONSTRAINT                                         | Add constraint with Primary key(Not supported)                  |
| DROP CONSTRAINT                                        | Add constraint with Primary key(Not supported)                  |



## TABLE operations
| MySQL                                    | ClickHouse                          |
|------------------------------------------|-------------------------------------|
| RENAME TABLE name_1 to name_2            |                                     |
| TRUNCATE TABLE                           |                                     |
| DROP TABLE name_1                        |                                     |
| DROP TABLE name_1, name_2                |                                     |
| ALTER TABLE table_name to new_table_name | RENAME table_name to new_table_name |

## DATABASE operations
| MySQL           | ClickHouse |
|-----------------|------------|
| CREATE DATABASE |            |
| USE DATABASE    |            |

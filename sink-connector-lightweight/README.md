### Docker compose

### Getting started
Configuration(yaml) file mounted as volume
```
      - ./config.yml:/config.yml
```

**Configuration(MySQL)**
`(sink-connector-lightweight/docker/config.yml)`

Start the docker container
A Sample docker-compose is provided , it starts the docker container \
`registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:latest`
```
cd sink-connector-lightweight
cd docker
./start-docker-compose.sh
```

###  Getting Started (PostgreSQL)

`(sink-connector-lightweight/docker/config_postgres.yml)` 

**Configuration**
```
export database.hostname="postgres"
export database.port="5432"
export database.user="root"
export database.password="root"
export snapshot.mode="schema_only"
export clickhouse.server.url="clickhouse"
export clickhouse.server.user="root"
export clickhouse.server.password="root"
export clickhouse.server.port="8123"
export connector.class="io.debezium.connector.postgresql.PostgresConnector"
export plugin.name="pgoutput"
export table.include.list="public.tm"
export clickhouse.server.database="test"
export auto.create.tables=true
export replacingmergetree.delete.column="_sign"
export metrics.port=8083

```

After the environment variables are set, start the docker container
```
cd sink-connector-lightweight
cd docker
docker-compose up -f docker-compose-postgres.yml
```

###  Getting Started (MongoDB)
`(sink-connector-lightweight/docker/docker_mongo.env)`

**Configuration**
```
export mongodb.connection.string="mongodb://localhost:49252"
export mongodb.members.auto.discover="true"
export topic.prefix="mongo-ch"
export collection.include.list="project.items"
export snapshot.include.collection.list="project.items"
export database.include.list="project"
export database.dbname="project"
export database.user="project"
export database.password="project"
export snapshot.mode="initial"
export connector.class="io.debezium.connector.mongodb.MongoDbConnector"
export offset.storage="org.apache.kafka.connect.storage.FileOffsetBackingStore"
export offset.flush.interval.ms="60000"
export auto.create.tables="true"
export clickhouse.server.url="clickhouse"
export clickhouse.server.port="8123"
export clickhouse.server.user="root"
export clickhouse.server.password="root"
export clickhouse.server.database="project"
export replacingmergetree.delete.column="_sign"
export metrics.port="8087"
export database.allowPublicKeyRetrieval="true"
```

After the environment variables are set, start the docker container
```
cd sink-connector-lightweight
cd docker
docker-compose up -f docker-compose-mongo.yml
```

###  Getting Started (Helm Charts)
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
  - name: clickhouse.server.password
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


## TABLE operations
| MySQL                                    | ClickHouse                          |
|------------------------------------------|-------------------------------------|
| RENAME TABLE name_1 to name_2            |                                     |
| TRUNCATE TABLE                           |                                     |
| DROP TABLE name_1                        |                                     |
| DROP TABLE name_1, name_2                |                                     |
| ALTER TABLE table_name to new_table_name | RENAME table_name to new_table_name |
| CREATE TABLE PARTITION BY KEY(col1)      | PARTITION BY col1                   |
| CREATE TABLE PARTITION BY RANGE(col1,col2, col3) | PARTITION BY col1, col2, col3|



## DATABASE operations
| MySQL           | ClickHouse |
|-----------------|------------|
| CREATE DATABASE |            |
| USE DATABASE    |            |


###  Not supported:

| MySQL                                                  | ClickHouse                                                      |
|--------------------------------------------------------|-----------------------------------------------------------------|
| ADD INDEX                                              | Secondary indexes in CH, what about type and index granularity? |
| ADD CONSTRAINT  (CHECK)                                |                                                                 |
| ADD CONSTRAINT                                         | Add constraint with Primary key(Not supported)                  |
| DROP CONSTRAINT                                        | Add constraint with Primary key(Not supported)                  |

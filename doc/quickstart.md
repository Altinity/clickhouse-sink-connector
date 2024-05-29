# Sink Connector QuickStart Guide

Use [Docker Compose](https://docs.docker.com/compose/) to bring 
up a complete configuration that illustrates operation of 
Altinity Sink Connector.

# QuickStart Installation for Lightweight Sink Connector

This is the recommended path for initial use. It uses MySQL as the
source database and brings up a full stack with MySQL, ClickHouse, 
Lightweight Sink Connector, and Grafana. Example shown here is for
Ubuntu but will work for any Linux or MacOS provided prerequisites
are installed. 

## Prerequisites

Install Docker and Docker Compose.

* [Docker Installation](https://docs.docker.com/engine/install/) 
* [Docker Compose Installation](https://docs.docker.com/compose/) 

Install MySQL client and ClickHouse client. 
```
sudo apt update
sudo apt install mysql-client
sudo apt install clickhouse-client
```

## Start the stack 

Use Docker Compose to start containers. 
```
cd sink-connector-lightweight/docker
export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE=altinity/clickhouse-sink-connector:2.1.0-lt
docker compose -f docker-compose-mysql.yml up --renew-anon-volumes
```

## Test replication 

The installed configuration will replicate any table added to MySQL
database sysbench to ClickHouse table test.

Login to MySQL, substituting your host name. 
```
mysql --user=root --password=root --host=<your host>
```

Enter SQL commands to Create a table in database sysbench and add data. 
```
mysql> use test
mysql> CREATE TABLE foo (id int PRIMARY KEY, value VARCHAR(100));
Query OK, 0 rows affected (0.12 sec)
mysql> INSERT INTO foo VALUES (1, '25');
Query OK, 1 row affected (0.02 sec)
```

Login to ClickHouse, substituting your host name. 
```
clickhouse-client --user=root --password=root
```

Confirm that ClickHouse has the table in database test and that it 
contains the data you entered. 
```
d69c9d8993cb :) use test
Ok.
d69c9d8993cb :) select * from foo
SELECT *
FROM foo

┌─id─┬─value─┬─_sign─┬────────────_version─┐
│  1 │ 25    │     1 │ 1731383644255158333 │
└────┴───────┴───────┴─────────────────────┘
```

## Stop the stack

Stop all components. 
```
docker compose -f docker-compose-mysql.yml down
```

### Connecting to External MySQL/ClickHouse

**Step 1:** Update **MySQL** information in config.yaml(https://github.com/Altinity/clickhouse-sink-connector/blob/develop/sink-connector-lightweight/docker/config.yml
):
```
   database.hostname: <MySQL Hostname>
   database.port: <MySQL Port>
   database.user: <MySQL username>
   database.password: <MySQL password>
```
**Step 2:** Update **ClickHouse** information in config.yaml: 
```
    clickhouse.server.url: <ClickHouse hostname>
    clickhouse.server.user: <ClickHouse username>
    clickhouse.server.password: <ClickHouse password>
    clickhouse.server.port. <ClickHouse port>
```
**Step 3:** Update **Offset storage/Schema History** to be stored in **ClickHouse**:
```
    offset.storage.jdbc.url: "jdbc:clickhouse://<ClickHouse hostname>:<ClickHouse port>/altinity_sink_connector"
    schema.history.internal.jdbc.url: "jdbc:clickhouse://<ClickHouse hostname>:<ClickHouse port>/altinity_sink_connector"
    
    offset.storage.jdbc.user: <ClickHouse username>
    offset.storage.jdbc.password: <ClickHouse password>
    
    schema.history.internal.jdbc.user: <ClickHouse username>
    schema.history.internal.jdbc.password: <ClickHouse password>
```
**Step 4:** Update **MySQL databases** to be replicated:
```
    database.include.list: <Database name>
```

**Step 5:** Add **table filters** to include/exclude tables to be replicated:
```
    table.include.list: <Table names>
    table.exclude.list: <Table names>
```
**Step 6:** Configure **Snapshot Mode** to define initial load vs CDC replication:
```
    # Initial load(transfer all existing data in MySQL)
    snapshot.mode: initial
    
    or
    
    # CDC replication(transfer only new data in MySQL)
    snapshot.mode: schema_only
```
**Note: ClickHouse Secure(Altinity Cloud/ClickHouse Cloud)**:
Set the sever url to `https` and add `?ssl=true` to the end of the url.
```
clickhouse.server.url: "https://cloud_url"
offset.storage.jdbc.url: "jdbc:clickhouse://cloud_url:8443/altinity_sink_connector?ssl=true"
schema.history.internal.jdbc.url: "jdbc:clickhouse://cloud_url:8443/altinity_sink_connector?ssl=true"
```

## References:
1. [Sink Connector Configuration ](configuration.md)
2. [MySQL Topologies supported](https://debezium.io/documentation/reference/2.5/connectors/mysql.html#setting-up-mysql)
3. [MySQL Setup](https://debezium.io/documentation/reference/2.5/connectors/mysql.html#setting-up-mysql)

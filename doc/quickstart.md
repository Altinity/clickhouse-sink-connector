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
export SINK_LIGHTWEIGHT_VERSION=latest
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
mysql> use sbtest
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

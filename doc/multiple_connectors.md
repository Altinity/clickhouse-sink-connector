# Start the first connector with the usual configuration.

```
./getLatestRelease.sh
```
Set the sink connector version.
```
export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE=altinity/clickhouse-sink-connector:2.5.0-lt
```

Start the connector. (This will start the sink connector, MySQL and ClickHouse.)
The name of the first connector is the service name `clickhouse-sink-connector-lt`
```
docker-compose up
```

# Start the second connector

Note: The second connector docker service (clickhouse-sink-connector-lt-2) is setup to use
a different configuration file(config-2.yml) where the following configuration variables are different.
```
name: "company-2"
database.server.id: "22323"
database.server.id: "22323"
```

This will start the second connector (docker service) with the configuration file `config-2.yml`.
Also note the port number for the second connector is different.
```
docker-compose -f clickhouse-sink-connector-lt-service-2.yml create clickhouse-sink-connector-lt-2
docker-compose -f clickhouse-sink-connector-lt-service-2.yml start clickhouse-sink-connector-lt-2
```

Check the docker ps to verify that the second connector is running.
```
docker ps
CONTAINER ID   IMAGE                                         COMMAND                  CREATED             STATUS                       PORTS                                                                                                                                                                            NAMES
3c55644dddac   altinity/clickhouse-sink-connector:2.5.0-lt   "sh -c 'java -agentl…"   About an hour ago   Up About an hour             0.0.0.0:5006->5005/tcp, :::5006->5005/tcp, 0.0.0.0:7009->7000/tcp, :::7009->7000/tcp, 0.0.0.0:8089->8083/tcp, :::8089->8083/tcp                                                  docker_clickhouse-sink-connector-lt-2_1
d16b13ee6c27   docker_grafana                                "/run.sh /run.sh"        About an hour ago   Up About an hour             0.0.0.0:3000->3000/tcp, :::3000->3000/tcp                                                                                                                                        docker_grafana_1
c11525b3af94   altinity/clickhouse-sink-connector:2.5.0-lt   "sh -c 'java -agentl…"   About an hour ago   Up About an hour             0.0.0.0:5005->5005/tcp, :::5005->5005/tcp, 0.0.0.0:8083->8083/tcp, :::8083->8083/tcp, 0.0.0.0:39999->39999/tcp, :::39999->39999/tcp, 0.0.0.0:7001->7000/tcp, :::7001->7000/tcp   docker_clickhouse-sink-connector-lt_1

```

Verify the offsets storage values in ClickHouse(To validate that they are not overwritten by the second connector)
```
docker exec -it clickhouse bash
root@ec3bece132cc:/# clickhouse-client 
ClickHouse client version 23.8.5.16 (official build).
Connecting to localhost:9000 as user root.
Connected to ClickHouse server version 23.8.5 revision 54465.

ec3bece132cc :) use altinity_sink_connector

USE altinity_sink_connector

SELECT *
FROM replica_source_info
FINAL

Query id: 4a92b294-16d8-41ba-9f42-b95928debaf0

┌─id───────────────────────────────────┬─offset_key──────────────────────────────────┬─offset_val────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┬────record_insert_ts─┬─record_insert_seq─┐
│ a952b7c7-bfd2-4533-9dfb-d4f0778c7a22 │ ["company-1",{"server":"sink-connector-1"}] │ {"ts_sec":1734449681,"file":"mysql-bin.000003","pos":197,"gtids":"a7123a4c-bcbe-11ef-9f5c-0242ac180004:1-56","snapshot":true} │ 2024-12-17 15:37:04 │               484 │
│ cf37949f-47d1-4b17-aaa4-1b2236bb384a │ ["company-2",{"server":"sink-connector-2"}] │ {"ts_sec":1734449823,"file":"mysql-bin.000003","pos":197,"gtids":"a7123a4c-bcbe-11ef-9f5c-0242ac180004:1-56","snapshot":true} │ 2024-12-17 15:37:04 │               483 │
└──────────────────────────────────────┴─────────────────────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┴─────────────────────┴───────────────────┘

2 rows in set. Elapsed: 0.001 sec. 

```
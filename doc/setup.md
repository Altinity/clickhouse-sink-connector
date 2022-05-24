This doc describes how to set up CDC pipeline

# Pipeline

![pipeline](img/pipeline.png)

# Setup local pipeline

## Pre-requisites
Sink connector image needs to be built locally.
Use the following script to build the image
`docker/package-build-sink-on-debezium-base.sh`
[docker/package-build-sink-on-debezium-base.sh](../docker/package-build-sink-on-debezium-base.sh)
Future: Github releases will push docker images to Docker hub.

## docker-compose
Full pipeline can be launched via docker-compose with the help of [docker-compose.yaml][docker-compose.yaml]
It will start:
1. MySQL
2. Zookeeper
3. Debezium MySQL connector
4. RedPanda
5. clickhouse-kafka-sink-connector
6. Clickhouse

```bash
cd deploy/docker
docker-compose up
```

# Source connector
After all the docker containers are up and running, execute the following command
to create the Debezium MySQL connector.
Make sure MySQL master/slave is up and running before executing the following script.
[debezium-connector-setup-schema-registry.sh](../deploy/debezium-connector-setup-schema-registry.sh)

# Sink Connector
After the source connector is created, 
execute the script [sink-connector-setup-schema-registry.sh](../deploy/sink-connector-setup-schema-registry.sh)
to create the Clickhouse Sink connector using Kafka connect REST API

# Deleting connectors
The source connector can be deleted using the following script
[debezium-delete.sh](../deploy/debezium-delete.sh)

The sink connector can be deleted using the following script
[sink-delete.sh](../deploy/sink-delete.sh)

# References
Kafka Connect REST API - (https://docs.confluent.io/platform/current/connect/references/restapi.html)

[docker-compose.yaml]: ../deploy/docker/docker-compose.yaml
[Dockerfile]: ../docker/Dockerfile


# Topic Partitions.
By Default the kafka topic is created with number of partitions set to 1.
For better throughput and High availability, its better to set to the partitions
to a number greater than 1.
The topic partitions must be created before the sink connector is started.
For redpanda:
```
rpk topic create SERVER5432.test.employees -p 3
```

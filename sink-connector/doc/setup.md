This doc describes how to set up CDC pipeline

# Pipeline

![pipeline](img/pipeline.png)

# Setup local pipeline

## Pre-requisites
- Java JDK 11 (https://openjdk.java.net/projects/jdk/11/)
- Maven (mvn) (https://maven.apache.org/download.cgi)
- Docker and Docker-compose

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
7. Confluent Schema registry or Apicurio Schema registry

The `start-docker-compose.sh` by default uses the `latest` tag, you could also pass the docker tag to the script.
Altinity sink images are tagged on every successful build with the following format(yyyy-mm-dd) Example(2022-07-19)

### MySQL:
```bash
cd deploy/docker
./start-docker-compose.sh 
```

### Postgres using Confluent Schema Registry:
```bash
export SINK_VERSION=latest
cd deploy/docker
docker-compose -f docker-compose.yaml -f docker-compose-postgresql.override.yaml up
```

### Postgres using Apicurio Schema Registry:
```bash
export SINK_VERSION=latest
cd deploy/docker
docker-compose -f docker-compose.yaml -f docker-compose-postgresql-apicurio-schema-registry.override.yaml up
```

### Start Docker-compose with a specific docker tag
```bash
cd deploy/docker
./start-docker-compose.sh 2022-07-19
```

### Load Airline data set
```bash
docker-compose -f docker-compose.yaml -f docker-compose-airline-data.override.yaml up
```
### Stop Docker-compose
```bash
cd deploy/docker
./stop-docker-compose.sh
```
# Source connector
After all the docker containers are up and running, execute the following command
to create the Debezium MySQL connector.

Make sure MySQL master/slave is up and running before executing the following script.\

### MySQL:
```bash
    ../deploy/debezium-connector-setup-schema-registry.sh
```
[debezium-connector-setup-schema-registry.sh](../deploy/debezium-connector-setup-schema-registry.sh)

### Postgres(Using Apicurio):
```bash
../deploy/debezium-connector-setup-schema-registry.sh postgres apicurio
```

# Sink Connector
After the source connector is created, 
execute the script [sink-connector-setup-schema-registry.sh](../deploy/sink-connector-setup-schema-registry.sh)
to create the Clickhouse Sink connector using Kafka connect REST API

### MySQL:
```bash
    ../deploy/sink-connector-setup-schema-registry.sh
```
### Postgres(Using Apicurio):
```bash
../deploy/sink-connector-setup-schema-registry.sh postgres apicurio
```

# Deleting connectors
The source connector can be deleted using the following script
[debezium-delete.sh](../deploy/debezium-delete.sh)

The sink connector can be deleted using the following script
[sink-delete.sh](../deploy/sink-delete.sh)

# References
Kafka Connect REST API - (https://docs.confluent.io/platform/current/connect/references/restapi.html)

[docker-compose.yaml]: ../deploy/docker/docker-compose-apicurio-schema-registry.override.yaml
[Dockerfile]: ../docker/Dockerfile-sink-on-debezium-base-image

## Connecting to a different MySQL instance(Host)

Add the following line to the `debezium` service in `docker-compose.yml`,
so that debezium is able to access host.
```
extra_hosts:
- "host.docker.internal:host-gateway"
```
Make sure the mysqld.conf is modified with `bind-address` set to `0.0.0.0`

Modify debezium configuration `debezium-connector-setup-schema-registry.sh` 
to set the MySQL host.
```
            "database.hostname": "host.docker.internal",
```

Grant access in MySQL server for debezium host

Caution about the security risks about WITH GRANT OPTION, refer manual
```
mysql> CREATE USER 'root'@'%' IDENTIFIED BY 'PASSWORD';
mysql> GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION;
mysql> FLUSH PRIVILEGES;
```
# Topic Partitions.
By Default the kafka topic is created with number of partitions set to 1.
For better throughput and High availability, its better to set to the partitions
to a number greater than 1.
The topic partitions must be created before the sink connector is started.
For redpanda:
```
rpk topic create SERVER5432.test.employees -p 3
```

This doc describes how to set up CDC pipeline

# Pipeline

![pipeline](img/pipeline.png)

# Setup local pipeline

## docker-compose
Full pipeline can be launched via docker-compose with the help of [docker-compose.yaml][docker-compose.yaml]
It will start:
1. MySQL
2. Zookeeper
3. Debezium MySQL connector
4. RedPanda
5. clickhouse-kafka-sink-connector
6. Clickhouse

In order to launch `clickhouse-kafka-sink-connector` appropariate docker image is required, 
which can be built as described in [Image](#Image)  
```bash
cd deploy/docker
docker-compose up
```

# Image
Docker image can be created with provided [Dockerfile][Dockerfile] and build script

# Topic Partitions.
By Default the kafka topic is created with number of partitions set to 1.
For better throughput and High availability, its better to set to the partitions
to a number greater than 1.
The topic partitions must be created before the sink connector is started.
```
rpk topic create SERVER5432.test.employees -p 3
```

ToDO: Create Kafka connector image with Mysql
Create JAR file by running the following command and copy to the /libs directory of Kafka. 

` mvn install`

Copy MYSQL libs from container/libs to the kafka libs directory.

Start the Kafka connect process and pass the properties file
for both MYSQL and Clickhouse properties.

`./connect-standalone.sh ../config/connect-standalone.properties 
../../kafka-connect-clickhouse/kcch-connector/src/main/config/mysql-debezium.properties 
../../kafka-connect-clickhouse/kcch-connector/src/main/config/clickhouse-sink.properties`

[docker-compose.yaml]: ../deploy/docker/docker-compose.yaml
[Dockerfile]: ../docker/Dockerfile

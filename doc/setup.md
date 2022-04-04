# Pipeline

![pipeline](img/pipeline.png)

For local setup, run Docker compose in docker
directory. It will start
- MySQL
- RedPanda(Kafka)
- Clickhouse

ToDO: Create Kafka connector image with Mysql

` cd docker`

`docker-compose up`

Create JAR file by running the following command and copy to the /libs directory of Kafka. 

` mvn install`

Copy MYSQL libs from container/libs to the kafka libs directory.

Start the Kafka connect process and pass the properties file
for both MYSQL and Clickhouse properties.

`./connect-standalone.sh ../config/connect-standalone.properties 
../../kafka-connect-clickhouse/kcch-connector/src/main/config/mysql-debezium.properties 
../../kafka-connect-clickhouse/kcch-connector/src/main/config/clickhouse-sink.properties`
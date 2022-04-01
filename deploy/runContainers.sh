#!/bin/zsh

docker rm zookeeper
docker rm kafka
docker rm connect
docker rm mysql

docker run --detach --name zookeeper zookeeper &
sleep 5
docker run --detach --name kafka -p 8081:8081 -p 8082:8082 -p 9091:9091 -p 9092:9092 vectorized/redpanda &
sleep 5
docker run --detach -it --name connect \
    -p 8083:8083 \
    -e BOOSTRAP_SERVERS=localhost:9092 \
    -e GROUP_ID=1 \
    -e CONFIG_STORAGE_TOPIC=my-connect-configs \
    -e OFFSET_STORAGE_TOPIC=my-connect-offsets \
    -e ADVERTISED_HOST_NAME=$(echo $DOCKER_HOST | cut -f3  -d'/' | cut -f1 -d':') \
    --link zookeeper:zookeeper \
    --link kafka:kafka \
    debezium/connect
sleep 5

# Activating binlog, using mariadb since mysql images are not supported in
docker run --detach --name mysql \
    -p 3306:3306 \
    --env MARIADB_USER=user \
    --env MARIADB_PASSWORD=secret \
    --env MARIADB_ROOT_PASSWORD=root \
    arm64v8/mariadb:latest \
    mysqld \
    --datadir=/var/lib/mysql \
    --user=mysql \
    --server-id=1 \
    --log-bin=/var/lib/mysql/mysql-bin.log \
    --binlog_do_db=test

# Set this in mysql prompt
# set global binlog_format = ROW;

# Run clickhouse local docker image
docker run -d -p8123:8123 --name clickhouse --ulimit nofile=262144:262144 yandex/clickhouse-server

sleep 5


# Start kafka connect standalone
# copy containers/libs jar files to $KAFKA_CONNECT_ROOT/libs folder.
./connect-standalone.sh \
    ../config/connect-standalone.properties \
    ../../GITHUB/kafka-connect-clickhouse/kcch-connector/src/main/config/mysql-debezium.properties \
    ../../GITHUB/kafka-connect-clickhouse/kcch-connector/src/main/config/clickhouse-sink.properties

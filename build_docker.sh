#!/bin/bash

cd sink-connector
mvn clean install

cd ..
cd sink-connector-lightweight
mvn clean install -DskipTests=true
today_date=$(date +%F)

cd ..
cd sink-connector-client
CGO_ENABLED=0 go build

cd ..

docker login registry.gitlab.com
docker build -f sink-connector-lightweight/Dockerfile -t clickhouse_debezium_embedded:${today_date} . --no-cache
docker tag clickhouse_debezium_embedded:${today_date} registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:${today_date}
docker push registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:${today_date}

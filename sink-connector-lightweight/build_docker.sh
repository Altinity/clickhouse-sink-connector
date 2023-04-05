#!/bin/bash

docker login registry.gitlab.com
docker build . -t clickhouse_debezium_embedded
docker tag clickhouse_debezium_embedded registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded
docker push registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded

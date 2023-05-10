#!/bin/bash

today_date=$(date +%F)

docker login registry.gitlab.com
docker build . -t clickhouse_debezium_embedded:${today_date} --no-cache
docker tag clickhouse_debezium_embedded:${today_date} registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:${today_date}
docker push registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:${today_date}

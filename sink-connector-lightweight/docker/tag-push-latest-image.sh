#!/bin/bash
set -x


today_date=$(date +%F)

echo 'Tagging ${today_date} as latest'

docker tag registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:${today_date} registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:latest
docker push registry.gitlab.com/altinity-public/container-images/clickhouse_debezium_embedded:latest
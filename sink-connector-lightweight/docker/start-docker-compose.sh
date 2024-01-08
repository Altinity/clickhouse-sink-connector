#!/bin/bash


if [ -z $1 ]
then
  echo 'Using the latest tag for Sink connector'
  export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE='altinityinfra/clickhouse-sink-connector:408-97b1d3d83ef93c1b76a2b1c4d9c544dc67fbbec3'
else
  export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE=$1
fi

./stop-docker-compose.sh
# Altinity sink images are tagged daily with this tag yyyy-mm-dd(2022-07-19)

docker-compose -f docker-compose-mysql.yml up --remove-orphans --force-recreate --renew-anon-volumes

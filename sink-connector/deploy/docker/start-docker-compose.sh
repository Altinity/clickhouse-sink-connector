#!/bin/bash


if [ -z $1 ]
then
  echo 'Using the latest tag for Sink connector'
  export SINK_VERSION='latest'
else
  export SINK_VERSION=$1
fi

./stop-docker-compose.sh
# Altinity sink images are tagged daily with this tag yyyy-mm-dd(2022-07-19)

docker-compose up  --remove-orphans --force-recreate --renew-anon-volumes

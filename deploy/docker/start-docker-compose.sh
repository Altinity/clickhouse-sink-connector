#!/bin/bash

# Altinity sink images are tagged daily with this tag yyyy-mm-dd(2022-07-19)

if [ -z $1 ]
then
  echo 'Using the latest tag for Sink connector'
  export SINK_VERSION='latest'
else
  export SINK_VERSION=$1
fi

docker-compose up


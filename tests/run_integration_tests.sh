#!/bin/bash

today_date=$(date +%F)

cd ../deploy/docker
./start-docker-compose.sh 2022-10-17  &

sleep 30

cd ..
echo "Setting up DATATYPES database"
./configure_datatypes.sh > /dev/null
sleep 15
echo "Setting up Sakila database"
./configure_sakila.sh
sleep 15

cd ../tests
echo "Compare DATATYPES MySQL/CH checksum"
./diff_datatypes_data.sh
sleep 10
echo "Compare Sakila MySQL/CH checksum"
./diff_sakila_data.sh

sleep 5

cd ../deploy/docker
./stop-docker-compose.sh > /dev/null

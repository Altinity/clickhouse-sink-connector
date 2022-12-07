#!/bin/bash
today_date=$(date +%F)

if [ -z $1 ]; then
  TAG=$today_date
else
  TAG=$1
fi



cd ../deploy/docker
./start-docker-compose.sh $TAG &

sleep 30

cd ..
echo "Setting up DATATYPES database"
./configure_datatypes.sh > /dev/null
sleep 15

#echo "Setting up Sakila database"
#./configure_sakila.sh
#sleep 15
cd ../python
pip install -r requirements.txt > /dev/null

cd ../tests
echo "Compare DATATYPES MySQL/CH checksum"
./diff_datatypes_data.sh
sleep 10

cd ../deploy
echo "Removing Datatypes debezium/sink connector"
#./debezium-delete.sh datatypes
./sink-delete.sh datatypes

#cd docker
#docker-compose stop debezium
#docker-compose stop sink
#sleep 15
#
#docker-compose start debezium
#docker-compose start sink

cd ..
echo "Setting up Menagerie database"
./configure_menagerie.sh
sleep 15

cd ../tests
echo "Compare MENAGERIE MySQL/CH checksum"
./diff_menagerie_data.sh
sleep 10
#echo "Compare Sakila MySQL/CH checksum"
#./diff_sakila_data.sh

sleep 5

cd ../deploy/docker
#./stop-docker-compose.sh > /dev/null

exit 0

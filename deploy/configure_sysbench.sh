DATABASE=sbtest
./debezium-delete.sh  $DATABASE &&  ./debezium-connector-setup-database.sh $DATABASE

docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"

sleep 5
./sink-delete.sh  $DATABASE && ./sink-connector-setup-database.sh $DATABASE


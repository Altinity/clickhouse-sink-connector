DATABASE=datatypes
./debezium-delete.sh $DATABASE &&  ./debezium-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"

docker cp  ../tests/data_types.sql mysql-master:/tmp
docker exec -it mysql-master mysql -uroot -proot -e "DROP DATABASE IF EXISTS $DATABASE;CREATE DATABASE $DATABASE;"

docker exec -it mysql-master mysql -uroot -proot -e "use $DATABASE;source /tmp/data_types.sql;"

sleep 5
./sink-delete.sh $DATABASE && ./sink-connector-setup-database.sh $DATABASE

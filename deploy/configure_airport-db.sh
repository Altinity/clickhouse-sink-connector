DATABASE=airportdb
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
docker exec -it mysql-master mysql -uroot -proot -e ""

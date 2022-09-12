DATABASE=sakila
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
docker cp  sakila-db/sakila-schema.sql mysql-master:/tmp
docker cp  sakila-db/sakila-data.sql mysql-master:/tmp
docker exec -it mysql-master mysql -uroot -proot -e "source /tmp/sakila-schema.sql;source /tmp/sakila-data.sql;"

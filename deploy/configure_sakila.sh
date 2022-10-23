DATABASE=sakila
./debezium-delete.sh $DATABASE &&  ./debezium-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"

wget https://downloads.mysql.com/docs/sakila-db.zip
unzip -a sakila-db.zip
docker cp  sakila-db/sakila-schema.sql mysql-master:/tmp
docker cp  sakila-db/sakila-data.sql mysql-master:/tmp
docker exec -it mysql-master mysql -uroot -proot -e "source /tmp/sakila-schema.sql;source /tmp/sakila-data.sql;"
rm -f sakila-db.zip
rm -fr sakila-db/

sleep 5
./sink-delete.sh $DATABASE && ./sink-connector-setup-database.sh $DATABASE
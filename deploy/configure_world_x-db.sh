DATABASE=world_x
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
wget https://downloads.mysql.com/docs/world_x-db.zip
unzip -a world_x-db.zip
docker cp  world_x-db/world_x.sql mysql-master:/
docker exec -it mysql-master mysql -uroot -proot -e "source /world_x.sql"
rm -fr world_x-db.zip
rm -fr world_x-db/

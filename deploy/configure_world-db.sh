DATABASE=world
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE

docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
wget https://downloads.mysql.com/docs/world-db.zip
unzip -a world-db.zip
docker cp  world-db/world.sql mysql-master:/
docker exec -it mysql-master mysql -uroot -proot -e "source /world.sql"
rm -fr world-db.zip
rm -fr world-db/

sleep 5
./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE

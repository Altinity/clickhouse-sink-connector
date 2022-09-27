DATABASE=sbtest
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE

docker exec -it mysql-master mysql -uroot -proot -e "CREATE SCHEMA sbtest;"
docker exec -it mysql-master mysql -uroot -proot -e "CREATE USER 'sbtest'@'%' IDENTIFIED BY 'passw0rd';"
docker exec -it mysql-master mysql -uroot -proot -e "GRANT ALL PRIVILEGES ON sbtest.* TO 'sbtest'@'%';"


docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"




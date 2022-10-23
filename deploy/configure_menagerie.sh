DATABASE=menagerie
./debezium-delete.sh $DATABASE &&  ./debezium-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
wget https://downloads.mysql.com/docs/menagerie-db.zip
unzip menagerie-db.zip
rm -fr menagerie-db.zip
rm -fr menagerie*.zip.*
docker cp  menagerie-db/cr_pet_tbl.sql mysql-master:/
docker cp  menagerie-db/pet.txt mysql-master:/
docker cp  menagerie-db/ins_puff_rec.sql mysql-master:/
docker cp  menagerie-db/cr_event_tbl.sql mysql-master:/
docker cp  menagerie-db/event.txt mysql-master:/
docker exec -it mysql-master mysql -uroot -proot -e "DROP DATABASE IF EXISTS $DATABASE;CREATE DATABASE $DATABASE;"
docker exec -it mysql-master mysql -uroot -proot -e "use $DATABASE;SOURCE cr_pet_tbl.sql;SOURCE ins_puff_rec.sql;SOURCE cr_event_tbl.sql;"
docker exec -it mysql-master mysqlimport -uroot -proot --local menagerie pet.txt
docker exec -it mysql-master mysqlimport -uroot -proot --local menagerie event.txt
rm -fr menagerie-db

sleep 5
./sink-delete.sh $DATABASE && ./sink-connector-setup-database.sh $DATABASE

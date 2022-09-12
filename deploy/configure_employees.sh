DATABASE=employees
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
./debezium-delete.sh &&  ./debezium-connector-setup-database.sh $DATABASE && ./sink-delete.sh && ./sink-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
docker cp  $HOME/test_db/employees.sql mysql-master:/
docker cp  $HOME/test_db/show_elapsed.sql mysql-master:/
docker cp  $HOME/test_db/load_departments.dump mysql-master:/
docker cp  $HOME/test_db/load_dept_emp.dump mysql-master:/
docker cp  $HOME/test_db/load_dept_manager.dump mysql-master:/
docker cp  $HOME/test_db/load_employees.dump mysql-master:/
docker cp  $HOME/test_db/load_salaries1.dump mysql-master:/
docker cp  $HOME/test_db/load_salaries2.dump mysql-master:/
docker cp  $HOME/test_db/load_salaries3.dump mysql-master:/
docker cp  $HOME/test_db/load_titles.dump mysql-master:/
docker exec -it mysql-master mysql -uroot -proot -e "source /employees.sql"

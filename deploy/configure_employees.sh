DATABASE=employees
./debezium-delete.sh $DATABASE &&  ./debezium-connector-setup-database.sh $DATABASE
docker exec -it clickhouse clickhouse-client -uroot --password root -mn --query "drop database if exists $DATABASE;create database $DATABASE;"
mkdir test_db
cd test_db
wget https://raw.githubusercontent.com/datacharmer/test_db/master/employees.sql
wget https://raw.githubusercontent.com/datacharmer/test_db/master/show_elapsed.sql
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_departments.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_dept_emp.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_dept_manager.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_employees.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_salaries1.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_salaries2.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_salaries3.dump
wget https://raw.githubusercontent.com/datacharmer/test_db/master/load_titles.dump

docker cp  employees.sql mysql-master:/
docker cp  show_elapsed.sql mysql-master:/
docker cp  load_departments.dump mysql-master:/
docker cp  load_dept_emp.dump mysql-master:/
docker cp  load_dept_manager.dump mysql-master:/
docker cp  load_employees.dump mysql-master:/
docker cp  load_salaries1.dump mysql-master:/
docker cp  load_salaries2.dump mysql-master:/
docker cp  load_salaries3.dump mysql-master:/
docker cp  load_titles.dump mysql-master:/

docker exec -it mysql-master mysql -uroot -proot -e "source /employees.sql"
sleep 5
./sink-delete.sh $DATABASE && ./sink-connector-setup-database.sh $DATABASE

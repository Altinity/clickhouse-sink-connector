DATABASE=$1
# requires the db to be dump using mysqlsh :
#rm -rf $HOME/dbdumps/world_x
#mysql-shell-8.0.30-linux-glibc2.12-x86-64bit/bin/mysqlsh -uroot -proot -hlocalhost -e "util.dumpSchemas(['world'], '/home/aadant/dbdumps/world');"
docker exec -it clickhouse clickhouse-client -uroot --password root --query "drop database if exists $DATABASE"
python db_load/clickhouse_loader.py --clickhouse_host localhost  --clickhouse_database $DATABASE --dump_dir $HOME/dbdumps/$DATABASE --clickhouse_user root --clickhouse_password root --threads 4  --mysql_source_database $DATABASE --mysqlshell
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database $DATABASE --tables_regex . --threads 4 | grep "Checksum for table" | awk '{print $11" "$13" "$15}' | sort >$DATABASE.ch
python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database $DATABASE --tables_regex . --threads 4 | grep "Checksum for table" | awk '{print $11" "$13" "$15}' | sort >$DATABASE.mysql
diff $DATABASE.ch $DATABASE.mysql | grep "<\|>"

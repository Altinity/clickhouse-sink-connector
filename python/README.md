## Installation

source ./install.sh

## Usage

Compute the checksum of one table 

```
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database menagerie --tables_regex ^pet --threads 4
2022-09-11 19:39:45,455 - INFO - ThreadPoolExecutor-0_0 - Checksum for table menagerie.pet = 3d19b8b13cf29b5192068278123c5059 count 9

python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database menagerie --tables_regex ^pet --threads 4
2022-09-11 19:39:49,148 - INFO - ThreadPoolExecutor-0_0 - Checksum for table menagerie.pet = 3d19b8b13cf29b5192068278123c5059 count 9
```

Compare all tables in database an diff the checksums

```
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database menagerie --tables_regex . --threads 4 | grep "Checksum for table" | awk '{print $11" "$13" "$15}' | sort >menagerie.ch
python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database menagerie --tables_regex . --threads 4 | grep "Checksum for table" | awk '{print $11" "$13" "$15}' | sort >menagerie.mysql
diff menagerie.ch menagerie.mysql | grep "<\|>"
```

Debug differences

Example table pet :

```
python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database menagerie --tables_regex "^pet" --debug_output
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database menagerie --tables_regex "^pet" --debug_output 
diff out.pet.ch.txt out.pet.mysql.txt  | grep "<\|>"
```

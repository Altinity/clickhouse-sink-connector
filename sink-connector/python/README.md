## Installation

source ./install.sh

## Loading data to ClickHouse from a dump

clickhouse_loader.py is a program that loads data dumped in MySQL into a CH database compatible the sink connector (ReplacingMergeTree with virtual columns _version and _sign)

It creates the schema (--schema_only option if you only need the schema), and loads the data using clickhouse-client and zstd to decompress the dump files.

The schema conversion is very crude. It does not use a SQL parser but it just applies regexp filters
Please submit enhancement requires or bug fixes.

Limitations :

- only fully tested with mysqlsh dumps (sakila, employees, airportdb, world, menagerie)
- ClickHouse Date / DateTime range conversion can lead to data loss if outside the supported ranges
- it does not support partition creation
- it does not support dump and load from a S3 compatible bucket
- make sure you run checksums to validate the load

Example : assuming the world database was dumped with mysqlsh using this command ($HOME needs to be replaced with a constant) :

MySQL Shell(JS version works better with Python)
```
mysqlsh -uroot -proot -hlocalhost -e "util.dumpSchemas(['world'], '$HOME/dbdumps/world');"

## Dump separate tables
 ## If there are no permissions for LOCKING, use consistent: false flag.
 util.dumpTables("db_name", ["table1", "table2"], "output_directory", {"consistent": "false", "threads": "100"})
```

!mydumper is not supported at this stage!  

```
DATABASE=world
## Error with max number of concurrent connections(100)
docker exec -it clickhouse clickhouse-client -uroot --password root --query "drop database if exists $DATABASE"
python db_load/clickhouse_loader.py --clickhouse_host localhost  --clickhouse_database $DATABASE --dump_dir $HOME/dbdumps/$DATABASE --clickhouse_user root --clickhouse_password root --threads 50  --mysql_source_database $DATABASE --mysqlshell
```

If you loaded the same data in MySQL, you can then run checksums (see test_db.sh)

## Table checksums
**Note**: Only `Python 3.10` is supported\
**Credits** : https://www.sisense.com/blog/hashing-tables-to-ensure-consistency-in-postgres-redshift-and-mysql/

Compute the checksum of one table 

```
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database menagerie --tables_regex ^pet --threads 4
2022-09-11 19:39:45,455 - INFO - ThreadPoolExecutor-0_0 - Checksum for table menagerie.pet = 3d19b8b13cf29b5192068278123c5059 count 9

python db_compare/mysql_table_checksum.py --mysql_host localhost --mysql_user root --mysql_password root  --mysql_database menagerie --tables_regex ^pet --threads 4
2022-09-11 19:39:49,148 - INFO - ThreadPoolExecutor-0_0 - Checksum for table menagerie.pet = 3d19b8b13cf29b5192068278123c5059 count 9
```

## Compute table Checksum on ReplacingMergeTree tables with is_deleted columns
```
python db_compare/clickhouse_table_checksum.py --clickhouse_host localhost --clickhouse_user root --clickhouse_password root  --clickhouse_database test --tables_regex . --threads 4 
--exclude_columns=is_deleted,_version --sign_column ""
```

## Connecting to secure ClickHouse
```
python3 clickhouse_table_checksum.py --sign_column=sign --secure=True --clickhouse_port 9440 --clickhouse_host secure-host  --clickhouse_user user  --clickhouse_password password  --clickhouse_database das --tables_regex '^products' --exclude_columns=[sign,ver]
```

## Exclude columns
```
python3 clickhouse_table_checksum.py --sign_column=sign --secure=True --clickhouse_port 9440 --clickhouse_host secure-host --clickhouse_user user --clickhouse_password password --clickhouse_database das --tables_regex '^products' --exclude_columns=sign,ver --debug_output --debug


```
Compare all tables in database and diff the checksums

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

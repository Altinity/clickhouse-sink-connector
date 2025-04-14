#### DDL Supported (MySQL):
With Datatype conversion(From MySQL to ClickHouse)

ALTER TABLE

| MySQL                                                  | ClickHouse                      |
|--------------------------------------------------------|---------------------------------|
| ADD COLUMN                                             |                                 |
| ADD COLUMN NULL/NOT NULL                               |                                 |
| ADD COLUMN DEFAULT                                     |                                 |
| ADD COLUMN FIRST, AFTER                                |                                 |
| DROP COLUMN                                            | Supported                       |
| MODIFY COLUMN data_type                                |                                 |
| MODIFY COLUMN data_type NULL/NOT NULL                  |                                 |
| MODIFY COLUMN data_type DEFAULT                        |                                 |
| MODIFY COLUMN FIRST, AFTER                             |                                 |
| MODIFY COLUMN old_name new_name datatype NULL/NOT NULL | Supported                       |
| RENAME COLUMN col1 to col2                             | Supported                       |
| CHANGE COLUMN FIRST, AFTER                             | MODIFY COLUMN                   |
| ALTER COLUMN col_name ADD DEFAULT                      | Not supported by grammar        |
| ALTER COLUMN col_name ADD DROP DEFAULT                 | Not supported by grammar        |
| ADD PRIMARY KEY                                        | Cannot modify primary key in CH |


## TABLE operations
| MySQL                                            | ClickHouse                          |
|--------------------------------------------------|-------------------------------------|
| RENAME TABLE name_1 to name_2                    | Supported                           |
| RENAME TABLE name_1 to name_2, name_3 to name_4  | Supported                           |
| TRUNCATE TABLE                                   | Supported                           |
| DROP TABLE name_1                                | Supported                           |
| DROP TABLE name_1, name_2                        | Supported                           |
| ALTER TABLE table_name to new_table_name         | RENAME table_name to new_table_name |
| CREATE TABLE PARTITION BY KEY(col1)              | PARTITION BY col1                   |
| CREATE TABLE PARTITION BY RANGE(col1,col2, col3) | PARTITION BY col1, col2, col3       |
| CREATE TABLE table1 as table2                    | Supported                           |
| CREATE TABLE table1 GENERATED <col1>             | CREATE TABLE table1 MATERIALIZED    |


## DATABASE operations
| MySQL           | ClickHouse |
|-----------------|------------|
| CREATE DATABASE | Supported  |
| USE DATABASE    | Supported  |


###  Not supported:

| MySQL                                                  | ClickHouse                                                      |
|--------------------------------------------------------|-----------------------------------------------------------------|
| ADD INDEX                                              | Secondary indexes in CH, what about type and index granularity? |
| ADD CONSTRAINT  (CHECK)                                |                                                                 |
| ADD CONSTRAINT                                         | Add constraint with Primary key(Not supported)                  |
| DROP CONSTRAINT                                        | Supported                                                      |
| DROP INDEX                                            | Supported                                                      |

## DataType Mappings:
| MySQL | ClickHouse |
|-------|------------|
| enum  | String     |
| JSON  | String     |


#### DDL Supported (MySQL):
With Datatype conversion(From MySQL to ClickHouse)

ALTER TABLE

| MySQL                                                  | ClickHouse                      |
|--------------------------------------------------------|---------------------------------|
| ADD COLUMN                                             |                                 |
| ADD COLUMN NULL/NOT NULL                               |                                 |
| ADD COLUMN DEFAULT                                     |                                 |
| ADD COLUMN FIRST, AFTER                                |                                 |
| DROP COLUMN                                            | Supported. A sorting-key column cannot be dropped from the existing CH table (Code: 524): the drop is deferred to the rebuilt table (Spec 06.09 §3.1.1), e.g. the GIPK promotion `DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id)`. |
| MODIFY COLUMN data_type                                | Supported. On a sorting-key column: a same-or-narrower type is skipped as loss-free; a wider or non-comparable type rebuilds the table under the same key with the new type (Spec 06.05 §3.4 / Spec 06.09 §3.1.2): the swap is inside the DDL barrier (metadata only) and the copy of the rows runs online afterwards -- a value-level comparison differs and the retired table `<table>__pk_rebuild_<ts>` (or `__pk_retired_<ts>`) exists until the log reports the backfill complete; `ddl.primary.key.rebuild=false` restores the loud stop. |
| MODIFY COLUMN data_type NULL/NOT NULL                  |                                 |
| MODIFY COLUMN data_type DEFAULT                        |                                 |
| MODIFY COLUMN FIRST, AFTER                             |                                 |
| MODIFY COLUMN old_name new_name datatype NULL/NOT NULL | Supported. A sorting-key column cannot be renamed on the existing CH table (Code: 524): a CHANGE of one rebuilds the table keyed by the new name (Spec 06.09 §3.1.1) -- swap inside the barrier, copy online afterwards; a value-level comparison differs and the retired table `<table>__pk_rebuild_<ts>` (or `__pk_retired_<ts>`) exists until the log reports the backfill complete; `ddl.primary.key.rebuild=false` restores the loud stop. |
| RENAME COLUMN col1 to col2                             | Supported. A sorting-key column: rebuild keyed by the new name, as for CHANGE (Spec 06.09 §3.1.1). |
| CHANGE COLUMN FIRST, AFTER                             | MODIFY COLUMN                   |
| ALTER COLUMN col_name ADD DEFAULT                      | Not supported by grammar        |
| ALTER COLUMN col_name ADD DROP DEFAULT                 | Not supported by grammar        |
| ADD PRIMARY KEY                                        | The sorting key is fixed at CREATE in CH, so a key that changes the row identity rebuilds the table under the new key (Spec 06.09): the swap -- an empty table with the new key exchanged into place -- is inside the DDL barrier and metadata-only, and the copy of the pre-DDL rows runs online afterwards on the `pk-rebuild-backfill` thread while replication continues. A value-level comparison of the table is expected to differ and the retired table `<table>__pk_rebuild_<ts>` (or `<table>__pk_retired_<ts>`) exists until the log reports the backfill complete; a restart resumes an unfinished backfill. A restatement of the existing key is skipped. `ddl.primary.key.rebuild=false` restores the loud stop instead of the rebuild. |
| DROP PRIMARY KEY                                       | The sorting key is fixed at CREATE in CH, so a drop without a replacement key rebuilds the table under the keyless all-columns key (Spec 06.09): swap inside the DDL barrier, copy online afterwards; a value-level comparison differs and the retired table `<table>__pk_rebuild_<ts>` (or `__pk_retired_<ts>`) exists until the log reports the backfill complete. `ddl.primary.key.rebuild=false` restores the loud stop instead of the rebuild. |
| DROP PRIMARY KEY, DROP COLUMN my_row_id, ADD PRIMARY KEY (id) | The GIPK promotion: the table is rebuilt keyed by `id`, and `my_row_id` (the old key column, which CH cannot drop in place) is dropped from the rebuilt table before the rows are copied (Spec 06.09 §3.1.1). |


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


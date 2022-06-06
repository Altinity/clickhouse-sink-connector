## CollapsingMergeTree
Sink Connector will attempt to read the `engine_full` column from system.tables for the corresponding table and will 
identify the `engine` and the `sign` column.

`CollapsingMergeTree(sign) PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192`

### Updates:
For inserts, record will be inserted with `sign` set to `1`
For updates, `before` value will be inserted with `sign` set to `-1`
and `after` value will be inserted with `sign` set to `1`

When `optimize table <table_name> final` of `select .. final` is performed and when the merges are performed by
ClickHouse in the background, the initial insert record will be merged along the `before` record.

### Deletes:
For deletes, record will be inserted with `sign` set to `-1`

## ReplacingMergeTree
For updates, only the `after` record will be inserted with `version` set to timestamp in milliseconds.
After merging, ClickHouse will drop the previous insert since the update `version` column value is greater
than the insert record `version` value.

For Deletes, the user provided `replacingmergetree.delete.column` will be set to `-1`


## Other table Engines(MergeTree ...)


   
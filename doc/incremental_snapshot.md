## Adding new tables to configuration.
The following steps will guide you through the process of adding newer tables to be replicated 
from Source after the initial replication was setup.
This is achieved using the incremental snapshots feature of debezium
https://debezium.io/blog/2021/10/07/incremental-snapshots/

**Step 1**: Create the `signaling` table in Source using the following schema
```
CREATE TABLE dbz_signal (id VARCHAR(<varcharValue>) PRIMARY KEY,
type VARCHAR(32) NOT NULL,
data VARCHAR(2048) NULL);
```
**Step 2**: Update the `table.include.list` in `config.yml` to include the newer tables and the `signal.data.collection` configuration.
```
table.include.list:"dbname.table1, dbname.table2`
signal.data.collection: "dbname.dbz_signal"
```
Restart the sink connector, either using the `sink-connector-client restart` or by stopping/starting sink connector.

**Step 3**: Insert a new row to the `signaling` table created in Step 1 with the new table information.
```
INSERT INTO dbname.dbz_signal VALUES ('d139b9b7–7777–4547–917d-e1775ea61d61', 'execute-snapshot', '{"data-collections": ["dbname.table1, dbname.table2"]}')
```

This step should trigger the snapshot process in parallel to the regular streaming.

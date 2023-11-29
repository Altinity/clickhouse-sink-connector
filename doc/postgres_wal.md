## PostgreSQL WAL Dump utility.

Using the `pg_waldump` utility to dump the WAL log information. `pg_waldump` utility needs to be provided the postgresql data directory path.
```
pg_waldump pg_wal/000000010000000000000001
```

```
┌─id───────────────────────────────────┬─offset_key────────────────────────────────────┬─offset_val──────────────────────────────────────────────────────────────────────────────────────────────────────────────┬────record_insert_ts─┬─record_insert_seq─┐
│ 03750062-c862-48c5-9f37-451c0d33511b │ ["\"engine\"",{"server":"embeddedconnector"}] │ {"transaction_id":null,"lsn_proc":27485360,"messageType":"UPDATE","lsn":27485360,"txId":743,"ts_usec":1687876724804733} │ 2023-06-27 14:38:45 │                 1 │
└──────────────────────────────────────┴───────────────────────────────────────────────┴─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┴─────────────────────┴───────────────────┘

```
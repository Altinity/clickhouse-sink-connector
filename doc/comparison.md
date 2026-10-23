| Feature                         | Altinity Sink Connector (Lightweight, Single Binary) | Airbyte                        | ClickHouse `mysql` Table Engine       | Custom Python Script with ClickHouse Connect |
|---------------------------------|------------------------------------------------------|--------------------------------|----------------------------------------|-----------------------------------------------|
| **Replication Type**            | Real-time CDC                                        | Batch (Scheduled)              | Direct Query                           | Batch or Scheduled                           |
| **Data Freshness**              | Near real-time                                      | Configurable (e.g., hourly)    | Near real-time (with latency)          | Configurable                                 |
| **Schema Change Handling**      | Full support(MySQL), Partial(PostgreSQL)  | Manual schema refresh required | No automatic schema sync               | Manual intervention needed                   |
| **Complexity**                  | Low to Medium (single binary setup)                  | Moderate                       | Low                                    | High (requires coding and scheduling)        |
| **Ease of Setup**               | Easy (standalone binary, no Kafka needed)            | Easy                           | Very easy                              | Complex (custom coding)                      |
| **Maintenance**                 | Low to Moderate (single binary process)              | Low                            | Low                                    | High                                        |
| **Initial Sync Support**        | Yes                                                 | Yes                            | Not applicable (direct query)          | Yes                                         |
| **Transformation Capabilities** | Limited                                             | Basic (Airbyte transformations)| No                                     | Full control (custom code)                   |
| **Cost**                        | Free or license-based                               | Free (Open-source)             | Free (built-in to ClickHouse)          | Free (but may require custom infrastructure) |
| **Suitability for High Volume** | High                                                | Medium                         | Medium                                 | Medium to Low                                |
| **Additional Infrastructure**   | None                                                | None                           | None                                   | Optional (scheduling tools like Airflow)     |
| **Data Accuracy**               | High (real-time CDC)                                | Medium (depends on sync frequency) | Medium                              | High                                        |
| **Ideal Use Case**              | Low-latency, real-time replication without Kafka     | Batch syncs, easy setup        | Simple queries without replication     | Custom, flexible ETL                        |


| Feature                         | Altinity Sink Connector (Lightweight, Single Binary) | Airbyte                        |
|---------------------------------|------------------------------------------------------|--------------------------------|
|

## Comparison with OLake (MySQL source)

[OLake](https://github.com/datazip-inc/olake) reads the MySQL binlog and writes to Apache Iceberg or Parquet. The table compares MySQL coverage, from a review of the OLake source at commit `73d065d` (2026-10-21). "Not handled" means the OLake code has no handling for the case; the behavior was not measured.

| Area | Altinity Sink Connector | OLake |
|------|-------------------------|-------|
| Destination | ClickHouse (ReplacingMergeTree, MergeTree, ReplicatedReplacingMergeTree) | Apache Iceberg or Parquet only. No ClickHouse writer. |
| Compressed binlog transactions (`binlog_transaction_compression=ON`) | Supported. Decoded transparently, with a start-up check (`binlog.transaction.compression.check`). | Not handled. The binlog loop reads only rotate, GTID, rows and query events, and a compressed transaction arrives as a single payload event. |
| Partial JSON updates (`binlog_row_value_options=PARTIAL_JSON`) | Not supported. The update is skipped without an error. See [Known limitations](limitations.md). | Not handled. The partial update event is ignored without an error. |
| `DECIMAL` / `NUMERIC` | Mapped to a ClickHouse `Decimal`. See [data types](data_types.md). | Converted to a 64-bit float. Exact up to 15 significant digits, longer values can lose precision. |
| `BIGINT UNSIGNED` above 2^63-1 | Stored exactly in `UInt64` (source type propagation is enabled automatically in the lightweight connector). | Stays wrapped to a negative number. OLake has no unsigned 64-bit type (open TODO in its source). |
| `TRUNCATE TABLE` | Replayed on the ClickHouse table. History mode keeps the rows. `disable.drop.truncate=true` skips it. | Not applied to the destination table. |
| Row-level filter conditions | Not built in. Databases, tables and columns can be filtered with Debezium configuration. | Supported per stream. Also applied to delete events, so a delete whose row image does not match the filter is not written. |
| SSH tunnel to the source | Not built in. | Built in (`ssh_config`). |
| Incremental sync by cursor column (no binlog) | Not built in. | Supported. |
| Initial snapshot | Supported (`snapshot.mode`). | Supported (backfill). |

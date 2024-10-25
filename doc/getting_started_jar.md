# QuickStart Installation for Lightweight Sink Connector(JAR file)

This is the recommended path for initial use. It uses PostgreSQL as the source database and brings up a full stack with 
PostgreSQL, ClickHouse, Lightweight Sink Connector, and Grafana. Example shown here is for Ubuntu but will work for any Linux or MacOS provided prerequisites are installed.


### Start PostgreSQL and ClickHouse in docker-compose.
```
cd sink-connector-lightweight/docker
docker-compose -f docker-compose-postgres-standalone.yml up
```
This will start PostgreSQL and ClickHouse in docker-compose.

For simplicity, the configuration file for sink connector (`docker/config_postgres_local.yml`) 
is setup to connect to the PostgreSQL and ClickHouse services started by docker compose.

### Start the Lightweight Sink Connector(JAR file)
```
java -jar target/clickhouse-debezium-embedded-0.0.4.jar docker/config_postgres_local.yml
```

By default the replication is setup to replicate two tables,
in the sink connector logs you will notice the following lines that 
highlight the newer records that are inserted to ClickHouse.

```
2024-08-14 18:44:40.613 INFO  - *** INSERT QUERY for Database(public) ***: insert into `tm`(`id`,`secid`,`acc_id`,`ccatz`,`tcred`,`am`,`set_date`,`created`,`updated`,`events_id`,`events_transaction_id`,`events_status`,`events_payment_snapshot`,`events_created`,`vid`,`vtid`,`vstatus`,`vamount`,`vcreated`,`vbilling_currency`,`_version`,`_sign`) select `id`,`secid`,`acc_id`,`ccatz`,`tcred`,`am`,`set_date`,`created`,`updated`,`events_id`,`events_transaction_id`,`events_status`,`events_payment_snapshot`,`events_created`,`vid`,`vtid`,`vstatus`,`vamount`,`vcreated`,`vbilling_currency`,`_version`,`_sign` from input('`id` UUID,`secid` Nullable(UUID),`acc_id` Nullable(UUID),`ccatz` Nullable(String),`tcred` Nullable(Bool),`am` Nullable(Decimal(21, 5)),`set_date` Nullable(String),`created` Nullable(String),`updated` Nullable(String),`events_id` Nullable(UUID),`events_transaction_id` Nullable(UUID),`events_status` Nullable(String),`events_payment_snapshot` Nullable(String),`events_created` Nullable(String),`vid` Nullable(UUID),`vtid` Nullable(UUID),`vstatus` Nullable(String),`vamount` Nullable(Decimal(21, 5)),`vcreated` Nullable(String),`vbilling_currency` Nullable(String),`_version` UInt64,`_sign` UInt8')
2024-08-14 18:44:40.625 INFO  - *************** EXECUTED BATCH Successfully Records: 2************** task(0) Thread ID: Sink Connector thread-pool-5 Result: [I@14e8dc94 Database: public Table: tm

```
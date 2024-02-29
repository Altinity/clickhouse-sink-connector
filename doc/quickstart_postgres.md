# Sink Connector QuickStart Guide (PostgreSQL)

Use [Docker Compose](https://docs.docker.com/compose/) to bring 
up a complete configuration that illustrates operation of 
Altinity Sink Connector.

# QuickStart Installation for Lightweight Sink Connector

This is the recommended path for initial use. It uses PostgreSQL as the
source database and brings up a full stack with PostgreSQL, ClickHouse, 
Lightweight Sink Connector, and Grafana. Example shown here is for
Ubuntu but will work for any Linux or MacOS provided prerequisites
are installed. 

## Prerequisites

Install Docker and Docker Compose.

* [Docker Installation](https://docs.docker.com/engine/install/) 
* [Docker Compose Installation](https://docs.docker.com/compose/) 


## Start the stack 

Use Docker Compose to start containers. Pick the latest released tag.
```
cd sink-connector-lightweight/docker
export CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE=altinity/clickhouse-sink-connector:2.0.0-lt
docker compose -f docker-compose-postgres.yml up --renew-anon-volumes
```

## Test replication 

The installed configuration will replicate any table added to PostgreSQL
to ClickHouse table test.

Login to postgresql container, execute `psql`
```
5b62722f5041:/# psql -U root public
```

The postgres container contains seed scripts to create a table and insert data. 
Update the table and observe replication to ClickHouse.
```
public=# update public.tm set vstatus='COMPLETED_2';
UPDATE 2

public=# select * from public.tm;
                  id                  |                secid                 |                acc_id                | ccatz | tcred |      am      |           set_date           |           created            |           updated            |              events_id               |        events_transaction_id         | events_status | events_payment_snapshot |        events_created        |                 vid                  |                 vtid                 |  vstatus  |   vamount    |           vcreated           | vbilling_currency 
--------------------------------------+--------------------------------------+--------------------------------------+-------+-------+--------------+------------------------------+------------------------------+------------------------------+--------------------------------------+--------------------------------------+---------------+-------------------------+------------------------------+--------------------------------------+--------------------------------------+-----------+--------------+------------------------------+-------------------
 9cb52b2a-8ef2-4987-8856-c79a1b2c2f71 | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 | IDR   | t     | 200000.00000 | 2022-10-16 16:53:15.01957+00 | 2022-10-16 16:53:15.01957+00 | 2022-10-16 16:53:15.01957+00 | b4763f4a-2e3f-41ae-9715-4ab113e2f53c | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 |               | {"Hello": "World"}      | 2022-10-16 16:53:15.01957+00 |                                      |                                      |           |              |                              | 
 9cb52b2a-8ef2-4987-8856-c79a1b2c2f73 | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 | IDR   | t     | 200000.00000 | 2022-10-16 16:53:15.01957+00 | 2022-10-16 16:53:15.01957+00 | 2022-10-16 16:53:15.01957+00 | b4763f4a-2e3f-41ae-9715-4ab113e2f53c | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 |               |                         | 2022-10-16 16:53:15.01957+00 | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f71 | 9cb52b2a-8ef2-4987-8856-c79a1b2c2f72 | COMPLETED | 200000.00000 | 2022-10-16 16:53:15.01957+00 | IDR

public=# update public.tm set vstatus='COMPLETED_2';
UPDATE 2

```

Login to ClickHouse, substituting your host name. 
```
clickhouse-client --user=root --password=root
```

Confirm that ClickHouse has the table in database test and that it 
has the updated column value.
```
49f79bda7622 :) select vstatus from tm final;

SELECT vstatus
FROM tm
FINAL

Query id: a88d9052-eeda-4071-ac98-7bd22ff28a8c

┌─vstatus─────┐
│ COMPLETED_2 │
│ COMPLETED_2 │
└─────────────┘

```

## Stop the stack

Stop all components. 
```
docker compose -f docker-compose-postgres.yml down
```

### Connecting to External PostgreSQL/ClickHouse

**Step 1:** Update **PostgreSQL** information in config.yaml(https://github.com/Altinity/clickhouse-sink-connector/blob/develop/sink-connector-lightweight/docker/config_postgres.yml
):
```
   database.hostname: <PostgreSQL Hostname>
   database.port: <PostgreSQL Port>
   database.user: <PostgreSQL username>
   database.password: <PostgreSQL password>
```
**Step 2:** Update **ClickHouse** information in config.yaml: 
```
    clickhouse.server.url: <ClickHouse hostname>
    clickhouse.server.user: <ClickHouse username>
    clickhouse.server.password: <ClickHouse password>
    clickhouse.server.port. <ClickHouse port>
```
**Step 3:** Update **Offset storage/Schema History** to be stored in **ClickHouse**:
```
    offset.storage.jdbc.url: "jdbc:clickhouse://<ClickHouse hostname>:<ClickHouse port>/altinity_sink_connector"
    schema.history.internal.jdbc.url: "jdbc:clickhouse://<ClickHouse hostname>:<ClickHouse port>/altinity_sink_connector"
    
    offset.storage.jdbc.user: <ClickHouse username>
    offset.storage.jdbc.password: <ClickHouse password>
    
    schema.history.internal.jdbc.user: <ClickHouse username>
    schema.history.internal.jdbc.password: <ClickHouse password>
```
**Step 4:** Update **PostgreSQL schemas** to be replicated:
```
    schema.include.list: <Database name>
```

**Step 5:** Add **table filters** to include/exclude tables to be replicated:
```
    table.include.list: <Table names>
    table.exclude.list: <Table names>
```
**Step 6:** Configure **Snapshot Mode** to define initial load vs CDC replication:
```
    # Initial load(transfer all existing data in PostgreSQL)
    snapshot.mode: initial
    
    or
    
    # CDC replication(transfer only new data in PostgreSQL)
    snapshot.mode: schema_only
```
**Note: ClickHouse Secure(Altinity Cloud/ClickHouse Cloud)**:
Set the sever url to `https` and add `?ssl=true` to the end of the url.
```
clickhouse.server.url: "https://cloud_url"
offset.storage.jdbc.url: "jdbc:clickhouse://cloud_url:8443/altinity_sink_connector?ssl=true"
schema.history.internal.jdbc.url: "jdbc:clickhouse://cloud_url:8443/altinity_sink_connector?ssl=true"
```

## References:
1. [Sink Connector Configuration ](configuration.md)
2. [PostgreSQL Setup](https://debezium.io/documentation/reference/2.5/connectors/postgresql.html#setting-up-postgresql)
3. For AWS RDS users, you might need to add heartbeat interval and query to avoid WAL logs constantly growing in size.
   https://stackoverflow.com/questions/76415644/postgresql-wal-log-limiting-rds
   https://debezium.io/documentation/reference/stable/connectors/postgresql.html#postgresql-wal-disk-space
# PostgreSQL Database/Catalog/Schema Naming Hierarchy for litellm-dev

**Date:** 2026-03-10  
**Purpose:** Understand why ClickHouse database is named `app` and how PostgreSQL's naming hierarchy maps to ClickHouse.

---

## 1. PostgreSQL Naming Hierarchy

PostgreSQL has a **3-level naming hierarchy**: `database` → `schema` → `table`

### Query Results

```
 database_name | catalog_name | current_schema | pg_version
---------------+--------------+----------------+---------------------------------------------
 app           | app          | public         | PostgreSQL 16.2 (Debian 16.2-1.pgdg110+2)
```

**Key insight:** In PostgreSQL, `database_name` ≡ `catalog_name`. They are always identical — the SQL standard concept of "catalog" maps directly to the PostgreSQL "database".

### Full Hierarchy for this Instance

| Level            | Value    | Notes                                      |
|------------------|----------|--------------------------------------------|
| **Database**     | `app`    | Also the "catalog" in SQL standard terms   |
| **Catalog**      | `app`    | Always equals `database_name` in PG        |
| **Schema**       | `public` | Default schema; all LiteLLM tables live here |
| **Tables**       | 55+      | e.g., `LiteLLM_SpendLogs`, `LiteLLM_UserTable`, etc. |

### Fully Qualified Table Name in PostgreSQL

```
app.public.LiteLLM_SpendLogs
 │    │         │
 │    │         └── table_name
 │    └── table_schema (schema)
 └── table_catalog (= database name)
```

### Databases on this PG Instance

| database_name |
|---------------|
| `app`         |
| `postgres`    |

### Schemas in `app` Database

| schema_name        |
|--------------------|
| `information_schema` |
| `pg_catalog`       |
| `public`           |

### Tables (sample from `public` schema)

All 55+ tables have the same catalog/schema pattern:

```
 table_catalog | table_schema | table_name
---------------+--------------+------------------------------
 app           | public       | LiteLLM_SpendLogs
 app           | public       | LiteLLM_UserTable
 app           | public       | LiteLLM_VerificationToken
 app           | public       | LiteLLM_TeamTable
 app           | public       | _prisma_migrations
 app           | public       | sink_connector_heartbeat
 ... (55+ tables total)
```

---

## 2. Debezium Topic Naming Convention

The connector config uses:

```yaml
database.server.name: "litellm_dev_to_ch_metrics_dev"
```

There is **no explicit `topic.prefix`** in the config. In Debezium, `database.server.name` serves as the topic prefix (in older Debezium versions; newer versions use `topic.prefix`).

**Debezium topic format for PostgreSQL:**

```
{topic.prefix/server.name}.{schema}.{table}
```

So topics would look like:
```
litellm_dev_to_ch_metrics_dev.public.LiteLLM_SpendLogs
litellm_dev_to_ch_metrics_dev.public.LiteLLM_UserTable
```

---

## 3. Why Data Landed in `app` Instead of `litellm_dev`

### The Root Cause

The connector config specifies:

```yaml
clickhouse.server.database: "litellm_dev"
```

However, the connector **ignores this setting for DML records**. Instead, the ClickHouse target database name is derived from the **Debezium source record's `source.db` field**, which contains the PostgreSQL database name.

### The Code Path

1. **`ClickHouseStruct`** ([`ClickHouseStruct.java:482`](sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/ClickHouseStruct.java:482)):
   ```java
   this.setDatabase((String) source.get(DATABASE));
   ```
   Where `DATABASE` = `"db"` ([`SinkRecordColumns.java:84`](sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/model/SinkRecordColumns.java:84))

2. **`extractDatabaseNameFromRecord()`** ([`DebeziumChangeEventCapture.java:782`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java:782)):
   ```java
   String db = (String) sourceStruct.get("db");
   ```

3. The Debezium PostgreSQL connector sets `source.db` = the **PostgreSQL database name** = `app`.

4. Therefore, all tables get created in ClickHouse database `app`, not `litellm_dev`.

### The Mapping Chain

```
PostgreSQL database "app"
    → Debezium source.db = "app"
        → ClickHouseStruct.database = "app"
            → ClickHouse database = "app"  ← THIS IS WHERE TABLES ACTUALLY LAND
            
Config: clickhouse.server.database = "litellm_dev"  ← IGNORED for DML routing
```

### Current State in ClickHouse

| Database      | Tables |
|---------------|--------|
| `litellm_dev` | **0** (empty) |
| `app`         | **19** tables (LiteLLM_SpendLogs, LiteLLM_UserTable, etc.) |

---

## 4. What the Correct ClickHouse Database Name Should Be

The connector is **working as designed** — it uses the PostgreSQL database name (`app`) as the ClickHouse database name. This is the standard behavior for the Altinity sink connector.

### Options

1. **Accept `app` as the CH database name** — This is the simplest path. The data is already there. The config's `clickhouse.server.database: "litellm_dev"` is misleading because it's not used for DML routing.

2. **Use database override mapping** — The MySQL DDL parser has a `sourceToDestinationMap` concept ([`MySqlDDLParserListenerImpl.java:132`](sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java:132)), but this is not implemented for PostgreSQL DML routing.

3. **Rename the PG database** — Not practical; the PG database is named `app` by the LiteLLM application.

### Recommendation

Accept `app` as the ClickHouse database name. Update the config comment and tooling to reflect this reality:

```yaml
# The actual CH database will be "app" (derived from PG database name).
# clickhouse.server.database is used for some internal operations but NOT for DML routing.
clickhouse.server.database: "litellm_dev"  # Does not control DML target database
```

---

## 5. Commands to Verify This Yourself

### Connect to PostgreSQL and check hierarchy
```bash
# Get credentials
ssh fpif-metrics-dev-ch01 "grep -E 'database\.(hostname|port|user|password|dbname)' \
  /home/clickhouse/sink-connector/litellm-dev-sink/config/config.yml"

# Check database/catalog/schema
ssh fpif-metrics-dev-ch01 "PGPASSWORD='xFNrMcAGH7FAvRiMcvjm' psql -h 7.150.17.35 -p 5432 \
  -U sink_connector_user -d app -c \"
  SELECT current_database() as database_name,
         current_catalog as catalog_name,
         current_schema() as current_schema;\""

# List schemas
ssh fpif-metrics-dev-ch01 "PGPASSWORD='xFNrMcAGH7FAvRiMcvjm' psql -h 7.150.17.35 -p 5432 \
  -U sink_connector_user -d app -c \"
  SELECT schema_name FROM information_schema.schemata ORDER BY schema_name;\""

# List tables with full hierarchy
ssh fpif-metrics-dev-ch01 "PGPASSWORD='xFNrMcAGH7FAvRiMcvjm' psql -h 7.150.17.35 -p 5432 \
  -U sink_connector_user -d app -t -c \"
  SELECT table_catalog, table_schema, table_name 
  FROM information_schema.tables 
  WHERE table_schema = 'public' ORDER BY table_name;\""
```

### Check ClickHouse tables
```bash
# Tables in 'app' database (where data actually lands)
~/.ch.sh fpif-metrics-dev-ch01 default -q "SHOW TABLES FROM app"

# Tables in 'litellm_dev' database (empty)
~/.ch.sh fpif-metrics-dev-ch01 default -q "SHOW TABLES FROM litellm_dev"
```

---

## 6. Summary

| Concept | PostgreSQL | ClickHouse | Notes |
|---------|-----------|------------|-------|
| Database/Catalog | `app` | `app` | PG db name becomes CH db name |
| Schema | `public` | *(n/a)* | CH has no schema concept; flattened |
| Table | `LiteLLM_SpendLogs` | `LiteLLM_SpendLogs` | 1:1 mapping |
| Full PG name | `app.public.LiteLLM_SpendLogs` | `app.LiteLLM_SpendLogs` | Schema layer dropped |
| Config `clickhouse.server.database` | — | `litellm_dev` | **Not used** for DML routing |
| Debezium `source.db` | `app` | → `app` | **Actually used** for CH database |

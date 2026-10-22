## Time Zone
Ideally the timezone of the source database (MySQL/PostgreSQL) should be the same as the timezone of ClickHouse (preferably UTC).
If they are different, set both zone keys explicitly, as described below.

### Both zone keys are mandatory
The connector has no default zone. Both keys must be set in the connector configuration. The connector refuses to start
if either key is unset, empty or blank.

| Key | Controls |
|---|---|
| `database.connectionTimeZone` | The zone of the source database. The connector uses it to convert the source's DATETIME and TIMESTAMP values, and the source JDBC driver uses the same zone. |
| `clickhouse.datetime.timezone` | The zone of the `DateTime` and `DateTime64` columns that the connector writes to ClickHouse. |

The check runs before the connector opens a connection to the source or to ClickHouse. Nothing falls back to the JVM zone,
the host zone, the ClickHouse server zone or the MySQL session zone.

#### Refusal message
When a key is missing, the connector refuses to start and reports a message of this form (shown for `clickhouse.datetime.timezone`):

```
clickhouse.datetime.timezone is not set. Both zone settings are mandatory and have no default: set clickhouse.datetime.timezone=UTC (or another IANA region id such as America/Chicago) in the connector configuration.
```

The message for `database.connectionTimeZone` has the same form and names that key.

#### Accepted and refused values

| Value | `database.connectionTimeZone` | `clickhouse.datetime.timezone` |
|---|---|---|
| IANA region ids, such as `UTC`, `America/Chicago`, `US/Central` | Accepted | Accepted |
| Fixed offsets, such as `+05:30` or `-03:00` | Accepted | Refused (not accepted for this key) |
| Abbreviations, such as `CST`, `CDT`, `PST`, `PDT`, `EST`, `EDT`, `MST`, `MDT`, `HST` | Refused, with region-id suggestions | Refused, with region-id suggestions |
| Keywords, such as `SYSTEM`, `LOCAL`, `Z`, `SERVER` | Refused | Refused |
| Region id that is unknown to the JVM or has the wrong case, such as `utc` | Refused | Refused |
| Value with surrounding spaces | Refused | Refused |
| Unset, empty or blank | Refused | Refused |
| Key name that differs only in case, such as `database.connectionTimezone` | Refused, naming `database.connectionTimeZone` | Refused, naming `clickhouse.datetime.timezone` |

### Configuring the timezone for MySQL
The source zone is set with `database.connectionTimeZone` in the config.yml file.
The key is case-sensitive. `database.connectionTimezone` (lower-case z) is refused at start, and the message names the correct key.
The value is an IANA zone id (`US/Central`, `America/Chicago`, `UTC`) or a fixed offset (`+05:30`).
Abbreviations such as `CST`, `CDT` or `PST` are refused at start, because they do not name one zone.
The connector does not read the MySQL session zone. The value you set is the zone the connector uses.

### Configuring the ClickHouse timezone
The ClickHouse zone is set with `clickhouse.datetime.timezone` in the config.yml file.
It is the zone given to `DateTime/DateTime64` fields when they are persisted to ClickHouse.
The value is an IANA zone id only. Offsets, abbreviations and keywords are refused at start.

The connector does not use the ClickHouse server zone in place of this key. The key must be set, and the startup check
below compares the server zone with the configured zone.
## Checksum and load tools: `--source_timezone`
The checksum tools (`ch-mysql-checksum`, `ch-ch-checksum`, and the scripts they run) and the loader (`ch-mysql-load`, `ch-mysql-resync`)
take the zone the connector wrote MySQL `DATETIME` and `TIMESTAMP` values in as `--source_timezone <IANA zone>`. Pass the value of the
connector's `database.connectionTimeZone`, for example `--source_timezone America/Chicago`, or `--source_timezone UTC` when the connector
used UTC. The flag has no default, and no tool reads the MySQL server zone. A run without it is refused before any connection.

What the flag is trusted for:

- **Checksum tools: trusted, not validated.** The flag sets how DATETIME bounds are rendered for the comparison. A checksum does not record
  the zone a table was written in, so a flag that names the wrong zone cannot be detected. It shows up only as DIFFERENT verdicts on DATETIME values.
- **Loader and `ch-mysql-resync patch`: checked against a recorded zone.** A dump that records its own zone is loaded in that zone, and a flag
  that disagrees with it is refused. A dump without a recorded zone takes the flag as given. `ch-mysql-resync rewind-sql` takes no zone.

**Mixed zones.** One run compares one zone. A database whose tables were written under different connector zones needs one run per zone,
selected with `--tables_regex`. A table written under another zone is DIFFERENT, and a checksum cannot tell that from data drift, so each
DIFFERENT table gets a WARNING in the run summary. The WARNING names the table and the zone the run used. It does not name the other zone,
because the tool does not know it, and it does not change the exit code. Per-table zones are not implemented.

At start the connector reads `SELECT timezone()` and the zones declared by existing DateTime/DateTime64 columns, and
logs one WARN per existing column whose declared zone differs from the configured zone.
The connector still starts; the WARN is the signal to align the column type or the configured zone.
The sink does not change existing columns. Columns that the connector creates itself declare the configured zone,
so they cannot differ from it.
If the check cannot run, the connector logs a WARN and starts anyway.

## Debezium handling of time fields.
Its important to read this section to understand how the DATETIME/TIMESTAMP fields are handled by Debezium.
https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-temporal-types

```
The DATETIME type represents a local date and time such as "2018-01-13 09:48:27". As you can see, there is no time zone information. Such columns are converted into epoch milliseconds or microseconds based on the column’s precision by using UTC. The TIMESTAMP type represents a timestamp without time zone information. It is converted by MySQL from the server (or session’s) current time zone into UTC when writing and from UTC into the server (or session’s) current time zone when reading back the value. For example:

DATETIME with a value of 2018-06-20 06:37:03 becomes 1529476623000.

TIMESTAMP with a value of 2018-06-20 06:37:03 becomes 2018-06-20T13:37:03Z.

Such columns are converted into an equivalent io.debezium.time.ZonedTimestamp in UTC based on the server (or session’s) current time zone. The time zone will be queried from the server by default. If this fails, it must be specified explicitly by the database connectionTimeZone MySQL configuration option. For example, if the database’s time zone (either globally or configured for the connector by means of the connectionTimeZone option) is "America/Los_Angeles", the TIMESTAMP value "2018-06-20 06:37:03" is represented by a ZonedTimestamp with the value "2018-06-20T13:37:03Z".

```

The quoted text above describes the upstream Debezium default. This connector does not query the server or session zone
when `database.connectionTimeZone` is unset. The key is mandatory, so set it explicitly.

### Example of setting the timezone for MySQL and ClickHouse for DATETIME fields.

1. The environment variable `TZ=US/Central` in docker-compose under mysql can be used to set the timezone for MySQL.

2. To make sure the timezone is properly set in MySQL, run the following SQL  on MySQL Server.

 `select @@system_time_zone`

3. Set the `database.connectionTimeZone: 'US/Central'` in config.yml to make sure the sink connector performs the datetime/timestamp conversions using the same timezone.

### ClickHouse

To check the ClickHouse server timezone.
```
SELECT timezone()

Query id: f7b665b5-639f-4e1c-b494-256744c4310f

┌─timezone()──────┐
│ America/Chicago │
└─────────────────┘
```

Example of reading the same stored DATETIME value in two zones:
```
SELECT toDateTime(Mid_Value, 'UTC') AS utc_time
FROM temporal_types_DATETIME5

┌────────────utc_time─┐
│ 2022-09-29 06:50:28 │
└─────────────────────┘

SELECT toDateTime(Mid_Value, 'America/Chicago') AS chicago_time
FROM temporal_types_DATETIME5

┌────────chicago_time─┐
│ 2022-09-29 01:50:28 │
└─────────────────────┘
```

## Upgrading to 2.11.1: set these two keys
Both keys are required from 2.11.1. A configuration that leaves either key unset is refused at start, so add both keys
before you upgrade. A configuration that already sets both keys to a valid value (see the table above) needs no change.

Releases up to and including 2.11.0 did not require either key. When a key was absent, the connector behaved as follows.
This behaviour is replaced by the mandatory rule above:

- `clickhouse.datetime.timezone` unset: the connector read the ClickHouse server zone with `SELECT timezone()`, and used UTC if that query failed.
- `database.connectionTimeZone` unset: the source JDBC driver derived the zone from the MySQL session.

Both keys had an empty default in 2.11.0.

### Add the keys
Kafka Connect properties:
```
database.connectionTimeZone=UTC
clickhouse.datetime.timezone=UTC
```

Lightweight connector (config.yml):
```
database.connectionTimeZone: "UTC"
clickhouse.datetime.timezone: "UTC"
```

Ansible (deploy/ansible-systemd): set `mysql.source_mysql_timezone` and `clickhouse.timezone` for each source item.
The template no longer substitutes UTC when they are missing.

Helm (sink-connector-lightweight chart): set `connector.connectionTimeZone` and `connector.clickhouseDatetimeTimezone`.
Rendering fails when either value is missing or empty.

### What happens if a key is not added
The connector refuses to start and reports the missing key with the suggested value, as shown in the refusal message above.
It does not fall back to UTC, the server zone or the session zone.

### How to choose the value
Use UTC unless the existing data was written in another zone. Check before you choose another zone:

- On MySQL, run `SELECT @@GLOBAL.time_zone, @@SESSION.time_zone, @@GLOBAL.system_time_zone`. The value of
  `database.connectionTimeZone` should be the zone in which the DATETIME values were written.
- On ClickHouse, run `SELECT timezone()`, and check the zones declared by existing `DateTime` and `DateTime64` columns
  (for example in `system.columns`). The value of `clickhouse.datetime.timezone` should match them. The startup check
  logs an ERROR for each column whose zone differs.

Use an IANA region id for `clickhouse.datetime.timezone`. A fixed offset is accepted only for `database.connectionTimeZone`.

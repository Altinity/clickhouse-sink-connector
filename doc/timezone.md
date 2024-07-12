## Time Zone
Ideally the timezone of the source database(MySQL/PostgreSQL) should be the same as the timezone of ClickHouse.(Preferably UTC)
If for some reason they are different, the following instructions can be used to set the timezone of the source database and ClickHouse.

### Configuring the timezone for MySQL
The timezone of the source database(MySQL) can be set using the `database.connectionTimezone` configuration in the config.yml file.

## Overriding the ClickHouse server timezone
The timezone of ClickHouse can be set using the `clickhouse.datetime.timezone` configuration in the config.yml file.

## Debezium handling of time fields.
Its important to read this section to understand how the DATETIME/TIMESTAMP fields are handled by Debezium.
https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-temporal-types

```
The DATETIME type represents a local date and time such as "2018-01-13 09:48:27". As you can see, there is no time zone information. Such columns are converted into epoch milliseconds or microseconds based on the column’s precision by using UTC. The TIMESTAMP type represents a timestamp without time zone information. It is converted by MySQL from the server (or session’s) current time zone into UTC when writing and from UTC into the server (or session’s) current time zone when reading back the value. For example:

DATETIME with a value of 2018-06-20 06:37:03 becomes 1529476623000.

TIMESTAMP with a value of 2018-06-20 06:37:03 becomes 2018-06-20T13:37:03Z.

Such columns are converted into an equivalent io.debezium.time.ZonedTimestamp in UTC based on the server (or session’s) current time zone. The time zone will be queried from the server by default. If this fails, it must be specified explicitly by the database connectionTimeZone MySQL configuration option. For example, if the database’s time zone (either globally or configured for the connector by means of the connectionTimeZone option) is "America/Los_Angeles", the TIMESTAMP value "2018-06-20 06:37:03" is represented by a ZonedTimestamp with the value "2018-06-20T13:37:03Z".

```


### Example of setting the timezone for MySQL and ClickHouse for DATETIME fields.

1. The environment variable `TZ=US/Central` in docker-compose under mysql can be used to set the timezone for MySQL.

2. To make sure the timezone is properly set in MySQL, run the following SQL  on MySQL Server.

 `select @@system_time_zone`
 
3. Set the `database.connectionTimezone: 'US/Central'` in config.yml to make sure the sink connector performs the datetime/timestamp conversions using the same timezone.

Note: With the new version of Debezium there are no errors if the `database.connectionTimeZone` is not set and if the MySQL server is set to a different timezone
`TZ=US/Central`

### ClickHouse

To check the ClickHouse server timezone.
```
SELECT timezone()

Query id: f7b665b5-639f-4e1c-b494-256744c4310f

┌─timezone()──────┐
│ America/Chicago │
└─────────────────┘

```
The configuration variable `clickhouse.datetime.timezone: "UTC"` is to used to force the timezone for `DateTime/DateTime64` fields
when its persisted to ClickHouse. By Default the server timezone will be used.
```

SELECT toDateTime(Mid_Value, 'UTC') AS utc_time
FROM temporal_types_DATETIME5

Query id: f6c735bc-cdb3-4df6-995d-00dfe2d13ae8

┌────────────utc_time─┐
│ 2022-09-29 06:50:28 │
└─────────────────────┘

d1c194dbabc3 :) select toDateTime(Mid_Value, 'America/Chicago') as chicago_time from temporal_types_DATETIME5;

SELECT toDateTime(Mid_Value, 'America/Chicago') AS chicago_time
FROM temporal_types_DATETIME5

Query id: 77602998-f7f8-4bfb-b7a4-2a2034541c62

┌────────chicago_time─┐
│ 2022-09-29 01:50:28 │


SELECT *
FROM temporal_types_DATETIME5

Query id: c2f743df-f64e-4037-ad85-6363f9d7f11c

┌─Type────────┬─────────────Minimum_Value─┬─────────────────Mid_Value─┬─────────────Maximum_Value─┬─Null_Value─┬────────────_version─┬─is_deleted─┐
│ DATETIME(5) │ 1900-01-01 00:00:00.00000 │ 2022-09-29 01:50:28.12345 │ 2299-12-31 23:59:59.99999 │       ᴺᵁᴸᴸ │ 1730606158705590330 │          0 │
└─────────────┴───────────────────────────┴───────────────────────────┴───────────────────────────┴────────────┴─────────────────────┴────────────┘

```
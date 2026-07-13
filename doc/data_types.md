## MySQL Data Types
Refer [Debezium](https://debezium.io/documentation/reference/stable/connectors/mysql.html#mysql-supported-data-types) for detailed data types.

| MySQL              | Debezium                                     | ClickHouse                      |
|--------------------|------------------------------------------------------|---------------------------------|
| Bigint             | INT64\_SCHEMA                                        | Int64                           |
| Bigint Unsigned    | INT64\_SCHEMA                                        | UInt64                          |
| Blob               |                                                      | String + hex                    |
| Char               | String                                               | String / LowCardinality(String) |
| Date               | Schema: INT64<br>Name:<br>debezium.Date              | Date(6)                         |
| DateTime(0/1/2/3)  | Schema: INT64<br>Name: debezium.Timestamp            | DateTime64(0/1/2/3)             |
| DateTime(4/5/6)    | Schema: INT64<br>Name: debezium.MicroTimestamp       | DateTime64(4/5/6)               |
| Decimal(30,12)     | Schema: Bytes<br>Name:<br>kafka.connect.data.Decimal | Decimal(30,12)                  |
| Double             |                                                      | Float64                         |
| Int                | INT32                                                | Int32                           |
| Int Unsigned       | INT64                                                | UInt32                          |
| Longblob           |                                                      | String + hex                    |
| Mediumblob         |                                                      | String + hex                    |
| Mediumint          | INT32                                                | Int32                           |
| Mediumint Unsigned | INT32                                                | UInt32                          |
| Smallint           | INT16                                                | Int16                           |
| Smallint Unsigned  | INT32                                                | UInt16                          |
| Text               | String                                               | String                          |
| Time               |                                                      | String                          |
| Time(6)            |                                                      | String                          |
| Timestamp          |                                                      | DateTime64                      |
| Tinyint            | INT16                                                | Int8                            |
| Tinyint Unsigned   | INT16                                                | UInt8                           |
| varbinary(\*)      |                                                      | String + hex                    |
| varchar(\*)        |                                                      | String                          |
| JSON               |                                                      | String                          |
| BYTES              | BYTES, io.debezium.bits                              | String                          |
| YEAR               | INT32                                                | INT32                           |
| GEOMETRY           | Binary of WKB                                        | String                          |
| SET                |                                                      | Array(String)                   |
| ENUM               |                                                      | Array(String)                   |

> **Unsigned integers:** MySQL unsigned integers are promoted by Debezium to a
> wider signed Kafka Connect type (for example `INT UNSIGNED` becomes `INT64`
> and `SMALLINT UNSIGNED` becomes `INT32`), and that promotion is ambiguous.
> To preserve the unsigned range, the connector reads the original MySQL column
> type from the `__debezium.source.column.type` schema parameter and maps it to
> the corresponding ClickHouse `UInt` type.
>
> The lightweight (embedded) connector enables `column.propagate.source.type=.*`
> automatically. For the classic Kafka Connect sink connector you must enable
> source-type propagation on your **source** Debezium connector
> (`column.propagate.source.type=.*`); otherwise unsigned columns created from
> the record schema fall back to the signed ClickHouse types.


### PostgreSQL Data Types

| PostgreSQL Type           | Notes                                                                                 |
|---------------------------|---------------------------------------------------------------------------------------|
| `SMALLINT`                |                                                                              |
| `INTEGER`                 | Supported                                                                             |
| `BIGINT`                  | Supported                                                                             |
| `NUMERIC`                 | Supported                                                                             |
| `REAL`                    | Supported                                                                             |
| `DOUBLE PRECISION`        | Supported                                                                             |
| `BOOLEAN`                 | Supported                                                                             |
| `CHAR(n)`                 | Supported                                                                             |
| `VARCHAR(n)`              | Supported                                                                             |
| `TEXT`                    | Supported                                                                             |
| `BYTEA`                   | Supported                                                                             |
| `DATE`                    | Supported                                                                             |
| `TIME [ WITHOUT TIME ZONE ]` | Supported                                                                       |
| `TIME WITH TIME ZONE`     | Supported                                                                             |
| `TIMESTAMP [ WITHOUT TIME ZONE ]` | Supported                                                                 |
| `TIMESTAMP WITH TIME ZONE` | Supported                                                                          |
| `INTERVAL`                | Supported                                                                             |
| `UUID`                    | Supported                                                                             |
| `INET`                    | Supported                                                                             |
| `MACADDR`                 | Supported                                                                             |
| `JSON`                    | Supported                                                                             |
| `JSONB`                   | Supported                                                                             |
| `HSTORE`                  | Supported                                                                             |
| `ENUM`                    | Supported                                                                             |
| `ARRAY`                   | Supported, but arrays of unsupported types are not supported                          |
| `GEOMETRY` (PostGIS)      | Not supported                                                                         |
| `GEOGRAPHY` (PostGIS)     | Not supported                                                                         |
| `CITEXT`                  | Supported                                                                             |
| `BIT`                     | Not supported                                                                         |
| `BIT VARYING`             | Not supported                                                                         |
| `MONEY`                   | Not supported                                                                         |
| `XML`                     | Not supported                                                                         |
| `OID`                     | Not supported                                                                         |
| `UNSUPPORTED`             | Types other than those listed are not supported                                       |

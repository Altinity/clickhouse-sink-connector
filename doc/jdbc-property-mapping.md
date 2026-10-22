# ClickHouse JDBC property mapping (`clickhouse.jdbc.params`)

This page lists the three connection properties that older configurations set through
`clickhouse.jdbc.params`, and what the connector does with each one. The connector runs the
ClickHouse JDBC V2 driver (`com.clickhouse:clickhouse-jdbc` 0.9.8). The legacy V1 driver cannot
be selected: a configuration with `clickhouse.jdbc.v1` set to `true` is refused at start
(spec 03.08).

## Summary

| Property | V2 driver 0.9.8 | Connector behaviour | Operator action |
|---|---|---|---|
| `socket_timeout` | Supported. Integer, milliseconds. Stored default `0`. | Passed through unchanged. | Set it in milliseconds if you need a bound. The connector sets no default. |
| `connection_timeout` | Supported. Long, milliseconds. Applied as the HTTP connect timeout. | Passed through unchanged. | Set it in milliseconds if you need a bound. |
| `http_keep_alive_timeout` | Supported. Long, milliseconds. Applied as the HTTP keep-alive duration. | Passed through unchanged. | Optional. It is not verified as equivalent to `keepalive.timeout`. |
| `keepalive.timeout` | Not a V2 property. | Removed before the connection is created. A WARN is logged once per JVM. | Remove it. Setting it has no effect on V2. |
| `max_buffer_size` | Not a V2 property. | Removed before the connection is created. A WARN is logged once per JVM. | Remove it. |

The connector removes `keepalive.timeout` and `max_buffer_size` because the V2 driver rejects any
property it does not map, and the connection fails when one is present.

## Citations

Each row was checked against the bytecode of the driver jars. To reproduce a check, run
`javap -c -p -cp <jar> <class>` on the jar named in the table and search for the string.

- **V2 rejects unmapped properties.** `com.clickhouse.client.api.ClientConfigProperties`
  (client-v2 0.9.8) throws `Unknown and unmapped config properties` for keys it does not map,
  unless `ignore_unknown_config_key` is set. Verified in the bytecode (about offsets 306-343).
  The jar has no string `max_buffer_size` and no string `keepalive.timeout`.
- **`socket_timeout` (V2).** `ClientConfigProperties.SOCKET_OPERATION_TIMEOUT` uses key
  `socket_timeout`, type `Integer`, default `"0"`. `Client.Builder.setSocketTimeout(long, ChronoUnit)`
  converts with `Duration.toMillis()` and calls `setSocketTimeout(long)`, which stores the value
  as milliseconds.
- **`connection_timeout` (V2).** `ClientConfigProperties.CONNECTION_TIMEOUT`, type `Long`. The
  consumer in `HttpAPIClientHelper.createConnectionConfig` calls
  `ConnectionConfig.Builder.setConnectTimeout(ms, TimeUnit.MILLISECONDS)`.
- **`http_keep_alive_timeout` (V2).** `ClientConfigProperties.HTTP_KEEP_ALIVE_TIMEOUT`, type `Long`.
  `HttpAPIClientHelper.createHttpClient` builds a keep-alive strategy that returns
  `TimeValue.ofMilliseconds(value)`.
- **`socket_timeout` (V1, reference).** `com.clickhouse.client.config.ClickHouseClientOption`
  (V1 client jar, version not resolved in this check): `socket_timeout`, default `30000`,
  documented as "Socket timeout in milliseconds". The V2 default is `0`, so an unset value means a different behaviour on each driver.
- **`max_buffer_size` (V1, reference).** The same class: `max_buffer_size`, default `1048576`,
  documented as "Maximum buffer size in byte can be used for streaming". No V2 property with this
  meaning was found. `client_network_buffer_size` is a V2 property, but its meaning was not
  established as equal to `max_buffer_size`, so it is not mapped.
- **`keepalive.timeout`.** No V2 or V1 property with this name was found in the client-v1,
  client-v2 or JDBC jars for 0.9.8. Its effect on any driver is not established.

## Timeout errors

For a `Connect to timed out` or a socket timeout error on V2, set `socket_timeout` and
`connection_timeout` in milliseconds, for example
`clickhouse.jdbc.params: "socket_timeout=30000,connection_timeout=30000"`. Choose the values to be
longer than the longest expected INSERT.

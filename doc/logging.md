## Lightweight Sink Connector Logging

Sink connector uses the log4j version 2 directly, however the dependendent libraries like debezium and clickhouse-jdbc
use different logging frameworks(JUL, slf4j).
There is logic to use adapters to bridge the logging frameworks.

Logging is controlled by the [`log4j2.xml` ](https://github.com/Altinity/clickhouse-sink-connector/blob/develop/sink-connector-lightweight/docker/log4j2.xml)file in the 'docker' directory.
This file is mounted into the container and is passed to the JVM as a system property.(-Dlog4j.configurationFile)

```
version: "3.4"

services:
  clickhouse-sink-connector-lt:
    image: ${CLICKHOUSE_SINK_CONNECTOR_LT_IMAGE}
    entrypoint: ["sh", "-c", "java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -Xms4g -Xmx4g -Dlog4j2.configurationFile=log4j2.xml -jar /app.jar /config.yml com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication"]
    restart: "no"
    ports:
      - "8083:8083"
      - "5005:5005"
      - "7000:7000"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - ./log4j2.xml:/log4j2.xml
      - ./config.yml:/config.yml
```

### Log Levels
If you need to change the Logging level , you can modify the rootLogger level in the [`log4j2.xml` ](https://github.com/Altinity/clickhouse-sink-connector/blob/develop/sink-connector-lightweight/docker/log4j2.xml) file.
By default its set to `info` level.

```xml

    <Root level="info" additivity="false">
```

## Changing Layout (Example JSON)
If you want to change the layout of the logs, you can modify the [`log4j2.xml` ](https://github.com/Altinity/clickhouse-sink-connector/blob/develop/sink-connector-lightweight/docker/log4j2.xml) file.
You can comment out the default `PatternLayout` and enable `JSONLayout`

```
        <Console name="console" target="SYSTEM_OUT">
            <!-- <JSONLayout compact="true" eventEol="true" properties="true" stacktraceAsString="true" includeTimeMillis="true" /> -->

            <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level - %msg%n"/>
        </Console>
```

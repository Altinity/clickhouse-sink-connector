FROM openjdk:17
COPY sink-connector-client/sink-connector-client /sink-connector-client
COPY sink-connector-lightweight/target/clickhouse-debezium-embedded-*.jar /app.jar
ENV JAVA_OPTS="-Dlog4jDebug=true"
ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-jar","/app.jar", "/config.yml", "com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication"]

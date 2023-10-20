FROM openjdk:11
COPY sink-connector-client/sink-connector-client /sink-connector-client
COPY sink-connector-lightweight/target/clickhouse-debezium-embedded-1.0-SNAPSHOT.jar /app.jar
ENV JAVA_OPTS="-Dlog4jDebug=true"
ENTRYPOINT ["java", "$JAVA_OPTS", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-jar","/app.jar", "/config.yml", "com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication"]

FROM openjdk:11
COPY target/clickhouse-debezium-embedded-1.0-SNAPSHOT.jar /app.jar
ENV JAVA_OPTS="-Dlog4jDebug=true"
ENTRYPOINT ["java", "-jar","/app.jar", "com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication"]
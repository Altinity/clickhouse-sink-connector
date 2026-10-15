FROM openjdk:17
COPY sink-connector-client/sink-connector-client /sink-connector-client
COPY sink-connector-lightweight/target/clickhouse-debezium-embedded*.jar /app.jar
# MySQL Connector/J (GPL-2.0 + Universal FOSS Exception) is not part of the
# Apache-2.0 jar; it ships as a separate file next to /app.jar, whose manifest
# Class-Path loads it (doc/licensing.md).
COPY sink-connector-lightweight/target/mysql-driver/mysql-connector-j.jar /mysql-connector-j.jar
# Third-party notices, license texts and the driver's corresponding source.
COPY THIRD_PARTY_NOTICES.md /licenses/THIRD_PARTY_NOTICES.md
COPY licenses/ /licenses/
COPY sink-connector-lightweight/target/mysql-driver/mysql-connector-j-*-sources.jar /licenses/mysql-connector-j/
ENV JAVA_OPTS="-Dlog4jDebug=true"
ENTRYPOINT ["java", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-jar","/app.jar", "/config.yml", "com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication"]

FROM debezium/connect-base:1.9

LABEL maintainer="Altinity"

COPY deploy/libs/* /kafka/connect/clickhouse-kafka-sink-connector/
COPY target/*.jar  /kafka/connect/clickhouse-kafka-sink-connector/

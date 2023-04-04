ARG DEBEZIUM_VERSION
FROM quay.io/debezium/connect:${DEBEZIUM_VERSION}

ARG JMX_AGENT_VERSION
RUN mkdir /kafka/etc && cd /kafka/etc &&\
        curl -so jmx_prometheus_javaagent.jar \
        https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/$JMX_AGENT_VERSION/jmx_prometheus_javaagent-$JMX_AGENT_VERSION.jar

COPY config.yml /kafka/etc/config.yml
USER root:root
# Copy confluent
# https://github.com/debezium/container-images/blob/main/connect-base/1.2/Dockerfile
RUN 'curl' '-L' '--output' '/kafka/libs/kafka-connect-avro-converter-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-connect-avro-converter/7.2.1/kafka-connect-avro-converter-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/libs/kafka-avro-serializer-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-avro-serializer/7.2.1/kafka-avro-serializer-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/libs/kafka-schema-registry-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-schema-registry/7.2.1/kafka-schema-registry-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/libs/kafka-schema-serializer-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-schema-serializer/7.2.1/kafka-schema-serializer-7.2.1.jar'

RUN 'curl' '-L' '--output' '/kafka/libs/kafka-schema-registry-client-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-schema-registry-client/7.2.1/kafka-schema-registry-client-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/libs/common-config-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/common-config/7.2.1/common-config-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/libs/common-utils-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/common-utils/7.2.1/common-utils-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/libs/kafka-serde-tools-package-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-serde-tools-package/7.2.1/kafka-serde-tools-package-7.2.1.jar'


RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-mysql/failureaccess-1.0.jar' 'https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0/failureaccess-1.0.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-mysql/guava-31.1-jre.jar' 'https://repo1.maven.org/maven2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-mysql/avro-1.11.1.jar' 'https://repo1.maven.org/maven2/org/apache/avro/avro/1.11.1/avro-1.11.1.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-mysql/kafka-connect-avro-data-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-connect-avro-data/7.2.1/kafka-connect-avro-data-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-mysql/kafka-schema-converter-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-schema-converter/7.2.1/kafka-schema-converter-7.2.1.jar'

## Link the same libraries for postgres

RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-postgres/failureaccess-1.0.jar' 'https://repo1.maven.org/maven2/com/google/guava/failureaccess/1.0/failureaccess-1.0.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-postgres/guava-31.1-jre.jar' 'https://repo1.maven.org/maven2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-postgres/avro-1.11.1.jar' 'https://repo1.maven.org/maven2/org/apache/avro/avro/1.11.1/avro-1.11.1.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-postgres/kafka-connect-avro-data-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-connect-avro-data/7.2.1/kafka-connect-avro-data-7.2.1.jar'
RUN 'curl' '-L' '--output' '/kafka/connect/debezium-connector-postgres/kafka-schema-converter-7.2.1.jar' 'https://packages.confluent.io/maven/io/confluent/kafka-schema-converter/7.2.1/kafka-schema-converter-7.2.1.jar'


#RUN ["ln", "-s", "/kafka/connect/debezium-connector-mysql/failureaccess-1.0.jar", "/kafka/connect/debezium-connector-postgres/failureaccess-1.0.jar"]
#RUN ["ln", "-s", "/kafka/connect/debezium-connector-mysql/guava-31.1-jre.jar", "/kafka/connect/debezium-connector-postgres/guava-31.1-jre.jar"]
#RUN ["ln", "-s", "/kafka/connect/debezium-connector-mysql/avro-1.11.1.jar", "/kafka/connect/debezium-connector-postgres/avro-1.11.1.jar"]
#RUN ["ln", "-s", "/kafka/connect/debezium-connector-mysql/kafka-connect-avro-data-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-connect-avro-data-7.2.1.jar"]
#RUN ["ln", "-s", "/kafka/connect/debezium-connector-mysql/kafka-schema-converter-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-schema-converter-7.2.1.jar"]

RUN ["cp", "/kafka/libs/kafka-connect-avro-converter-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/kafka-connect-avro-converter-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-avro-serializer-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/kafka-avro-serializer-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-schema-registry-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/kafka-schema-registry-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-schema-serializer-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/kafka-schema-serializer-7.2.1.jar"]

RUN ["cp", "/kafka/libs/kafka-schema-registry-client-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/kafka-schema-registry-client-7.2.1.jar"]
RUN ["cp", "/kafka/libs/common-config-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/common-config-7.2.1.jar"]
RUN ["cp", "/kafka/libs/common-utils-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/common-utils-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-serde-tools-package-7.2.1.jar", "/kafka/connect/debezium-connector-mysql/kafka-serde-tools-package-7.2.1.jar"]


RUN ["cp", "/kafka/libs/kafka-connect-avro-converter-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-connect-avro-converter-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-avro-serializer-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-avro-serializer-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-schema-registry-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-schema-registry-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-schema-serializer-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-schema-serializer-7.2.1.jar"]

RUN ["cp", "/kafka/libs/kafka-schema-registry-client-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-schema-registry-client-7.2.1.jar"]
RUN ["cp", "/kafka/libs/common-config-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/common-config-7.2.1.jar"]
RUN ["cp", "/kafka/libs/common-utils-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/common-utils-7.2.1.jar"]
RUN ["cp", "/kafka/libs/kafka-serde-tools-package-7.2.1.jar", "/kafka/connect/debezium-connector-postgres/kafka-serde-tools-package-7.2.1.jar"]

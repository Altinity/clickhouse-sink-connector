# Third-Party License Attribution

This project uses third-party software components. The project itself is licensed under the Apache License 2.0; see [`LICENSE`](LICENSE).

This notice is based on the runtime components present in the ClickHouse Sink Connector 2.10.3 release artifacts (Kafka connector JAR, lightweight connector JAR, and linked Go client binary), repository-managed runtime bundles copied or downloaded by Docker build definitions, and the direct dependencies declared by the installable `ch-sink-tools` Python package. Test-only and build-tool dependencies are excluded. Base-image and operating-system packages are not duplicated here because they carry their own image/package notices. Python dependency ranges that are not pinned are shown as declared; their transitive dependency versions are therefore not enumerated.

Where a dependency is offered under multiple licenses, this notice records the license option selected for this distribution. The selection does not change upstream licensing; it documents which available option this project relies on.

**Important:** this file is an attribution/source-availability manifest, not by itself proof that every binary release satisfies all source-delivery or relinking requirements. GPL/LGPL and other copyleft obligations must also be implemented in the release packaging process where noted below.

## Summary

| License | Packages |
| --- | ---: |
| Apache-2.0 | 148 |
| BSD-3-Clause | 16 |
| EPL-2.0 | 15 |
| MIT | 12 |
| BSD-2-Clause | 4 |
| CDDL-1.1 | 3 |
| GPL-2.0-only WITH Universal-FOSS-exception-1.0 | 2 |
| CC0-1.0 | 1 |
| CDDL-1.0 | 1 |
| Confluent Community License 1.0 | 1 |
| CPL-1.0 | 1 |
| LGPL-2.1-or-later | 1 |
| LGPL-3.0-or-later WITH OpenSSL-exception | 1 |
| MPL-2.0 | 1 |
| Public Domain | 1 |
| **Total** | **208** |

## License elections

For dependencies that permit a choice, this distribution records the following selections:

| Component/family | Upstream alternatives | Selected for this distribution |
| --- | --- | --- |
| Jetty / Jetty WebSocket 9.4.56 and 11.0.15 components | EPL or Apache dual license | **Apache-2.0** |
| org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api:5.0.2 | Apache-2.0 or EPL-1.0 | **Apache-2.0** |
| org.javassist:javassist:3.29.0-GA | MPL-1.1 / LGPL-2.1 / Apache-2.0 | **Apache-2.0** |
| org.locationtech.jts:jts-core:1.18.2 | EPL-2.0 or Eclipse Distribution License 1.0 | **BSD-3-Clause (EDL 1.0 permissive option)** |
| org.hdrhistogram:HdrHistogram:2.1.12 | CC0-1.0 or BSD-2-Clause | **BSD-2-Clause** |
| org.reflections:reflections:0.10.2 | WTFPL or Apache-2.0 | **Apache-2.0** |
| Jakarta Annotation / Transaction / WS-RS and HK2/Jersey 2.x components listed below | EPL-2.0 or GPL-2.0 with Classpath Exception | **EPL-2.0** |
| javax.activation-api 1.2.0, jaxb-api 2.3.1, javax.json 1.1.4 | CDDL-1.1 or GPL-2.0 with Classpath Exception | **CDDL-1.1** |

The full package table below has been normalized to the selected license, so dual-license strings are not left ambiguous.

## Third-party packages

| Module | Licenses | License file |
| --- | --- | --- |
| aopalliance:aopalliance:1.0 | Public Domain | http://aopalliance.sourceforge.net/ |
| at.yawk.lz4:lz4-java:1.10.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| ch.qos.reload4j:reload4j:1.2.25 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.alibaba:fastjson:1.2.83 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.clickhouse:clickhouse-client:0.9.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.clickhouse:clickhouse-data:0.9.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.clickhouse:clickhouse-http-client:0.9.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.clickhouse:clickhouse-jdbc:0.9.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.clickhouse:client-v2:0.9.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.clickhouse:jdbc-v2:0.9.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.core:jackson-annotations:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.core:jackson-annotations:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.core:jackson-core:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.core:jackson-core:2.20.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.core:jackson-databind:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.core:jackson-databind:2.20.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.12.6 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.jaxrs:jackson-jaxrs-base:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.module:jackson-module-afterburner:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.module:jackson-module-afterburner:2.16.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.fasterxml.jackson.module:jackson-module-jaxb-annotations:2.13.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.github.luben:zstd-jni:1.5.6-3 | BSD-2-Clause | https://opensource.org/license/bsd-2-clause |
| com.google.code.findbugs:jsr305:3.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.errorprone:error_prone_annotations:2.16 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.errorprone:error_prone_annotations:2.23.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.errorprone:error_prone_annotations:2.36.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:failureaccess:1.0.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:failureaccess:1.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:failureaccess:1.0.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:guava:30.0-jre | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:guava:31.0.1-jre | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:guava:33.0.0-jre | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:guava:33.4.6-jre | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.inject:guice:6.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.j2objc:j2objc-annotations:1.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.j2objc:j2objc-annotations:2.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.j2objc:j2objc-annotations:3.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.protobuf:protobuf-java:3.19.6 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| com.googlecode.json-simple:json-simple:1.1.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.mysql:mysql-connector-j:9.1.0 | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | https://github.com/mysql/mysql-connector-j/blob/9.1.0/LICENSE |
| com.yammer.metrics:metrics-core:2.2.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.zaxxer:HikariCP:6.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-codec:commons-codec:1.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-codec:commons-codec:1.19.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-io:commons-io:2.11.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-io:commons-io:2.20.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| filippo.io/edwards25519@v1.1.0 | BSD-3-Clause | https://github.com/FiloSottile/edwards25519/blob/v1.1.0/LICENSE |
| github.com/cpuguy83/go-md2man/v2@v2.0.2 | MIT | https://github.com/cpuguy83/go-md2man/blob/v2.0.2/LICENSE.md |
| github.com/go-sql-driver/mysql@v1.8.1 | MPL-2.0 | https://github.com/go-sql-driver/mysql/blob/v1.8.1/LICENSE |
| github.com/google/go-querystring@v1.0.0 | BSD-3-Clause | https://github.com/google/go-querystring/blob/v1.0.0/LICENSE |
| github.com/levigross/grequests@v0.0.0-20221222020224-9eee758d18d5 | Apache-2.0 | https://github.com/levigross/grequests/blob/9eee758d18d5/LICENSE |
| github.com/russross/blackfriday/v2@v2.1.0 | BSD-2-Clause | https://github.com/russross/blackfriday/blob/v2.1.0/LICENSE.txt |
| github.com/tidwall/pretty@v1.2.1 | MIT | https://github.com/tidwall/pretty/blob/v1.2.1/LICENSE |
| github.com/urfave/cli@v1.22.13 | MIT | https://github.com/urfave/cli/blob/v1.22.13/LICENSE |
| golang.org/x/net@v0.0.0-20181011144130-49bb7cea24b1 | BSD-3-Clause | https://github.com/golang/net/blob/49bb7cea24b1/LICENSE |
| io.apicurio:apicurio-registry-distro-connect-converter:2.1.5.Final | Apache-2.0 | https://github.com/Apicurio/apicurio-registry/blob/2.1.5.Final/LICENSE |
| io.confluent:common-config:7.2.1 | Apache-2.0 | https://github.com/confluentinc/common/blob/v7.2.1/LICENSE |
| io.confluent:common-utils:7.2.1 | Apache-2.0 | https://github.com/confluentinc/common/blob/v7.2.1/LICENSE |
| io.confluent:kafka-avro-serializer:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/avro-serializer/LICENSE |
| io.confluent:kafka-connect-avro-converter:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/avro-converter/LICENSE |
| io.confluent:kafka-schema-registry-client:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/client/LICENSE |
| io.confluent:kafka-schema-registry:7.2.1 | Confluent Community License 1.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/core/LICENSE |
| io.confluent:kafka-serde-tools-package:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/package-kafka-serde-tools/LICENSE |
| io.debezium:debezium-api:1.8.1.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-api:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-binlog:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-mysql:1.8.1.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-mysql:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-postgres:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-core:1.8.1.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-core:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-ddl-parser:1.8.1.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-ddl-parser:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-embedded:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-storage-file:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-storage-jdbc:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-storage-kafka:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:mysql-binlog-connector-java:0.25.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:mysql-binlog-connector-java:0.40.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.dropwizard.metrics:metrics-core:4.2.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.dropwizard.metrics:metrics-jmx:4.2.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.dropwizard.metrics:metrics-jvm:4.2.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.javalin:javalin:5.5.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.micrometer:micrometer-core:1.9.5 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.micrometer:micrometer-registry-prometheus:1.9.5 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient:0.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient:0.15.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_common:0.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_common:0.15.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_tracer_common:0.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_tracer_common:0.15.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_tracer_otel:0.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_tracer_otel:0.15.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_tracer_otel_agent:0.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.prometheus:simpleclient_tracer_otel_agent:0.15.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.swagger.core.v3:swagger-annotations:2.2.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| jakarta.activation:jakarta.activation-api:1.2.1 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| jakarta.annotation:jakarta.annotation-api:1.3.5 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| jakarta.inject:jakarta.inject-api:2.0.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| jakarta.transaction:jakarta.transaction-api:1.3.3 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| jakarta.validation:jakarta.validation-api:2.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| jakarta.ws.rs:jakarta.ws.rs-api:2.1.6 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| jakarta.xml.bind:jakarta.xml.bind-api:2.3.3 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| javax.activation:activation:1.1.1 | CDDL-1.0 | https://opensource.org/license/cddl-1-0 |
| javax.activation:javax.activation-api:1.2.0 | CDDL-1.1 | https://spdx.org/licenses/CDDL-1.1.html |
| javax.xml.bind:jaxb-api:2.3.1 | CDDL-1.1 | https://spdx.org/licenses/CDDL-1.1.html |
| junit:junit:4.10 | CPL-1.0 | https://opensource.org/license/cpl-1-0 |
| mysql:mysql-connector-java:8.0.27 | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | https://github.com/mysql/mysql-connector-j/blob/8.0.27/LICENSE |
| org.antlr:antlr4-runtime:4.13.1 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.antlr:antlr4-runtime:4.13.2 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.antlr:antlr4-runtime:4.8 | BSD-3-Clause | https://github.com/antlr/antlr4/blob/4.8/LICENSE.txt |
| org.apache.avro:avro:1.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.commons:commons-compress:1.21 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.commons:commons-compress:1.24.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.commons:commons-lang3:3.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.httpcomponents.client5:httpclient5:5.4.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.httpcomponents.core5:httpcore5-h2:5.3.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.httpcomponents.core5:httpcore5:5.3.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:connect-api:3.8.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:connect-file:3.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:connect-json:3.8.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:connect-runtime:3.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:connect-transforms:3.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:kafka-clients:3.8.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.kafka:kafka-server-common:3.3.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.logging.log4j:log4j-api:2.23.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.logging.log4j:log4j-core:2.23.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.logging.log4j:log4j-jul:2.23.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.maven:maven-artifact:3.8.6 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.maven:maven-artifact:3.9.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.bitbucket.b_c:jose4j:0.9.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.checkerframework:checker-qual:3.26.0 | MIT | https://opensource.org/license/mit |
| org.codehaus.plexus:plexus-utils:3.3.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.codehaus.plexus:plexus-utils:3.5.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api:5.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-core-common:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-core-server:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-jetty-api:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-jetty-common:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-jetty-server:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-servlet:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-annotations:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-client:9.4.56.v20240826 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-continuation:9.4.56.v20240826 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-http:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-io:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-jndi:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-plus:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-security:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-server:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-servlet:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-servlets:9.4.56.v20240826 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-util:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-webapp:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty:jetty-xml:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.glassfish.hk2.external:aopalliance-repackaged:2.6.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.hk2.external:jakarta.inject:2.6.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.hk2:hk2-api:2.6.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.hk2:hk2-locator:2.6.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.hk2:hk2-utils:2.6.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.hk2:osgi-resource-locator:1.0.3 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.jersey.containers:jersey-container-servlet-core:2.39.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.jersey.containers:jersey-container-servlet:2.39.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.jersey.core:jersey-client:2.39.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.jersey.core:jersey-common:2.39.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.jersey.core:jersey-server:2.39.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish.jersey.inject:jersey-hk2:2.39.1 | EPL-2.0 | https://www.eclipse.org/legal/epl-2.0/ |
| org.glassfish:javax.json:1.1.4 | CDDL-1.1 | https://spdx.org/licenses/CDDL-1.1.html |
| org.hamcrest:hamcrest-core:1.1 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.hdrhistogram:HdrHistogram:2.1.12 | BSD-2-Clause | https://opensource.org/license/bsd-2-clause |
| org.javassist:javassist:3.29.0-GA | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib-common:1.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.7.20 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.20 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib:1.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains:annotations:17.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jspecify:jspecify:1.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.latencyutils:LatencyUtils:2.0.3 | CC0-1.0 | https://creativecommons.org/publicdomain/zero/1.0/ |
| org.locationtech.jts:jts-core:1.18.2 | BSD-3-Clause | https://github.com/locationtech/jts/blob/jts-1.18.2/LICENSE_EDLv1.txt |
| org.lz4:lz4-java:1.8.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.mariadb.jdbc:mariadb-java-client:2.7.12 | LGPL-2.1-or-later | https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html |
| org.ow2.asm:asm-commons:9.4 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.ow2.asm:asm-tree:9.4 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.ow2.asm:asm:9.4 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.postgresql:postgresql:42.5.0 | BSD-2-Clause | https://opensource.org/license/bsd-2-clause |
| org.reflections:reflections:0.10.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.roaringbitmap:RoaringBitmap:1.0.6 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.slf4j:slf4j-api:1.7.36 | MIT | https://opensource.org/license/mit |
| org.slf4j:slf4j-api:2.0.12 | MIT | https://opensource.org/license/mit |
| org.slf4j:slf4j-api:2.0.7 | MIT | https://opensource.org/license/mit |
| org.slf4j:slf4j-simple:2.0.12 | MIT | https://opensource.org/license/mit |
| org.xerial.snappy:snappy-java:1.1.8.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.yaml:snakeyaml:2.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| pypi:antlr4-python3-runtime==4.11.1 | BSD-3-Clause | https://github.com/antlr/antlr4/blob/4.11.1/LICENSE.txt |
| pypi:clickhouse-driver>=0.2.9 | MIT | https://github.com/mymarilyn/clickhouse-driver/blob/master/LICENSE |
| pypi:pandas | BSD-3-Clause | https://github.com/pandas-dev/pandas/blob/main/LICENSE |
| pypi:psycopg2-binary | LGPL-3.0-or-later WITH OpenSSL-exception | https://github.com/psycopg/psycopg2/blob/master/LICENSE |
| pypi:PyMySQL | MIT | https://github.com/PyMySQL/PyMySQL/blob/main/LICENSE |
| pypi:PyYAML | MIT | https://github.com/yaml/pyyaml/blob/main/LICENSE |
| pypi:SQLAlchemy>=1.4 | MIT | https://github.com/sqlalchemy/sqlalchemy/blob/main/LICENSE |

## Compliance references

- [GNU GPL 2.0](https://www.gnu.org/licenses/old-licenses/gpl-2.0.html)
- [GNU LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html)
- [GNU LGPL 3.0](https://www.gnu.org/licenses/lgpl-3.0.html)
- [Universal FOSS Exception 1.0](https://oss.oracle.com/licenses/universal-foss-exception/)
- [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/)
- [Eclipse Public License 2.0](https://www.eclipse.org/legal/epl/epl-v20.html)
- [CDDL 1.0](https://opensource.org/license/CDDL-1.0)
- [CPL 1.0](https://opensource.org/license/CPL-1.0)
- [Confluent Community License FAQ](https://www.confluent.io/confluent-community-license-faq/)

## Copyleft and source-available components

The following components still carry copyleft or source-available obligations after the license elections above. The source links identify exact upstream source where the version is pinned. For GPL/LGPL components, release engineering must use a delivery method that actually satisfies the applicable license; an upstream URL in this notice alone should not be treated as a substitute for required corresponding-source/relink materials.

| Module | Selected license | Source availability | Distribution/release handling |
| --- | --- | --- | --- |
| com.mysql:mysql-connector-j:9.1.0 | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | [https://github.com/mysql/mysql-connector-j/tree/9.1.0](https://github.com/mysql/mysql-connector-j/tree/9.1.0) | GPLv2 + UFE: publish/provide complete corresponding source with the binary using a GPL-compliant delivery method; include GPLv2 and UFE text. |
| github.com/go-sql-driver/mysql@v1.8.1 | MPL-2.0 | [https://github.com/go-sql-driver/mysql/tree/v1.8.1](https://github.com/go-sql-driver/mysql/tree/v1.8.1) | MPL 2.0: include MPL text and inform recipients where the exact Covered Software source can be obtained. |
| io.confluent:kafka-schema-registry:7.2.1 | Confluent Community License 1.0 | [https://github.com/confluentinc/schema-registry/tree/v7.2.1](https://github.com/confluentinc/schema-registry/tree/v7.2.1) | Source-available, not OSI open source. Review the competing-SaaS restriction for the intended deployment/distribution; remove if not needed. |
| jakarta.annotation:jakarta.annotation-api:1.3.5 | EPL-2.0 | [https://repo1.maven.org/maven2/jakarta/annotation/jakarta.annotation-api/1.3.5/jakarta.annotation-api-1.3.5-sources.jar](https://repo1.maven.org/maven2/jakarta/annotation/jakarta.annotation-api/1.3.5/jakarta.annotation-api-1.3.5-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| jakarta.transaction:jakarta.transaction-api:1.3.3 | EPL-2.0 | [https://repo1.maven.org/maven2/jakarta/transaction/jakarta.transaction-api/1.3.3/jakarta.transaction-api-1.3.3-sources.jar](https://repo1.maven.org/maven2/jakarta/transaction/jakarta.transaction-api/1.3.3/jakarta.transaction-api-1.3.3-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| jakarta.ws.rs:jakarta.ws.rs-api:2.1.6 | EPL-2.0 | [https://repo1.maven.org/maven2/jakarta/ws/rs/jakarta.ws.rs-api/2.1.6/jakarta.ws.rs-api-2.1.6-sources.jar](https://repo1.maven.org/maven2/jakarta/ws/rs/jakarta.ws.rs-api/2.1.6/jakarta.ws.rs-api-2.1.6-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| javax.activation:activation:1.1.1 | CDDL-1.0 | [https://repo1.maven.org/maven2/javax/activation/activation/1.1.1/activation-1.1.1-sources.jar](https://repo1.maven.org/maven2/javax/activation/activation/1.1.1/activation-1.1.1-sources.jar) | CDDL 1.0: preserve notices and make the corresponding Covered Software source available. |
| javax.activation:javax.activation-api:1.2.0 | CDDL-1.1 | [https://repo1.maven.org/maven2/javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0-sources.jar](https://repo1.maven.org/maven2/javax/activation/javax.activation-api/1.2.0/javax.activation-api-1.2.0-sources.jar) | CDDL 1.1 elected: preserve notices and make the corresponding Covered Software source available. |
| javax.xml.bind:jaxb-api:2.3.1 | CDDL-1.1 | [https://repo1.maven.org/maven2/javax/xml/bind/jaxb-api/2.3.1/jaxb-api-2.3.1-sources.jar](https://repo1.maven.org/maven2/javax/xml/bind/jaxb-api/2.3.1/jaxb-api-2.3.1-sources.jar) | CDDL 1.1 elected: preserve notices and make the corresponding Covered Software source available. |
| junit:junit:4.10 | CPL-1.0 | [https://repo1.maven.org/maven2/junit/junit/4.10/junit-4.10-sources.jar](https://repo1.maven.org/maven2/junit/junit/4.10/junit-4.10-sources.jar) | CPL 1.0: include notice and state how the corresponding source can be obtained. Prefer removing this test library from runtime if unnecessary. |
| mysql:mysql-connector-java:8.0.27 | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | [https://github.com/mysql/mysql-connector-j/tree/8.0.27](https://github.com/mysql/mysql-connector-j/tree/8.0.27) | GPLv2 + UFE: publish/provide complete corresponding source with the binary using a GPL-compliant delivery method; include GPLv2 and UFE text. |
| org.glassfish.hk2.external:aopalliance-repackaged:2.6.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/hk2/external/aopalliance-repackaged/2.6.1/aopalliance-repackaged-2.6.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/hk2/external/aopalliance-repackaged/2.6.1/aopalliance-repackaged-2.6.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.hk2.external:jakarta.inject:2.6.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/hk2/external/jakarta.inject/2.6.1/jakarta.inject-2.6.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/hk2/external/jakarta.inject/2.6.1/jakarta.inject-2.6.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.hk2:hk2-api:2.6.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/hk2/hk2-api/2.6.1/hk2-api-2.6.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/hk2/hk2-api/2.6.1/hk2-api-2.6.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.hk2:hk2-locator:2.6.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/hk2/hk2-locator/2.6.1/hk2-locator-2.6.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/hk2/hk2-locator/2.6.1/hk2-locator-2.6.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.hk2:hk2-utils:2.6.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/hk2/hk2-utils/2.6.1/hk2-utils-2.6.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/hk2/hk2-utils/2.6.1/hk2-utils-2.6.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.hk2:osgi-resource-locator:1.0.3 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/hk2/osgi-resource-locator/1.0.3/osgi-resource-locator-1.0.3-sources.jar](https://repo1.maven.org/maven2/org/glassfish/hk2/osgi-resource-locator/1.0.3/osgi-resource-locator-1.0.3-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.jersey.containers:jersey-container-servlet-core:2.39.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/jersey/containers/jersey-container-servlet-core/2.39.1/jersey-container-servlet-core-2.39.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/jersey/containers/jersey-container-servlet-core/2.39.1/jersey-container-servlet-core-2.39.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.jersey.containers:jersey-container-servlet:2.39.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/jersey/containers/jersey-container-servlet/2.39.1/jersey-container-servlet-2.39.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/jersey/containers/jersey-container-servlet/2.39.1/jersey-container-servlet-2.39.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.jersey.core:jersey-client:2.39.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/jersey/core/jersey-client/2.39.1/jersey-client-2.39.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/jersey/core/jersey-client/2.39.1/jersey-client-2.39.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.jersey.core:jersey-common:2.39.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/jersey/core/jersey-common/2.39.1/jersey-common-2.39.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/jersey/core/jersey-common/2.39.1/jersey-common-2.39.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.jersey.core:jersey-server:2.39.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/jersey/core/jersey-server/2.39.1/jersey-server-2.39.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/jersey/core/jersey-server/2.39.1/jersey-server-2.39.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish.jersey.inject:jersey-hk2:2.39.1 | EPL-2.0 | [https://repo1.maven.org/maven2/org/glassfish/jersey/inject/jersey-hk2/2.39.1/jersey-hk2-2.39.1-sources.jar](https://repo1.maven.org/maven2/org/glassfish/jersey/inject/jersey-hk2/2.39.1/jersey-hk2-2.39.1-sources.jar) | EPL 2.0 elected: preserve notices and make the exact Program source reasonably available to recipients. |
| org.glassfish:javax.json:1.1.4 | CDDL-1.1 | [https://repo1.maven.org/maven2/org/glassfish/javax.json/1.1.4/javax.json-1.1.4-sources.jar](https://repo1.maven.org/maven2/org/glassfish/javax.json/1.1.4/javax.json-1.1.4-sources.jar) | CDDL 1.1 elected: preserve notices and make the corresponding Covered Software source available. |
| org.mariadb.jdbc:mariadb-java-client:2.7.12 | LGPL-2.1-or-later | [https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/2.7.12/mariadb-java-client-2.7.12-sources.jar](https://repo1.maven.org/maven2/org/mariadb/jdbc/mariadb-java-client/2.7.12/mariadb-java-client-2.7.12-sources.jar) | LGPL 2.1: include LGPL text and library source; preserve a practical ability to replace/relink the LGPL library. Prefer an unshaded separate JAR. |
| pypi:psycopg2-binary | LGPL-3.0-or-later WITH OpenSSL-exception | [https://github.com/psycopg/psycopg2](https://github.com/psycopg/psycopg2) | LGPL 3: if this binary wheel/package is redistributed, include applicable license/exception text, exact corresponding source, and replacement/relinking ability; audit bundled native libraries. |

### Release packaging requirements

- Include this `THIRD_PARTY_NOTICES.md` with each independently distributed JAR, binary archive, and container image (for example under `META-INF/` in JARs and beside the Go binary in release archives).
- Include the applicable full license/exception texts for the remaining GPL, LGPL, MPL, EPL, CDDL, CPL and Confluent-licensed components in the distributed artifact or an accompanying license bundle.
- For `com.mysql:mysql-connector-j:9.1.0` and `mysql:mysql-connector-java:8.0.27`, publish/provide complete corresponding source using a GPLv2-compliant method; include the Universal FOSS Exception text as well.
- Prefer shipping `org.mariadb.jdbc:mariadb-java-client:2.7.12` as a separate replaceable JAR rather than shading it into the application JAR. If shading is retained, preserve the LGPL relinking/replacement rights and provide the required source/materials.
- For the Go client, include the MPL-2.0 notice and exact `github.com/go-sql-driver/mysql@v1.8.1` source location with the binary distribution.
- `pypi:psycopg2-binary` is not version-pinned. If an installed Python environment/container is redistributed, record the exact wheel/version and audit its bundled native libraries before release.
- `junit:junit:4.10` appears in the runtime shaded dependency set even though it is a test framework. Prefer removing/excluding it from production packaging; otherwise maintain CPL source availability.
- `io.confluent:kafka-schema-registry:7.2.1` is under the Confluent Community License, not an OSI open-source license. Keep only if required and approved for the intended use, particularly hosted/managed-service scenarios.

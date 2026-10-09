# Third-Party License Attribution

This project is licensed under the Apache License 2.0; see [`LICENSE`](LICENSE).
It uses the third-party components listed below. Each keeps its own license.

## Scope

This notice covers what the project distributes:

* the Kafka connector JAR (`sink-connector`, assembled with its runtime
  dependencies);
* the lightweight connector JAR (`sink-connector-lightweight`, shaded);
* the Go client binary (`sink-connector-client`);
* the runtime bundles that the repository's Docker build definitions copy or
  download into the published images;
* the direct dependencies of the installable `ch-sink-tools` Python package.

Test-only and build-tool dependencies are excluded. So are base-image and
operating-system packages, which carry their own notices. Python dependencies
that are not pinned are shown as declared, so their transitive versions are
not enumerated.

Where a dependency is offered under several licenses, the table records the
option this distribution relies on. That choice does not change the upstream
licensing; it only states which option applies here.

The Maven table is generated from `mvn dependency:tree -Dscope=runtime`
(compile and runtime scope) for both modules and the license declared in each
artifact's POM. Regenerate it whenever a dependency changes; see
[`doc/licensing.md`](doc/licensing.md).

Full texts of the non-permissive licenses that apply are in [`licenses/`](licenses/).
The Apache License 2.0 is [`LICENSE`](LICENSE).

## What changed compared with the 2.10.3 notice

Removed, so these are no longer distributed:

| Component | License | Change |
| --- | --- | --- |
| `org.mariadb.jdbc:mariadb-java-client:2.7.12` | LGPL-2.1-or-later | test scope only; never shaded into a JAR |
| `junit:junit:4.10`, `org.hamcrest:hamcrest-core:1.1` | CPL-1.0 / BSD | excluded from `json-simple`'s mis-scoped runtime dependency |
| `org.glassfish:javax.json:1.1.4` | CDDL-1.1 or GPL-2.0 with Classpath Exception | unused; removed |
| `mysql:mysql-connector-java:8.0.27` | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | removed from `sink-connector/deploy/libs/` and the Kafka images; the Kafka sink never opens a MySQL connection |
| `io.confluent:kafka-schema-registry:7.2.1` | Confluent Community License 1.0 | the registry server is no longer downloaded into the Kafka images; the Avro converter needs only `kafka-avro-serializer` and `kafka-schema-registry-client` |
| `pypi:psycopg2-binary` | LGPL-3.0-or-later WITH OpenSSL-exception | replaced by `pg8000` (BSD-3-Clause) |
| `pypi:mysql-connector-python` (test harness) | GPL-2.0 | replaced by `PyMySQL` (MIT) |

Changed:

| Component | License | Change |
| --- | --- | --- |
| `com.mysql:mysql-connector-j:9.1.0` | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | no longer shaded into the lightweight JAR. The JAR loads it at run time from `mysql-connector-j.jar` next to it. Only the lightweight container image ships it, as a separate unmodified file with its corresponding source; see below. |

## License elections

| Component/family | Upstream alternatives | Selected for this distribution |
| --- | --- | --- |
| Jetty / Jetty WebSocket 9.4.x and 11.0.x, `jetty-jakarta-servlet-api` | EPL or Apache-2.0 | **Apache-2.0** |
| `org.javassist:javassist` | MPL-1.1 / LGPL-2.1 / Apache-2.0 | **Apache-2.0** |
| `org.locationtech.jts:jts-core` | EPL-2.0 or EDL-1.0 | **BSD-3-Clause (EDL-1.0)** |
| `org.hdrhistogram:HdrHistogram` | CC0-1.0 or BSD-2-Clause | **BSD-2-Clause** |
| `org.reflections:reflections` | WTFPL or Apache-2.0 | **Apache-2.0** |
| Jakarta Annotation / Transaction / WS-RS, HK2 and Jersey 2.x | EPL-2.0 or GPL-2.0 with Classpath Exception | **EPL-2.0** |
| `javax.activation-api` 1.2.0, `jaxb-api` 2.3.1 | CDDL-1.1 or GPL-2.0 with Classpath Exception | **CDDL-1.1** |
| `python-dateutil` | Apache-2.0 or BSD-3-Clause | **Apache-2.0** |

## Maven runtime dependencies (both connector JARs)

| Module | License | License text |
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
| com.google.guava:failureaccess:1.0.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:failureaccess:1.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:guava:31.0.1-jre | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:guava:33.0.0-jre | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.inject:guice:6.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.j2objc:j2objc-annotations:1.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.google.j2objc:j2objc-annotations:2.8 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.googlecode.json-simple:json-simple:1.1.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.yammer.metrics:metrics-core:2.2.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| com.zaxxer:HikariCP:6.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-codec:commons-codec:1.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-codec:commons-codec:1.19.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-io:commons-io:2.11.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| commons-io:commons-io:2.20.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-api:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-binlog:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-mysql:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-connector-postgres:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-core:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-ddl-parser:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-embedded:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-storage-file:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-storage-jdbc:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:debezium-storage-kafka:3.1.3.Final | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.debezium:mysql-binlog-connector-java:0.40.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.dropwizard.metrics:metrics-core:4.2.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.dropwizard.metrics:metrics-jmx:4.2.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.dropwizard.metrics:metrics-jvm:4.2.3 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.javalin:javalin:5.5.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.micrometer:micrometer-core:1.8.5 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.micrometer:micrometer-core:1.9.5 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| io.micrometer:micrometer-registry-prometheus:1.8.5 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
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
| jakarta.annotation:jakarta.annotation-api:1.3.5 | EPL-2.0 | licenses/EPL-2.0.md |
| jakarta.inject:jakarta.inject-api:2.0.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| jakarta.transaction:jakarta.transaction-api:1.3.3 | EPL-2.0 | licenses/EPL-2.0.md |
| jakarta.validation:jakarta.validation-api:2.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| jakarta.ws.rs:jakarta.ws.rs-api:2.1.6 | EPL-2.0 | licenses/EPL-2.0.md |
| jakarta.xml.bind:jakarta.xml.bind-api:2.3.3 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| javax.activation:activation:1.1.1 | CDDL-1.0 | licenses/CDDL-1.0.md |
| javax.activation:javax.activation-api:1.2.0 | CDDL-1.1 | licenses/CDDL-1.1.md |
| javax.inject:javax.inject:1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| javax.xml.bind:jaxb-api:2.3.1 | CDDL-1.1 | licenses/CDDL-1.1.md |
| org.antlr:antlr4-runtime:4.13.1 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.antlr:antlr4-runtime:4.13.2 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.apache.avro:avro:1.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.commons:commons-lang3:3.12.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.httpcomponents.client5:httpclient5:5.4.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.httpcomponents.core5:httpcore5:5.3.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.apache.httpcomponents.core5:httpcore5-h2:5.3.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
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
| org.checkerframework:checker-qual:3.41.0 | MIT | https://opensource.org/license/mit |
| org.codehaus.plexus:plexus-utils:3.3.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.codehaus.plexus:plexus-utils:3.5.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
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
| org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api:5.0.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-core-common:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-core-server:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-jetty-api:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-jetty-common:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-jetty-server:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.eclipse.jetty.websocket:websocket-servlet:11.0.15 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.glassfish.hk2:hk2-api:2.6.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.hk2:hk2-locator:2.6.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.hk2:hk2-utils:2.6.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.hk2:osgi-resource-locator:1.0.3 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.hk2.external:aopalliance-repackaged:2.6.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.hk2.external:jakarta.inject:2.6.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.jersey.containers:jersey-container-servlet:2.39.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.jersey.containers:jersey-container-servlet-core:2.39.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.jersey.core:jersey-client:2.39.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.jersey.core:jersey-common:2.39.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.jersey.core:jersey-server:2.39.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.glassfish.jersey.inject:jersey-hk2:2.39.1 | EPL-2.0 | licenses/EPL-2.0.md |
| org.hdrhistogram:HdrHistogram:2.1.12 | BSD-2-Clause | https://opensource.org/license/bsd-2-clause |
| org.javassist:javassist:3.29.0-GA | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains:annotations:17.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib:1.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib-common:1.9.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.7.20 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.7.20 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.jspecify:jspecify:1.0.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.latencyutils:LatencyUtils:2.0.3 | CC0-1.0 | https://creativecommons.org/publicdomain/zero/1.0/ |
| org.locationtech.jts:jts-core:1.18.2 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.lz4:lz4-java:1.8.0 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.ow2.asm:asm:9.4 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.ow2.asm:asm:9.7 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.ow2.asm:asm-commons:9.4 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.ow2.asm:asm-tree:9.4 | BSD-3-Clause | https://opensource.org/license/bsd-3-clause |
| org.postgresql:postgresql:42.5.0 | BSD-2-Clause | https://opensource.org/license/bsd-2-clause |
| org.reflections:reflections:0.10.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.roaringbitmap:RoaringBitmap:1.0.6 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.slf4j:slf4j-api:1.7.36 | MIT | https://opensource.org/license/mit |
| org.slf4j:slf4j-api:2.0.12 | MIT | https://opensource.org/license/mit |
| org.slf4j:slf4j-simple:2.0.12 | MIT | https://opensource.org/license/mit |
| org.xerial.snappy:snappy-java:1.1.8.4 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| org.yaml:snakeyaml:2.2 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |

Embedded in the artifacts above without their own Maven coordinates:

| Module | License | License text |
| --- | --- | --- |
| com.ongres.scram:client:2.1, com.ongres.scram:common:2.1, com.ongres.stringprep:saslprep:1.1, com.ongres.stringprep:stringprep:1.1 (shaded inside org.postgresql:postgresql) | BSD-2-Clause | `META-INF/licenses/com.ongres.*/*/LICENSE` in the JAR |
| org.postgresql:postgresql:42.5.4 (`postgresql-42.5.4.jar`, a resource of the lightweight JAR) | BSD-2-Clause | https://opensource.org/license/bsd-2-clause |

## Go client (`sink-connector-client`, statically linked)

| Module | License | License text |
| --- | --- | --- |
| filippo.io/edwards25519@v1.1.0 | BSD-3-Clause | https://github.com/FiloSottile/edwards25519/blob/v1.1.0/LICENSE |
| github.com/cpuguy83/go-md2man/v2@v2.0.2 | MIT | https://github.com/cpuguy83/go-md2man/blob/v2.0.2/LICENSE.md |
| github.com/go-sql-driver/mysql@v1.8.1 | MPL-2.0 | licenses/MPL-2.0.txt |
| github.com/google/go-querystring@v1.0.0 | BSD-3-Clause | https://github.com/google/go-querystring/blob/v1.0.0/LICENSE |
| github.com/levigross/grequests@v0.0.0-20221222020224-9eee758d18d5 | Apache-2.0 | https://github.com/levigross/grequests/blob/9eee758d18d5/LICENSE |
| github.com/russross/blackfriday/v2@v2.1.0 | BSD-2-Clause | https://github.com/russross/blackfriday/blob/v2.1.0/LICENSE.txt |
| github.com/tidwall/pretty@v1.2.1 | MIT | https://github.com/tidwall/pretty/blob/v1.2.1/LICENSE |
| github.com/urfave/cli@v1.22.13 | MIT | https://github.com/urfave/cli/blob/v1.22.13/LICENSE |
| golang.org/x/net@v0.0.0-20181011144130-49bb7cea24b1 | BSD-3-Clause | https://github.com/golang/net/blob/49bb7cea24b1/LICENSE |

## Bundles added by the Docker build definitions

Lightweight image (`sink-connector-lightweight/Dockerfile`, `Dockerfile`):

| Component | License | Notes |
| --- | --- | --- |
| com.mysql:mysql-connector-j:9.1.0 | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | `/mysql-connector-j.jar`, unmodified and separate from the Apache-2.0 JAR. Its license, including the exception text, is inside the jar (`LICENSE`) and in `licenses/`. The complete corresponding source is in the image at `/licenses/mysql-connector-j/mysql-connector-j-9.1.0-sources.jar`, and upstream at https://github.com/mysql/mysql-connector-j/tree/9.1.0 |

Kafka images (`sink-connector/docker/Dockerfile-sink-on-*`):

| Component | License | Notes |
| --- | --- | --- |
| io.apicurio:apicurio-registry-distro-connect-converter:2.1.5.Final | Apache-2.0 | https://github.com/Apicurio/apicurio-registry/blob/2.1.5.Final/LICENSE |
| io.confluent:common-config:7.2.1 | Apache-2.0 | https://github.com/confluentinc/common/blob/v7.2.1/LICENSE |
| io.confluent:common-utils:7.2.1 | Apache-2.0 | https://github.com/confluentinc/common/blob/v7.2.1/LICENSE |
| io.confluent:kafka-avro-serializer:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/avro-serializer/LICENSE |
| io.confluent:kafka-connect-avro-converter:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/avro-converter/LICENSE |
| io.confluent:kafka-schema-registry-client:7.2.1 | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/client/LICENSE |
| io.confluent:kafka-serde-tools-package:7.2.1 (strimzi variant only) | Apache-2.0 | https://github.com/confluentinc/schema-registry/blob/v7.2.1/package-kafka-serde-tools/LICENSE |
| `sink-connector/deploy/libs`: io.debezium debezium-api / debezium-core / debezium-ddl-parser / debezium-connector-mysql 1.8.1.Final, io.debezium:mysql-binlog-connector-java:0.25.4, com.google.guava:guava:30.0-jre, com.google.guava:failureaccess:1.0.1 | Apache-2.0 | https://www.apache.org/licenses/LICENSE-2.0.txt |
| `sink-connector/deploy/libs`: org.antlr:antlr4-runtime:4.8 | BSD-3-Clause | https://github.com/antlr/antlr4/blob/4.8/LICENSE.txt |

## Python package `ch-sink-tools` (direct dependencies, as declared)

| Package | License | License text |
| --- | --- | --- |
| pypi:clickhouse-driver>=0.2.9 | MIT | https://github.com/mymarilyn/clickhouse-driver/blob/master/LICENSE |
| pypi:pg8000>=1.31,<1.32 | BSD-3-Clause | https://github.com/tlocke/pg8000/blob/main/LICENSE |
| pypi:scramp (via pg8000) | MIT-0 | https://github.com/tlocke/scramp/blob/main/LICENSE |
| pypi:asn1crypto (via scramp) | MIT | https://github.com/wbond/asn1crypto/blob/master/LICENSE |
| pypi:python-dateutil (via pg8000) | Apache-2.0 | https://github.com/dateutil/dateutil/blob/master/LICENSE |
| pypi:PyYAML | MIT | https://github.com/yaml/pyyaml/blob/main/LICENSE |
| pypi:PyMySQL (extra `mysql`) | MIT | https://github.com/PyMySQL/PyMySQL/blob/main/LICENSE |
| pypi:SQLAlchemy>=1.4 (extra `mysql`) | MIT | https://github.com/sqlalchemy/sqlalchemy/blob/main/LICENSE |
| pypi:antlr4-python3-runtime==4.11.1 (extra `mysql`) | BSD-3-Clause | https://github.com/antlr/antlr4/blob/4.11.1/LICENSE.txt |
| pypi:pandas (extra `dataframe`) | BSD-3-Clause | https://github.com/pandas-dev/pandas/blob/main/LICENSE |

## Copyleft components that remain, and how each is handled

| Component | License | Handling |
| --- | --- | --- |
| com.mysql:mysql-connector-j:9.1.0 | GPL-2.0-only WITH Universal-FOSS-exception-1.0 | Not in either JAR. Shipped only in the lightweight image, as a separate unmodified file, with its corresponding source (`/licenses/mysql-connector-j/`) and license text (`/licenses/`). |
| github.com/go-sql-driver/mysql@v1.8.1 | MPL-2.0 | Unmodified. MPL text in `licenses/MPL-2.0.txt`; source at https://github.com/go-sql-driver/mysql/tree/v1.8.1. This notice and `licenses/` ship next to the binary in the lightweight image (`/licenses/`) and as a release asset. |
| Jakarta Annotation / Transaction / WS-RS, HK2, Jersey 2.x (EPL-2.0 rows above) | EPL-2.0 | Unmodified binaries; EPL text in `licenses/EPL-2.0.md`; source in the matching `-sources.jar` on Maven Central. |
| javax.activation:activation:1.1.1 | CDDL-1.0 | Unmodified; text in `licenses/CDDL-1.0.md`; source in the matching `-sources.jar` on Maven Central. |
| javax.activation:javax.activation-api:1.2.0, javax.xml.bind:jaxb-api:2.3.1 | CDDL-1.1 | Unmodified; text in `licenses/CDDL-1.1.md`; source in the matching `-sources.jar` on Maven Central. |

## Release packaging

* This file and `licenses/` are packaged under `META-INF/` in both connector
  JARs, so every image that carries a connector JAR carries them. The
  lightweight images also hold them under `/licenses/`, together with the
  MySQL driver's source and next to the Go client.
* The release workflow attaches this file to every GitHub release that ships
  the JAR and Go client binaries.
* The build fails if an LGPL/GPL JDBC driver or JUnit 4 reaches the
  compile/runtime scope of the lightweight JAR (maven-enforcer
  `bannedDependencies`).

## License texts

- [Apache License 2.0](LICENSE)
- [GPL-2.0 with the Universal FOSS Exception 1.0 (as shipped with MySQL Connector/J)](licenses/GPL-2.0-only-WITH-Universal-FOSS-exception-1.0.txt)
- [Mozilla Public License 2.0](licenses/MPL-2.0.txt)
- [Eclipse Public License 2.0](licenses/EPL-2.0.md)
- [CDDL 1.0](licenses/CDDL-1.0.md)
- [CDDL 1.1](licenses/CDDL-1.1.md)

# Licensing and third-party dependencies

This project is licensed under the [Apache License 2.0](../LICENSE). Its
dependencies follow the
[ASF 3rd Party License Policy](https://www.apache.org/legal/resolved.html):

* **Category A** (Apache-2.0, MIT, BSD, ISC, EDL-1.0, UPL, ...): may be bundled.
* **Category B** (weak copyleft: CDDL 1.0/1.1, CPL 1.0, EPL 1.0/2.0, MPL,
  GPL-2.0 WITH Classpath Exception): may be included in binary form only,
  labelled.
* **Category X** (GPL 1/2/3 "including the various exceptions available to GPL
  licenses" other than the Classpath and GCC Runtime exceptions, LGPL 2/2.1/3,
  AGPL, ...): must never be distributed with the project, in source or binary
  form. A user may supply such a component themselves, following the
  project's instructions.

## What the build enforces

`sink-connector-lightweight/pom.xml` runs `maven-enforcer-plugin`
`bannedDependencies` in the `validate` phase. The build FAILS when one of
these would reach the `compile` or `runtime` scope, i.e. would be shaded into
`clickhouse-debezium-embedded-<version>.jar`:

| Artifact | License | Why it is banned |
|---|---|---|
| `com.mysql:mysql-connector-j` | GPL-2.0 + Universal FOSS Exception | Category X |
| `mysql:mysql-connector-java` (pre-8.0.31 coordinates) | GPL-2.0 + FOSS Exception | Category X |
| `org.mariadb.jdbc:*` | LGPL-2.1 | Category X |
| `junit:junit` | CPL-1.0 | a test framework has no place in the production jar |

`test` and `provided` scopes are never shaded and are allowed.

This replaced `org.honton.chas:license-maven-plugin` 0.0.6, whose `compliance`
goal ran in every build and never failed one: the 2.11.0 jar it approved
contained 1,127 MySQL Connector/J entries (`com/mysql/`), 283 MariaDB driver
entries (`org/mariadb/`) and JUnit 4.

## The MySQL JDBC driver is supplied at run time

Debezium's MySQL connector needs MySQL Connector/J at run time: its classes
link directly against `com.mysql.cj.jdbc.Driver`,
`com.mysql.cj.CharsetMapping` and `com.mysql.cj.protocol.a.NativeConstants`.
No other JDBC driver can stand in for it (the MariaDB driver is LGPL anyway),
and the driver is GPL, so it cannot be bundled. Instead:

* The default build declares the driver `provided`. It is on the compile and
  test class path and is NOT inside the connector jar. This project's own
  source never links against it either. The one driver helper the connector
  uses, the zone resolver in `ConnectionTimeZonePreflight`, is called by
  reflection (`MySqlJdbcDriver.canonicalTimeZone`).
* `mvn package` copies the unmodified driver to
  `sink-connector-lightweight/target/mysql-driver/mysql-connector-j.jar` as
  a separate file.
* The connector jar's manifest has
  `Class-Path: mysql-connector-j.jar lib/mysql-connector-j.jar`.

To run a MySQL pipeline, put the driver on the class path in one of these
ways:

1. **Next to the jar (no change to the start command).** Copy or symlink the
   driver jar (from `target/mysql-driver/`, or
   [Maven Central](https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/))
   as `mysql-connector-j.jar`, or `lib/mysql-connector-j.jar`, in the
   directory that holds the connector jar. `java -jar
   clickhouse-debezium-embedded-<version>.jar config.yml` then loads it.
2. **Explicit class path:**
   `java -cp clickhouse-debezium-embedded-<version>.jar:mysql-connector-j-<v>.jar com.altinity.clickhouse.debezium.embedded.ClickHouseDebeziumEmbeddedApplication config.yml`
3. **Internal builds only:** `mvn package -Pbundle-mysql-driver` shades the
   driver into the jar, as releases before this change did, and skips only
   the Connector/J ban. A jar built this way contains GPL code. It may be
   used inside your organisation but must not be published or redistributed.

The lightweight Docker images (`Dockerfile`,
`sink-connector-lightweight/Dockerfile`) ship the driver as its own
unmodified file, `/mysql-connector-j.jar`, next to `/app.jar`. The container
entrypoints are unchanged. The image does what GPL-2.0 requires of anything
that redistributes the driver binary:

* it carries the license (inside the driver jar and in `/licenses/`);
* it carries the complete corresponding source,
  `/licenses/mysql-connector-j/mysql-connector-j-<v>-sources.jar`, which
  `mvn package` copies from Maven Central next to the binary.

A distribution that must contain no Category X component at all (an ASF
release, for example) builds its image without those two `COPY` lines and
mounts the driver at run time.

## Attribution: THIRD_PARTY_NOTICES.md

[`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) lists every
third-party component the project distributes, with the license each one
uses here. It covers both JARs, the Go client, the Docker bundles and the
Python package. [`licenses/`](../licenses/) holds the full texts of the
non-permissive licenses.

Both files are packaged:

* under `META-INF/` in both JARs;
* under `/licenses/` in the lightweight images;
* in the release JAR zip, and as a release asset.

The Maven table is generated from `mvn dependency:tree -Dscope=runtime` and
the POM license metadata. Regenerate it when a dependency changes.

The Kafka images no longer download Confluent's `kafka-schema-registry`
server jar (Confluent Community License, not OSI). The Avro converter needs
only `kafka-avro-serializer` and `kafka-schema-registry-client`. The Kafka
images also no longer copy `mysql-connector-java-8.0.27.jar` (GPL), which was
committed under `sink-connector/deploy/libs/` but never used: the Kafka sink
does not connect to MySQL.

A MySQL pipeline started without the driver refuses to start once, naming
these three options (`MySqlJdbcDriver.check`). It does not fail later inside
Debezium's retry loop. PostgreSQL pipelines do not need the MySQL driver and
are never refused.

The driver version is the property `version.mysql.connector.j`. Keep it at
the version that `debezium-connector-mysql` `${version.debezium}` declares.

## Category B dependencies that remain (allowed in binary form)

These remain in the shaded jar. They are Category B, or dual-licensed with a
Category A or B option:

* `jakarta.annotation-api`, `jakarta.transaction-api`, `jakarta.ws.rs-api`,
  HK2 (`org.glassfish.hk2:*`): EPL-2.0 or GPL-2.0 WITH Classpath Exception
  (both Category B).
* `javax.activation:activation`, `javax.activation-api`, `jaxb-api`:
  CDDL 1.0/1.1 (Category B; the GPL alternative is not the one relied on).
* `org.locationtech.jts:jts-core`: EPL-2.0 or EDL-1.0 (EDL is BSD-3, Category A).
* Jetty (`org.eclipse.jetty:*`): EPL or Apache-2.0 (Apache-2.0 relied on).
* Jersey (`org.glassfish.jersey.*`, pulled by Kafka Connect runtime):
  EPL-2.0 or GPL-2.0 WITH Classpath Exception (Category B).
* `org.javassist:javassist`: MPL-1.1, LGPL-2.1 or Apache-2.0 (Apache-2.0
  relied on).

`org.glassfish:javax.json` (CDDL-1.1 or GPL-2.0) was declared with a comment
about an antlr4-maven-plugin issue, but nothing used it. It has been removed,
and the build and tests pass without it.

## Python tooling (`sink-connector/python`)

| Was | License | Used by | Now |
|---|---|---|---|
| `psycopg2-binary` | LGPL-3.0 (Category X) | PostgreSQL checksum / dump tools in `ch_sink_tools` (required dependency) | `pg8000>=1.31,<1.32` (BSD-3-Clause; deps `scramp` MIT-0, `asn1crypto` MIT, `python-dateutil` Apache-2.0/BSD) |
| `mysql-connector-python` | GPL-2.0 (Category X) | `sink-connector/tests` (legacy test harness) | `pymysql` (MIT) |

Behaviour kept across the driver change (`ch_sink_tools/db/postgres.py`,
`top_level_postgres_checksum.py`):

* Rows are still returned as dicts keyed by column name, built from
  `cursor.description` (pg8000 has no `RealDictCursor`).
* The REPEATABLE READ, READ ONLY snapshot used by the checksum is set with
  `SET SESSION CHARACTERISTICS ...` before the implicit BEGIN. psycopg2 did
  the same with `set_session()`. Both the isolation level and READ ONLY are
  verified inside the transaction.
* The 20 s timeout applies to the connect only. pg8000 would otherwise keep
  it on the socket for every read, so it is cleared after connecting.
* `statement_timeout=0` is still sent as the `options` startup parameter.
* Checksummed values are cast to text by the server. The bulk dump path
  uses the `psql` CLI. Neither depends on driver type conversion.

## Adding a dependency

Check its license (and the licenses of its transitive dependencies) before
adding it. A Category X package must never be a compile/runtime dependency of
the jar or a required dependency of the Python package. Either find a
permissively licensed alternative or make it user-supplied, with
instructions, as this document does for the MySQL driver. When adding a new
Category X artifact family to the ecosystem, add it to the enforcer
`bannedDependencies` list too.

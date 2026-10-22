# Spec 10.07: Configuration Key Check

## 1. Executive Summary & Purpose

A key that the connector and Debezium do not read has no effect. A misspelled key such as
`offset.table.name` (for `offset.storage.jdbc.table.name`) is ignored without a log line, and the connector starts
with the default in place. A key that Debezium 3.x renamed (`database.whitelist`, `database.history.*`) or that the
connector stopped reading (`clickhouse.server.pass`) behaves the same way. The operator sees a connector that runs
with a setting that is not in force.

This spec adds a start-time check of the user configuration file, before it is merged over the defaults. Every
finding is reported, and the default never refuses the start:

- default (`config.keys.strict` absent or `false`): every finding is logged as a WARN line and the start continues;
- opt-in strict (`config.keys.strict: true`): the start is refused if some finding is UNKNOWN or RENAMED (a renamed
  key whose replacement is absent); the findings are logged with `action=refused`;
- a renamed key whose replacement is also set (RENAMED_IGNORED) and a dead key that is only ignored (IGNORED) never
  refuse, in either mode;
- a value of `config.keys.strict` other than `true` or `false` (compared case-insensitively after trimming) is invalid
  and refuses the start in every mode.

The `config.keys.strict` key is a known key: it is read by the start-time check and is not a finding.

Assumption: an Ansible role maintained outside this repository, which renders the connector configuration, still emits dead keys that are not read by Debezium
3.1.3 or by the connector (section 3.4 and section 7). These keys are not registered as known and are not added to the
ignored list, so they stay UNKNOWN, warn by default, and refuse under strict. They are reported as drift in the role
templates. The connector does not edit those templates.

## 2. Codebase Mapping on 2.11.1

- `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/config/ConfigKeyCheck.java`:
  `check(Properties)` (log, count, refuse), `findings(Set<String>, boolean strict)`, the `Finding` record, `IGNORED_KEYS`,
  `APP_READ_KEYS`, `knownKeys()`, `driverKeys()`, `debeziumFieldNames()`, `debeziumDeprecatedAliases()`.
- `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ClickHouseDebeziumEmbeddedApplication.java`:
  `loadPropertiesFile` calls `ConfigKeyCheck.check` on the user file before `props.putAll`; a refusal exits with status -1.
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/ClickHouseSinkConnectorConfig.java`:
  `definedKeys()`, a public accessor over the sink `ConfigDef` (`newConfigDef().names()`).
- `sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/config/ColumnTypeOverrideConfig.java`: reads the
  `column_type_override.direct.` and `column_type_override.alias.` prefixes (pass-through prefixes, section 3.1).
- `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogConnectionGuard.java`:
  `PROPERTY` (`binlog.read.timeout.ms`), `KEEP_ALIVE_INTERVAL` (`connect.keep.alive.interval.ms`, default 60000).
- `sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/converters/Latin1TextConverter.java`:
  `NAME` and `ensureRegistered(Properties)`, which sets `converters` and `latin1text.type`.
- `sink-connector-lightweight/src/test/java/com/altinity/clickhouse/debezium/embedded/config/ConfigKeyCheckTest.java`:
  the behaviour tests, the source scan and the shipped-default lint.
- Shipped configuration linted by the test and by the review: `sink-connector-lightweight/docker/config.yml`,
  `sink-connector-lightweight/docker/config_postgres.yml`, `sink-connector-lightweight/k8s/config.yml`,
  `sink-connector-lightweight/k8s/clickhouse-sink-connector-lt-cm1-configmap.yaml`,
  `sink-connector-lightweight/helm/sink-connector-lightweight/templates/configmap.yaml`,
  `deploy/ansible-systemd/templates/config.yml.j2`, `sink-connector/python/tests_e2e/mysql/connector-config.yml`.
- Operator reference: `doc/configuration.md`.

## 3. Operational Specification

### 3.1 The known set

A key is known when it is one of:

1. a key the sink `ConfigDef` defines (`definedKeys()`), or a `ClickHouseSinkConnectorConfigVariables` value;
2. a field name of a Debezium configuration class loaded from `FIELD_SOURCES`: `MySqlConnectorConfig`,
   `PostgresConnectorConfig`, `MongoDbConnectorConfig`, `EmbeddedEngineConfig`, `SchemaHistory`,
   `JdbcOffsetBackingStoreConfig` and `JdbcSchemaHistoryConfig`, read with `Field.name()` and `Field.deprecatedAliases()`
   (not written from memory);
3. a JDBC store key with the Debezium prefix: `offset.storage.` for `JdbcOffsetBackingStoreConfig` and
   `schema.history.internal.` for `JdbcSchemaHistoryConfig`. The prefixed set is derived from the field names and aliases,
   so `schema.history.internal.jdbc.schema.history.table.name` and its alias `schema.history.internal.jdbc.table.name`
   are both known;
4. a JDBC driver property passed through `database.`: the union of `com.mysql.cj.conf.PropertyKey` (`getKeyName`,
   `getCcAlias`) and `org.postgresql.PGProperty` (`getName`), read reflectively by `driverKeys()`. A driver property
   named in the `driver.` prefix is accepted as a pass-through;
5. a key the lightweight module reads through a property lookup, listed in `APP_READ_KEYS`, plus the
   `BinlogConnectionGuard.PROPERTY` and the converter type key `latin1text.type` (`Latin1TextConverter.NAME` plus
   `.type`, which `Latin1TextConverter.ensureRegistered` sets and Debezium reads);
6. the pass-through prefixes `column_type_override.direct.` and `column_type_override.alias.`: every key that starts
   with either prefix is known, whatever its suffix. The sink connector reads these keys through
   `ColumnTypeOverrideConfig`, and the suffix is a table and column name that the check cannot enumerate;
7. the key `config.keys.strict`, which the start-time check reads itself (section 3.2).

Key matching is exact and case-sensitive. A key that differs only in case from a known key is UNKNOWN, and the known key
is named as the suggestion. The value of `config.keys.strict` is matched case-insensitively (section 3.2).

### 3.2 Classification

Each user key is classified once:

- `UNKNOWN`: not in the known set. The message names the nearest known key. A known key that contains the
  segments of the unknown key in order wins; otherwise the known key with the smallest edit distance is named.
- `RENAMED`: a key in the rename table with no replacement set. The message names the replacement.
- `RENAMED_IGNORED`: a key in the rename table whose replacement is also set. The legacy value is ignored.
- `IGNORED`: a key in `IGNORED_KEYS` (dead, but shipped or set by deployment templates). The message names the key to use instead, or says
  why the key has no effect.
- known: no finding.

The kind is spelled `RENAMED_IGNORED`, with an underscore, in the code, the log and this spec.

The rename table holds the legacy keys `database.whitelist`, `database.blacklist`, `table.whitelist`,
`table.blacklist`, `column.blacklist`, `clickhouse.server.pass` and `database.server.name`, and the `database.history.`
prefix. Each legacy key is ignored by Debezium 3.1.3: a scan of the 3.1.3 jars for the literal keys finds no class that
reads `database.whitelist`, `database.blacklist`, `table.whitelist`, `table.blacklist`, `column.blacklist` or
`database.server.name`, and `clickhouse.server.pass` is read by neither Debezium nor the connector. A legacy key that
reaches Debezium therefore has no effect, and the rename finding says so.

The `config.keys.strict` key selects the mode. Its value is parsed once, before any other finding is decided:

- absent, or `false` (compared case-insensitively after trimming): default mode;
- `true` (compared case-insensitively after trimming): strict mode;
- any other value: invalid.

Decision rule, the same in every mode:

- an invalid `config.keys.strict` value refuses the start;
- in strict mode, the start is refused if and only if at least one finding is `UNKNOWN` or `RENAMED`;
- in default mode, no finding refuses the start;
- `RENAMED_IGNORED` and `IGNORED` never refuse the start, in either mode.

A strict refusal message lists every refusing finding, joined by `; `, and begins `config.keys.strict=true refuses the
start:`. An invalid value throws with the message `config.keys.strict has the invalid value '<value>'; use true or
false`, which names the value.

### 3.3 Logging

Every finding is logged in every mode, and no mode suppresses a warning. Each finding produces one WARN line of the form
`CONFIG-KEY-CHECK kind=<KIND> key=<key> ... action=<action>`, where `<KIND>` is one of `UNKNOWN`, `RENAMED`,
`RENAMED_IGNORED` or `IGNORED`. The action is `ignored` when the finding does not refuse the start, and `refused` when
it does. The run then logs one summary line,
`CONFIG-KEY-CHECK keys=<n> findings=<n> unknown_total=<n> renamed_total=<n> ignored_total=<n>`. The number of finding
lines equals `findings`. The counters are process-wide.

### 3.4 Keep-alive keys (verified against spec 01.07 and the guard)

- `connect.keep.alive` defaults to false. `BinlogKeepAlivePreflight` sets it when absent (spec 01.07 section 3.4).
- `connect.keep.alive.interval.ms` is read by `BinlogConnectionGuard` (default 60000). `binlog.read.timeout.ms` defaults
  to twice that interval. Debezium reads the same key only when keep-alive is on.
- The connector does not read `database.keep.alive.interval.ms`. The key is not in the ignored list and is not registered as
  known, so it is UNKNOWN, not ignored. The pinned expectation is UNKNOWN in
  `src/test/resources/config-key-check/production-keys.tsv`. A file that sets it warns by default and refuses under strict
  mode.
- The other dead keys that the external role still emits follow the same rule:
  `database.connection.reconnect.backoff.ms`, `database.connection.reconnect.backoff.max.ms`,
  `schema.history.internal.jdbc.create.table.ddl`, `ignore.delete`, `clickhouse.server.database`,
  `clickhouse.table.engine`, `clickhouse.table.sign.column` and `clickhouse.table.version.column`. None is registered as
  known and none is added to the ignored list. The connector does not edit the role templates; the drift is reported
  in section 7.

### 3.5 Lint scope

The shipped-default test renders the defaults and the templates listed in section 2 and checks that none of them
refuses the start in default mode, so the findings match what an operator receives. The Oracle sample
`docker/config_oracle.yml` warns by default. It carries six unknown keys, one of them a keep-alive key whose name has a stray
backtick inside it (line 186 of that file), and it sets `database.server.name` without `topic.prefix`. It refuses only under
`config.keys.strict: true`. The Oracle connector is not built (`README.md` line 40 and `doc/quickstart_oracle.md` mark
it experimental); the owner decides whether the file is corrected or removed.

## 4. Invariants Preserved

- **I9 (Loud Failure / Zero Silence)**: a key that does not take effect is never silent. Every finding is logged as a
  WARN line in every mode. In strict mode an UNKNOWN or RENAMED finding also refuses the start. A RENAMED_IGNORED or
  IGNORED finding is logged with `action=ignored` and never refuses.
- **I15 (Bounded, Declared Recovery)**: a refusal happens at start, before any batch is written, so recovery is one
  configuration edit and one process start. The default mode never refuses, so no recovery is needed for its findings.
  An invalid `config.keys.strict` value refuses in every mode, and its recovery is the same edit.

The decision is one function of the findings and the parsed strict value (section 3.2). It has five properties: the default never refuses for a valid parse; strict refuses iff some finding is UNKNOWN or
RENAMED; RENAMED_IGNORED and IGNORED never refuse; an invalid value always refuses; and the warning count equals the
finding count.

## 5. Verification

- Behaviour tests in `ConfigKeyCheckTest`: `ConfigKeyCheckTest.unknownKeyWarnsByDefault`,
  `ConfigKeyCheckTest.misspelledKeyIsUnknownWithSuggestion`, `ConfigKeyCheckTest.strictKeyIsKnown`,
  `ConfigKeyCheckTest.strictParsesTrueAndFalse`, `ConfigKeyCheckTest.strictInvalidValueRefuses`,
  `ConfigKeyCheckTest.deadShippedKeysAreIgnoredNotRefused`, `ConfigKeyCheckTest.renamedKeyWarnsByDefaultRefusesUnderStrict`,
  `ConfigKeyCheckTest.historyKeysAreRenamed`, `ConfigKeyCheckTest.renameTargetsAreDebeziumFieldsOrSinkKeys`,
  `ConfigKeyCheckTest.driverKeysAreDerivedFromTheDriverEnums`, `ConfigKeyCheckTest.shippedDefaultsWarnButNeverRefuseByDefault`,
  `ConfigKeyCheckTest.columnTypeOverrideAliasAndDirectKeysAreKnown`, `ConfigKeyCheckTest.renamedKeysAreNotDebeziumFieldNames`,
  `ConfigKeyCheckTest.strictRefusesEveryUnknownKeyNotOnlyRenamed`, `ConfigKeyCheckTest.latin1TextRegistrationKeysAreKnown`
  (every key the registration sets is known; a misspelt `latin1text.typ` or `latin1text.types` is not).
- Source scan: `ConfigKeyCheckTest.everyKeyReadByTheModuleIsKnown` (every literal, string constant, `Q.NAME.name()` and
  `ClickHouseSinkConnectorConfigVariables` read resolves to a known key), `ConfigKeyCheckTest.reviewedDynamicReadsAreStillPresent`
  (the reviewed `DYNAMIC_READS` table: `offset.storage.jdbc.url`, `schema.history.internal.jdbc.url`,
  `offset.storage.jdbc.table.ddl`, `offset.storage.jdbc.offset.table.ddl`), `ConfigKeyCheckTest.scannerFailsOnUnregisteredRead`,
  `ConfigKeyCheckTest.scannerResolvesConstantsAndReportsDynamicReads`.
- Pin test: `ConfigKeyPinTest` is data-driven. `config-key-check/production-keys.tsv` pins every key of the external role
  template and of the in-repo deploy template, each with its expected kind. `config-key-check/shipped-keys.tsv`
  pins the findings of each shipped sample. `ConfigKeyPinTest.productionKeySetsAreClassifiedAsPinned` checks the default
  findings and, under strict, exactly the UNKNOWN and RENAMED rows; `ConfigKeyPinTest.inRepoDeployTemplateKeysArePinned`
  reads `deploy/ansible-systemd/templates/config.yml.j2` and checks its keys against the pinned rows;
  `ConfigKeyPinTest.shippedSampleFindingsArePinned` checks each shipped file. A new or reclassified key therefore fails until
  its row is reviewed in the same commit.

## 6. Failure Modes & Recovery

### 6.1 Unknown key (misspelling, removed key, or a key from a newer Debezium)

- Detection, default mode: a `CONFIG-KEY-CHECK kind=UNKNOWN key=<key> ... action=ignored` WARN line on each start, naming
  the nearest known key. The start continues, and the key has no effect.
- Detection, strict mode (`config.keys.strict: true`): the process exits at start with `config.keys.strict=true refuses the start: ...` and a
  `CONFIG-KEY-CHECK kind=UNKNOWN ... action=refused` line that names the key and the nearest known key.
- Recovery: correct the key to the one named, or remove it, then restart. If the key is a real Debezium or sink key that
  the known set misses, add it to the set in section 3.1 (a field source or `APP_READ_KEYS`) and release. If the key is
  a dead key that the role template still emits (section 3.4), remove it from the template, or set `config.keys.strict`
  to `false` until the template is fixed.
- RTO: one process start after the file is corrected (seconds). Under strict mode no batch is written before the refusal.

### 6.2 Renamed key without its replacement

- Detection, default mode: a `CONFIG-KEY-CHECK kind=RENAMED key=<key> ... action=ignored` WARN line on each start, naming
  the replacement. The start continues, and the legacy key has no effect.
- Detection, strict mode: the process exits at start with `config.keys.strict=true refuses the start: ...` and a `kind=RENAMED ... action=refused`
  line that names the replacement.
- Recovery: set the replacement key (for example `topic.prefix` for `database.server.name`) and restart.
- RTO: one process start after the file is corrected (seconds).

### 6.3 Legacy key in a shipped template, replacement also set

- Detection: a `CONFIG-KEY-CHECK kind=RENAMED_IGNORED key=<key> ... action=ignored` WARN line on each start, in either
  mode. The start continues, and the legacy value is ignored.
- Recovery: delete the legacy key from the template. The start is not affected.
- RTO: not applicable; the connector is running with the replacement key in force.

### 6.4 Dead shipped key

- Detection: a WARN `CONFIG-KEY-CHECK kind=IGNORED key=<key> ... action=ignored` line on each start, in either mode,
  naming the key to use instead or saying why the key has no effect.
- Recovery: delete the key from the file, or keep it. The start is not affected.
- RTO: not applicable; the connector is running.

### 6.5 Driver property typo

- Detection: a misspelled `database.` driver property is UNKNOWN (section 6.1): it warns by default and refuses under
  strict mode. A property the driver does not know but that is in the `driver.` pass-through prefix is accepted and
  passed through unchecked. A key with the `column_type_override.direct.` or `column_type_override.alias.` prefix is
  known, whatever its suffix (section 3.1).
- Recovery: correct the name against the driver's documented properties.
- RTO: one process start after the file is corrected (seconds).

### 6.6 Invalid `config.keys.strict` value

- Detection: the process exits at start in every mode with `config.keys.strict has the invalid value '<value>'; use true
  or false`, which names the invalid value. A value other than `true` or `false` (case-insensitive after trimming) is invalid.
- Recovery: set `config.keys.strict` to `true`, to `false`, or remove it, then restart.
- RTO: one process start after the file is corrected (seconds). No batch is written before the refusal.

## 7. Open Items

- The `driver.` pass-through is accepted wholesale, so a typo under that prefix is not detected.
- The Connector/J and pgjdbc name union means a PostgreSQL-only driver property in a MySQL file is accepted and has no effect.
- The Oracle sample `sink-connector-lightweight/docker/config_oracle.yml` warns by default and refuses only under
  `config.keys.strict: true` (section 3.5). Its line 186 has a stray backtick inside a key name, and the file is not
  corrected by this change.
- The test fixture `sink-connector-lightweight/src/test/resources/config.properties` contains keys that are not known and is
  not part of the lint.
- Under `config.keys.strict: true` these in-repo shipped samples refuse, because each sets `database.server.name` without
  `topic.prefix` (a RENAMED finding with no replacement): `sink-connector-lightweight/docker/config_keepermap_storage.yml`,
  `sink-connector-lightweight/docker/config_local.yml`, `sink-connector-lightweight/docker/config_postgres.yml`,
  `sink-connector-lightweight/docker/config_postgres_local.yml`, `sink-connector-lightweight/docker/config_oracle.yml` (which
  also has six UNKNOWN keys), `sink-connector-lightweight/src/test/resources/config.yml` and
  `sink-connector-lightweight/src/test/resources/config_postgres.yml`, and the postgres branch of
  `sink-connector-lightweight/helm/sink-connector-lightweight/templates/configmap.yaml`. The in-repo deploy template
  `deploy/ansible-systemd/templates/config.yml.j2` refuses under strict for the same reason (`database.server.name` only).
- The samples `sink-connector-lightweight/docker/config.yml`, `sink-connector-lightweight/docker/config-2.yml`,
  `sink-connector-lightweight/k8s/config.yml` and `sink-connector-lightweight/src/main/resources/config.properties` set both
  `database.server.name` and `topic.prefix`. The legacy key is RENAMED_IGNORED, so these never refuse (the pinned
  expectations in `src/test/resources/config-key-check/shipped-keys.tsv` list no other finding for them). The same legacy
  key is RENAMED_IGNORED in `sink-connector-lightweight/k8s/clickhouse-sink-connector-lt-cm1-configmap.yaml`,
  `sink-connector/python/tests_e2e/mysql/connector-config.yml` and the mysql branch of the helm template; their other keys
  are not in the pinned expectations, so the refusal result for them is not claimed here.
- The fix for strict mode on the samples and the deploy template is to set `topic.prefix`. That is a separate follow-up,
  and this change does not make it.
- Drift in the external Ansible role: its templates still emit the dead keys listed in
  section 3.4 (`database.keep.alive.interval.ms`, `database.connection.reconnect.backoff.ms`,
  `database.connection.reconnect.backoff.max.ms`, `schema.history.internal.jdbc.create.table.ddl`, `ignore.delete`,
  `clickhouse.server.database`, `clickhouse.table.engine`, `clickhouse.table.sign.column` and
  `clickhouse.table.version.column`). The in-repo deploy template already uses `ignore_delete` and
  `connect.keep.alive.interval.ms`, but the role templates still use the old keys, so they need a follow-up in
  the role. Until that follow-up lands, those keys warn by default and refuse under strict.

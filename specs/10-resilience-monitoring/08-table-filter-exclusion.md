# Spec 10.08: Exclusion with a universal table include

## 1. Executive Summary

An operator cannot add a table exclusion to a deployment that already sets
`table.include.list: .*`: Debezium rejects both properties together before
streaming starts. Normalize the redundant universal include, not the operator's
exclusion, before the lightweight application starts its capture engine.

## 2. Invariants

- If the trimmed include is exactly `.*` and the exclude is non-null and
  nonblank, remove the include property and retain the exclusion unchanged.
- Log a warning that the redundant include was removed and excluded tables
  will not be captured. The warning must not print unrelated configuration.
- With a missing/blank exclude, or any other include, preserve both properties.
  In particular, never broaden a restrictive include by removing it. Debezium
  continues to reject conflicting restrictive include/exclude settings and
  malformed exclusion regular expressions.
- Preserve every unrelated property, including database filters and offsets.
- Apply normalization after file reload and before capture setup, for YAML,
  environment, initial start and application restart paths. It is idempotent.
- This changes configured capture scope only. Excluded tables accumulate a
  capture gap; this does not repair their historical schema or backfill data.

## 3. Codebase Mapping

`TableFilterConfig.removeRedundantInclude` implements the pure decision using
property names supplied from Debezium's `RelationalDatabaseConnectorConfig`
fields. `ClickHouseDebeziumEmbeddedApplication.start` calls it before creating
or setting up `DebeziumChangeEventCapture`, after any configuration reload.

## 4. Verification

The regression tests reproduce Debezium's actual mutual-exclusion validation,
then verify the normalized configuration validates and its native table
predicate excludes only the requested table. Negative tests preserve
restrictive includes, reject invalid exclusion regexes and preserve unrelated
properties. Four formal specifications cover normalization, preservation,
idempotence and the no-broadening rule; Hypothesis executes the actual Java
normalizer over generated inputs.

## 5. Failure Modes & Recovery

- Detection: the startup warning identifies normalization; Debezium still reports
  restrictive-list conflicts and malformed exclusions as configuration errors.
- Recovery: remove an unintended exclusion in the operator's configuration and
  restart through the normal deployment process. Reconcile and backfill the
  excluded interval separately; this normalization does not restore missed rows.
- RTO: normalization runs once per start, before engine setup. End-to-end recovery
  time depends on the deployment and any excluded-table backfill; no bound is
  claimed here.

## 6. Cost

One include trim and one exclude blank check per application start: O(I + E)
characters, no processes, network calls or per-row work. The existing engine
and writer limits remain unchanged. Removing the operator's exclusion returns
to the original universal include on the next configuration reload. Deploying
this change and changing production capture scope remain operator actions.

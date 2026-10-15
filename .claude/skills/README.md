# Review Skills Index

Skills for reviewing and self-reviewing changes to clickhouse-sink-connector.
Each `SKILL.md` is a standalone checklist; load the ones relevant to what
you're touching.

## Loading Order

For any PR review or self-review, load in this order:

1. **`pr-self-review`** — always load this first. It defines the five
   review gates (Contract, Impacted surface, Failure and divergence,
   Evidence, Lower-priority quality), the severity model, and routes you to
   everything else below.
2. **`code-quality-<lang>`**, one per language actually changed in the
   diff (`code-quality-jvm`, `code-quality-python`, `code-quality-go`,
   `code-quality-bash`, `code-quality-sql`).
3. **`code-quality-architecture`** — last, for the cross-cutting design
   pass once the per-language checklists are done.

Load the remaining skills when the diff triggers them (see each skill's
description and `pr-self-review`'s "Triggered Expansions" section).

## Skills

| Skill | One-line description |
|---|---|
| `pr-self-review` | Entry-point review skill: five gates, severity model, spec-citation convention, routes to every other skill below. |
| `code-quality-architecture` | Cross-cutting design pass: module boundaries, dependency direction, API surface, CI gates. |
| `code-quality-jvm` | Idiomatic Java review for `sink-connector` and `sink-connector-lightweight`, including a deep concurrency/resource checklist. |
| `code-quality-python` | Idiomatic Python review for `sink-connector/python` tooling (db_compare, ch_sink_tools, resync scripts). |
| `code-quality-go` | Idiomatic Go review for `sink-connector-client`. |
| `code-quality-bash` | Robust shell-scripting review for build/CI helper scripts. |
| `code-quality-sql` | General SQL craft review (queries, DDL, migrations) across MySQL/Postgres and ClickHouse. |
| `code-reuse-review` | Reviewer-side check for whether new code duplicates something that already exists in the repo. |
| `code-reuse-first` | Author-side ladder to climb before writing new code (search repo → stdlib → platform → dependency → write minimum). |
| `design-note-debt` | The `DESIGN-NOTE: <what>; <ceiling>; <trigger>` convention for tracking deliberate shortcuts. |
| `mandatory-code-change-validation` | What counts as real validation beyond "tests pass" — execution-path tracing, functional validation, blast-radius checks. |
| `destructive-operation-safety` | The `DESTRUCTIVE:` comment and `Destructive-Op-Check:` trailer convention for any destructive statement. |
| `repro-harness-negative-proof` | Rules for proving a negative test result is real, not a silently-failed harness. |
| `sink-connector-mysql-source-of-truth` | The prime directive, column-kind rules, and ordering/offset invariants specific to this connector's replication correctness. |

## Enforcement

`scripts/review_gates.py` enforces four gates in CI: **destructive**,
**merge-stop**, **license**, and **hygiene**. See `doc/review_gates.md` for
what each gate checks. These skills cover the judgment calls the automated
gates can't make — they are a complement to the gates, not a restatement
of them.

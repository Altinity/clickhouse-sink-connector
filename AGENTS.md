# AGENTS.md — read this before changing anything in this repository

This file is the standing context for every agent (and every human) working
on this codebase. Read it before proposing, writing, reviewing, or merging
any change. It is not background reading — it is the rule that decides
arguments.

## ⛔ THE PRIME DIRECTIVE: MySQL is the source of truth

**This project is a replication engine from a transactional source
database (MySQL, PostgreSQL) into ClickHouse.**

That single sentence settles most design questions in this repo, so state it
plainly:

1. **The source data is the truth, and the source is always correct.**
   The source database defines what the data *is*. There is no scenario in
   which this connector decides the source is wrong. (Throughout this file
   "MySQL" stands for whichever source you are replicating; the rule is
   identical for PostgreSQL.)
2. **The ClickHouse side must CONFORM to MySQL.** ClickHouse is the replica.
   Its schema, its column set, its values, and its types exist to reproduce
   what MySQL holds.
3. **Every mismatch is fixed by making ClickHouse match MySQL — always.**
   A divergence is never resolved by adjusting, reinterpreting, or excusing
   the MySQL side, and never by declaring the ClickHouse state acceptable.
   The direction of the fix is fixed: ClickHouse moves to MySQL.
4. **The connector is the ENFORCER of that rule, not a reporter of it.**
   It does not merely detect divergence and describe it — it makes
   ClickHouse correct according to MySQL. When the ClickHouse side is in a
   shape that prevents the source value from being stored, the connector
   changes the ClickHouse side so it can be stored. **Reporting is the
   fallback for when enforcement genuinely fails, never the default.**

### Enforcement, concretely

Enforcement is an action, so it has a shape. Whenever you find the replica
unable to hold what the source sent:

- **Correct the ClickHouse side automatically** where a safe, bounded
  operation exists. A MATERIALIZED column shadowing a source value is
  fixed with `ALTER TABLE ... MODIFY COLUMN <col> <type>`, restating the
  type with no default expression — metadata-only, no part rewrite, on the
  same DDL path the connector already uses for schema evolution.
- **Enforcement has two halves, and the second is easy to forget.**
  Correcting the definition fixes the write path *forward*. Rows already
  written while the definition was wrong still hold the wrong values, and
  reconciling those is a **backfill** of the affected range. A change that
  fixes only the forward path is half-done — say so explicitly in the log
  and in the PR, so the remaining work is visible rather than assumed done.
- **Log the enforcement, loudly.** An automatic schema correction is a real
  event: record what was wrong, what was changed, and what still needs
  backfilling.
- **Only when enforcement cannot be performed** — the DDL is rejected, the
  privilege is missing, the type cannot be read — do you fall back to a
  warning naming the manual remediation.

Do not write code whose response to a divergence is only a log line. If you
find yourself describing a fix for a human to apply, ask first whether the
connector can apply it.

### What this means when you are deciding something

Any time you are weighing a design choice, a bug fix, a schema rule, a
conflict-resolution policy, or a "which side is right?" question, resolve it
with the prime directive:

- If a value differs between MySQL and ClickHouse → **the MySQL value is
  correct.** Write the MySQL value. Do not preserve, prefer, or defend the
  ClickHouse value.
- If a column exists in MySQL but not usefully in ClickHouse → **the
  ClickHouse side is wrong or incomplete.** Fix the ClickHouse side.
- If a type or precision cannot round-trip → that is a defect in the
  ClickHouse mapping, not an acceptable loss. Report it loudly; never
  silently narrow, truncate, or default the value.
- If you are tempted to write "the connector correctly excludes X" — stop.
  Verify against MySQL first. "Correct" is defined by the source, not by
  what the current code happens to do.

### Column-kind rules (ClickHouse side)

These follow directly from the prime directive and are the specific rules
that have caused real production incidents:

| ClickHouse column kind | Rule |
|---|---|
| **ALIAS** | **Ignore it.** An ALIAS column is not stored and cannot be written. It is a query-time expression, never part of the replicated row. |
| **MATERIALIZED** | **If MySQL defines a column of the same name, the MySQL value WINS and must be written.** A MATERIALIZED definition on the ClickHouse side must never silently shadow, override, or discard a value the source supplies. ClickHouse computing something locally does not make it the truth — MySQL is the truth. If a MATERIALIZED column blocks writing the source value, the MATERIALIZED definition is the thing that is wrong, and **the connector corrects it** — `ALTER TABLE ... MODIFY COLUMN` strips the expression so the source value is stored from then on, and the rows written before that are backfilled. |
| **DEFAULT / ordinary** | Normal replicated column. Bind the source value, including NULL. Never let a ClickHouse DEFAULT stand in for a value MySQL actually sent. |

The trap: a column that is MATERIALIZED in ClickHouse *and* present in
MySQL looks, to code that only inspects the ClickHouse catalog, like a
column ClickHouse owns. It is not. If MySQL sends it, it belongs to MySQL.
Any code path that decides column membership must therefore ask what MySQL
sent, not only what the ClickHouse metadata says.

### Failures this directive is meant to prevent

Every one of these has actually happened in this codebase:

- A NULL-valued source column being dropped from the INSERT, so ClickHouse
  substituted a column DEFAULT (`0` / `''` / `1970-01-01`) instead of NULL.
  Row counts matched; only a value-level checksum caught it.
- A newly added source column being replicated as schema but never
  backfilled, leaving a large historical range blank on the ClickHouse side
  while MySQL held the values.
- Treating a MATERIALIZED column as "correctly excluded" ClickHouse-owned
  state, rather than asking whether MySQL defines it.
- A per-record metadata re-read loop caused by mistaking a permanently
  ClickHouse-computed column for a stale cache.

The common shape: **the connector inferred correctness from the ClickHouse
side alone.** Do not do that. Check the source.

## Silence is the enemy

Because MySQL is the truth, any divergence is a defect — so it must be
loud:

- **Never swallow, mute, or downgrade a replication error** to make a batch
  succeed. A failed batch that retries is recoverable; a batch that writes
  wrong data and reports success is not.
- **Never let row counts stand in for correctness.** Every incident above
  had matching row counts. Value-level comparison
  (`db_compare/` checksum tooling) is the only proof of agreement.
- **Never declare "in sync", "converged", or "false positive"** on a
  checksum failure without comparing actual column VALUES against the MySQL
  host the checksum job locked, and naming *why* the checksum differed in
  the first place. "It just converged" is not an explanation.

## Working rules for changes in this repo

1. **Verify against the live source, not the code.** Reading a DDL file or
   a config does not tell you what MySQL holds. Query it.
2. **Trace the full path before changing a flag.** config → generator →
   template → consumer → runtime effect. A flag whose mechanism does not
   achieve the intent is not a fix.
3. **Do not break existing behaviour to fix new behaviour.** Search for
   consumers of anything you change; fix them in the same change or
   abandon it.
4. **Add a regression test that fails without your fix.** Mutation-check
   it: break the fix, confirm the test goes red. A test that passes either
   way protects nothing.
5. **Schema-cache and metadata code is high-risk.** It decides INSERT
   column membership, so a mistake there is silent data divergence, not a
   crash. Changes here need value-level verification, not just green unit
   tests.

## Repository shape

- `sink-connector/` — the Kafka Connect sink: batching, schema cache,
  query construction, ClickHouse writes.
- `sink-connector-lightweight/` — the standalone (embedded Debezium)
  runtime.
- `sink-connector-client/` — client/CLI.
- `sink-connector/python/db_compare/` — source↔ClickHouse count and checksum
  comparison tooling. This is the ground truth for "are the two sides
  actually equal?".
- `doc/` — feature matrix and reference docs.

Build with JDK 17 and Maven; `mvn -o test` works offline once dependencies
are cached.

## If this file conflicts with something else

The prime directive wins. If a comment, a doc, an old commit message, or
your own reasoning suggests that the ClickHouse side should be preserved
over the MySQL side, that source is wrong and should be corrected in the
same change.

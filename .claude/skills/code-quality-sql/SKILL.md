---
name: code-quality-sql
description: >
  Use when writing or reviewing SQL craft and style (queries, DDL,
  migrations, test fixtures) anywhere in this repo, or when asked for
  "worldclass"/"clean" SQL. Covers explicit column lists, CTEs, JOIN
  explicitness, SARGable predicates, DDL/type discipline, and portability
  across MySQL/Postgres (source) and ClickHouse (target).
---

# Worldclass SQL

## Overview
Readable, correct, performant SQL across ClickHouse (the replication
target), and MySQL/Postgres (the source of truth). **ClickHouse-specific
design** (engine selection, `ORDER BY` keys, partitioning, `FINAL`,
materialized views, codecs, sparse indexes) is a distinct body of
knowledge from general SQL craft — consult ClickHouse's own documentation
and this project's `doc/clickhouse_engines.md` and `specs/07-type-system`,
`specs/08-schema-catalog` domains for the engine-level rules that matter
for this connector. This skill is general SQL craft.

## When to Use
- Writing or reviewing any `.sql` query, DDL, or migration
- A user asks for "clean / worldclass / readable" SQL
- Reviewing a query for SARGability / index-usage problems

## Core Craft (portable)
Why these: clarity and index-friendliness. Most slow queries come from hidden
full scans (non-SARGable predicates) and most bugs from `SELECT *` plus implicit
conversions.

1. **Explicit column lists, never `SELECT *`** — `*` hides intent, breaks on schema
   change, inflates I/O. (Exception: throwaway exploration.) This matters
   doubly here: a schema drift between MySQL/Postgres and ClickHouse is
   exactly the class of bug this project exists to prevent, and `SELECT *`
   in comparison/verification tooling can hide a dropped column.
2. **CTEs for readability** — one logical step per CTE, composable and debuggable.
   While debugging, `SELECT * FROM last_cte` to inspect a stage.
3. **Consistent casing** — keywords UPPER, identifiers `snake_case`. Pick one and
   hold it.
4. **Meaningful aliases** — descriptive, not `a`/`b`. Always qualify columns in
   multi-table queries so origin is obvious.
5. **Explicit JOIN types** — `INNER JOIN`/`LEFT JOIN`, never bare `JOIN`, never
   comma-joins. Join conditions in `ON`, filters in `WHERE`.
6. **Set-based over row-by-row** — no cursors/loops where one statement works.
7. **Prefer JOINs to correlated subqueries**, but confirm with `EXPLAIN` — modern
   optimizers often rewrite them equivalently.

```sql
-- BAD
SELECT * FROM orders o
WHERE (SELECT count(*) FROM items i WHERE i.order_id = o.id) > 5;  -- correlated, *

-- GOOD
WITH item_counts AS (
    SELECT order_id, count(*) AS item_count
    FROM items
    GROUP BY order_id
)
SELECT o.id, o.customer_id, ic.item_count
FROM orders AS o
INNER JOIN item_counts AS ic ON ic.order_id = o.id
WHERE ic.item_count > 5;
```

## SARGability — the #1 performance rule
Keep the indexed column "clean" on the left of the predicate — never wrapped in a
function, never implicitly converted.

- **No functions on indexed columns in `WHERE`:** `WHERE YEAR(order_date) = 2023`
  forces a full scan. Rewrite as a range:
  `WHERE order_date >= '2023-01-01' AND order_date < '2024-01-01'`.
  Same for `LEFT()`, `COALESCE()`, `TRIM()` on the column side.
- **No implicit conversions** — comparing mismatched types casts the *column* and
  kills index use (string ↔ numeric, `NVARCHAR` vs `VARCHAR`). Cast the *value*,
  never the column.
- **No leading-wildcard `LIKE '%x%'`** — non-SARGable; prefer trailing `'x%'`.

## DDL Quality
- **Explicit, narrowest correct types.** Match types across joined/compared columns
  to avoid implicit conversions. On the ClickHouse side, type choice directly
  affects how DDL translation and the column-kind rules (ALIAS/MATERIALIZED/
  ordinary) apply — see `sink-connector-mysql-source-of-truth`.
- **Constraints** (NOT NULL, PK/FK, CHECK, UNIQUE) in MySQL/Postgres enforce
  integrity at the DB. *ClickHouse does not enforce constraints — model invariants
  upstream, at the source.*
- **Naming conventions** — `snake_case`, FK columns as `<entity>_id`, indexes named
  by table+columns; choose singular-vs-plural and hold it.
- **Migrations** — versioned, forward-only, reviewed. Re-run `EXPLAIN` on hot
  queries after any column-type change (a cast can silently shift sides).

## Deep Review Checklist (SQL)
Run over every changed SQL statement (files and SQL built in code) during
`pr-self-review`'s per-language pass. Report a hit as the broken invariant
with `file:line` and a concrete trace.

1. **Injection** — statements built by concatenating values or identifiers;
   identifiers not quoted when names can be reserved words or contain
   special characters.
2. **NULL semantics** — `= NULL`, `NOT IN (subquery containing NULL)`
   returning nothing, aggregates skipping NULLs, `COALESCE` hiding real NULLs
   in comparisons. This is especially load-bearing here: ordinary columns
   must bind a source `NULL` faithfully, not silently coerce it.
3. **Join cardinality** — an INNER join dropping rows a LEFT join must keep;
   a fan-out duplicating rows that are then summed.
4. **Non-determinism** — `LIMIT` without `ORDER BY`, `any()` or a bare
   `GROUP BY` picking arbitrary values, ties in window ordering.
5. **Types** — FLOAT for exact values, implicit casts that truncate or
   overflow, DECIMAL scale mismatch, signed vs unsigned, comparisons under
   different collations or case rules.
6. **Time** — naive timestamps across systems in different zones,
   `DateTime` vs `DateTime64` precision, inclusive/exclusive range bounds.
7. **Unbounded work** — missing partition/primary-key predicates,
   `SELECT *`, N+1 query loops, no LIMIT on interactive queries. On
   replicated/target tables this also risks Invariant I14 (Bounded
   Bookkeeping) — an unbounded scan needs an `I14-scan-allowed:` marker or
   it should be rejected.
8. **Multi-statement writes** that must be atomic but are not in a
   transaction (on ClickHouse: not idempotent on retry).
9. **DDL safety** — table rewrites, locks, `DROP`/`TRUNCATE` without
   bounded blast radius (see `destructive-operation-safety`), mutations
   where a lightweight operation exists.

## Anti-Patterns
`SELECT *` · functions/implicit casts on indexed columns (non-SARGable) ·
leading-wildcard `LIKE` · N+1 per-row query patterns · comma-joins · unqualified
columns in multi-table queries · trusting a "rewrite correlated subquery" rule
without checking `EXPLAIN`.

## Sources
sqlstyle.guide (Holywell) · mattm/sql-style-guide · Meltano SQL Style Guide ·
Baeldung: SQL SARGability. ClickHouse design: `doc/clickhouse_engines.md` and
ClickHouse's own documentation.

## Cross-References
`pr-self-review`, `sink-connector-mysql-source-of-truth`,
`destructive-operation-safety`.

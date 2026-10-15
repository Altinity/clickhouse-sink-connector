---
name: code-quality-python
description: >
  Use when writing or reviewing Python in this repo's tooling
  (sink-connector/python — db_compare, ch_sink_tools, mysql_resync and
  friends), or when asked for "worldclass"/"idiomatic" Python. Covers type
  hints, error handling, the Ruff/Black/mypy toolchain, and a deep review
  checklist for the defect classes that matter in operational CLI tools.
---

# Worldclass Python

## Overview
Idiomatic, typed Python for this project's tooling under
`sink-connector/python` — comparison/verification tools (`db_compare`),
the `ch_sink_tools` package, and operational scripts such as
`mysql_resync.py`. These tools are run by operators against production
data, so correctness and loud failure matter more than cleverness.

## When to Use
- Writing or reviewing any `.py` file under `sink-connector/python`
- A user asks for "worldclass / idiomatic" Python
- Reviewing a CLI tool, comparison script, or data-repair utility for
  correctness and failure handling

## Core Rules

1. **Type hints on every public function signature.** Run mypy (or the
   project's configured type checker) in CI; untyped internals are
   tolerable, untyped public APIs are not.
2. **`dataclasses` (or `attrs`) for structured data**, not loose dicts
   passed around and mutated in place.
3. **Explicit over implicit** — no bare `except:`; catch the specific
   exception type you can actually handle, and let everything else
   propagate loudly.
4. **Context managers for resources** — DB connections, file handles,
   anything with a lifecycle — `with` blocks, not manual
   open/close pairs.
5. **f-strings for formatting**; no `%`-formatting or `.format()` in new
   code.
6. **`pathlib.Path` over string path manipulation.**
7. **Argument parsing via `argparse`/`click`**, with `--dry-run` as a
   first-class, well-tested mode for anything that mutates data — this
   matters especially for destructive/repair tooling (see
   `destructive-operation-safety`).
8. **Logging, not `print()`,** for anything beyond a CLI's final summary
   output; structured, leveled, and loud on failure (never swallow an
   exception and continue silently — see Invariant I9, Loud Failure).

```python
# BAD
try:
    result = compare_tables(a, b)
except:
    print("something went wrong")

# GOOD
try:
    result = compare_tables(a, b)
except TableComparisonError:
    logger.exception("comparison failed for %s vs %s", a, b)
    raise
```

## Ruff Rule Sets
A reasonable baseline Ruff configuration for this kind of operational
tooling selects at least: `E`/`F` (pyflakes/pycodestyle core), `B`
(bugbear — catches common real bugs like mutable default arguments), `UP`
(pyupgrade — keep syntax current), `SIM` (simplify), and `C90`
(complexity). Run `ruff format` for formatting and `ruff check --fix` for
the rest; wire both into CI rather than relying on local habit.

## Deep Review Checklist (Python)
Run this over every changed `.py` file during `pr-self-review`'s
per-language pass.

1. **Mutable default arguments** (`def f(x, items=[])`) — the classic
   shared-state bug; default to `None` and assign inside the function.
2. **Bare or overly broad `except` clauses** that hide the real error or
   swallow a `KeyboardInterrupt`/`SystemExit`.
3. **Resource leaks** — file handles, DB connections, or cursors opened
   without a `with` block, especially on an error path.
4. **Off-by-one and boundary handling** in anything doing comparison
   batching, pagination, or chunked reads of a replicated table.
5. **Silent coercion** — comparing or hashing values across types without
   checking that `NULL`/`None` is handled the same way the source database
   handles it; a comparison tool that treats `NULL` and empty string as
   equal can hide a real divergence.
6. **Dry-run vs apply divergence** — for any tool with both modes
   (`mysql_resync.py` and similar), does the dry-run path actually execute
   the same logic as the apply path up to the point of mutation, or is it
   a separate, driftable code path that can silently stop matching reality?
7. **Unbounded work** — a scan or comparison over an entire table with no
   batching/limit, especially relevant given Invariant I14 (Bounded
   Bookkeeping) for anything touching replicated or target-table data.
8. **Hardcoded version-dependent assumptions** — a check that assumes one
   MySQL/ClickHouse version's behavior without verifying it still holds on
   others the tool is expected to support.

## Testing
Prefer `pytest` with fixtures over ad hoc setup/teardown. Every bug fix in
this tooling should add a test that fails without the fix — a test that
only re-asserts current behavior proves nothing. For comparison/repair
tools, test both the dry-run and apply code paths explicitly, not just one.

## Anti-Patterns
Bare `except:` · mutable default arguments · `print()` for anything beyond
final CLI output · swallowing exceptions in repair/comparison tooling ·
untyped public function signatures · a dry-run mode that is a separate,
unverified code path from the real one.

## Sources
PEP 8 / PEP 484 (typing) · Ruff and mypy documentation · general Python
anti-pattern references (mutable defaults, broad excepts).

## Cross-References
`pr-self-review`, `code-quality-architecture`, `destructive-operation-safety`,
`sink-connector-mysql-source-of-truth`.

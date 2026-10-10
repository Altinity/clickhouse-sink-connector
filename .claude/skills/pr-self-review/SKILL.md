---
name: pr-self-review
description: >
  Use before opening a PR against clickhouse-sink-connector, or when asked to
  review a diff/PR in this repo. Entry-point review skill: five mandatory
  gates (Contract, Impacted surface, Failure and divergence, Evidence,
  Lower-priority quality), triggered expansions, a severity model
  (Blocker/Major/Nit), and the spec-citation convention used in PR
  descriptions. Load the matching code-quality-<lang> skill for each changed
  language, and code-quality-architecture for the design pass.
---

# PR Self-Review

## Overview
A disciplined, gate-based review pass — run it on your own diff before
opening a PR, and run the same gates when reviewing someone else's. The
goal is not style nitpicking; it is catching contract breaks, unhandled
failure modes, and claims unsupported by evidence, in that priority order.

This skill assumes the project's own ground rules from `AGENTS.md`: MySQL
(or Postgres) is the source of truth, the connector is an active enforcer of
convergence, spec-first development governs any behavior change, and
`python3 scripts/validate_specs.py` must pass before a PR is opened. Those
rules are not restated here — read `AGENTS.md` and `specs/CONSTITUTION.md`
first if you have not.

## The Five Gates

Run every diff through all five, in order. Do not skip ahead to style
issues (Gate 5) before the first four are clear — a Blocker in Gate 1
makes Gate 5 findings moot.

### Gate 1 — Contract
Does the change honor the contracts it touches: method/function signatures,
on-disk or wire formats, config schema, the public behavior of a CLI tool,
and — specific to this project — the **column-kind contract** (ALIAS is
never stored; MATERIALIZED on the source wins and the connector must
reissue `ALTER TABLE ... MODIFY COLUMN` rather than drift; ordinary/DEFAULT
columns bind the source value, including `NULL`)? A change that is
internally consistent but silently redefines a contract a caller or a
downstream consumer depends on is a Blocker, not a Nit.

### Gate 2 — Impacted surface
What else reads or writes the thing you changed? Trace forward: config →
generator → consumer → runtime effect (this mirrors `AGENTS.md`'s own
working rule for tracing a change before it ships). Grep for other callers,
other modules in `sink-connector`, `sink-connector-lightweight`, and
`sink-connector-client` that assume the old shape, and any spec file under
`specs/` that documents the old behavior and now needs an update alongside
the code.

### Gate 3 — Failure and divergence
How does this fail, and does it fail loudly? Per Invariant I9 (Loud
Failure), a swallowed exception, a silently skipped row, or a retry that
masks a permanent error is a defect class on its own, independent of
whether the happy path works. For anything touching offsets, the batch
executor, or schema application, check it against the relevant invariant
in `specs/CONSTITUTION.md` (I2 Deterministic Version Monotonicity, I8
Durable Offset Quiescence, I14 Bounded Bookkeeping, I15 Bounded Declared
Recovery) rather than inventing a new informal rule.

### Gate 4 — Evidence
Is every claim in the PR description backed by something a reviewer can
re-run? "Tests pass" with no test added for the new behavior is not
evidence. "Fixes the race" with no repro before and proof-of-fix after is
not evidence. Prefer the project's own dual-verification bar: an empirical
test that fails without the fix, and — where the change touches a formally
specified invariant — an update to the matching Lean 4 proof under
`formal_specs/lean`. A test that merely re-asserts the implementation
(asserts what the code does, not what it must do) is not evidence either.

### Gate 5 — Lower-priority quality
Only after Gates 1–4 are clear: naming, duplication, dead code,
`DESIGN-NOTE:` debt that should have been resolved, and the
language-specific deep checklist (see below). These are real findings —
just never let them substitute for a missing Gate 1–4 check.

## Triggered Expansions

Some diff shapes mandate extra scrutiny beyond the base five gates:

- **Touches anything under `specs/`** → cross-check the spec actually
  matches the code it describes; run `python3 scripts/validate_specs.py`
  and quote its output in the PR description, not just "ran it."
- **Touches a destructive statement** (`DROP`, `TRUNCATE`, `DELETE`,
  `ALTER ... DROP`, `DETACH PARTITION`, `rm -rf`, or an apply-mode run of a
  resync/repair tool) → load `destructive-operation-safety`.
  `scripts/review_gates.py`'s **destructive** gate checks for a
  `DESTRUCTIVE:` comment and a `Destructive-Op-Check:` trailer in CI, but a
  human reviewer must still judge whether the blast radius claim is true.
- **Touches config, a CLI flag, or anything matching the "mandatory
  validation" trigger list** → load `mandatory-code-change-validation`
  and trace the full execution path, not just the file you edited.
- **Touches MySQL/Postgres-to-ClickHouse semantics** (column kinds, DDL
  translation, type mapping, offset/transaction handling) → load
  `sink-connector-mysql-source-of-truth` and the project-specific review
  checklist in `references/clickhouse-sink-connector-review.md` below.
- **Reuses or duplicates existing logic** → load `code-reuse-review` /
  `code-reuse-first`.
- **Adds a shortcut, a hardcoded limit, or a "good enough for now" branch**
  → check for (or demand) a `design-note-debt` comment.

## Language Router (Step: per-file deep checklist)

For each changed file extension, load the matching deep-review checklist
before writing up findings:

| Extension | Skill |
|---|---|
| `.java` | `code-quality-jvm` |
| `.py` | `code-quality-python` |
| `.go` | `code-quality-go` |
| `.sh` | `code-quality-bash` |
| `.sql` (embedded or standalone) | `code-quality-sql` |

After all changed languages are covered, run `code-quality-architecture`
for the cross-cutting design pass (module boundaries, dependency direction,
API surface, test pyramid).

## Domain Pass — This Project Specifically

Beyond generic code quality, a PR against this repo should be checked
against:

1. **Prime directive conformance** — does the change treat MySQL/Postgres
   as the source of truth, or does it let ClickHouse silently diverge and
   call that acceptable? (`sink-connector-mysql-source-of-truth`)
2. **Spec citation** — does the PR description cite the spec(s) it
   implements or changes, in the form `(Spec NN.MM §section)`, or
   explicitly mark deliberate deviations `[spec-exempt: <reason>]`? An
   uncited behavior change to a spec'd area is a Gate 1 finding, not a
   nitpick.
3. **Invariant alignment** — for any of the fifteen invariants in
   `specs/CONSTITUTION.md` that the diff touches, does the PR either keep
   the matching Lean 4 proof green or explain why the invariant does not
   apply?
4. **Bounded work** — per Invariant I14, any new scan of replicated or
   target-table data must carry an `I14-scan-allowed: <spec reference>`
   marker or be rejected outright; `scripts/validate_specs.py` pass 8
   checks for this mechanically, but a reviewer should still ask "is this
   scan bounded by what it needs, or by total table size?"

## Severity Model

- **Blocker** — breaks a contract, an invariant, or correctness; must be
  fixed before merge.
- **Major** — a real defect or missing coverage that should be fixed before
  merge but doesn't block discussion of the rest of the PR.
- **Nit** — style, naming, or a minor readability improvement; call it out,
  don't block on it.

State your confidence on anything you can't fully verify ("I believe this
races, but I haven't reproduced it — worth a second look") rather than
asserting it as fact. Keep a short "Declined to judge" list for anything
out of scope or outside your expertise (e.g., a Lean proof correctness
question beyond a plausibility check) rather than silently skipping it.

## Producer-Side Self-Review

Before opening the PR, ask the adversarial questions a reviewer would ask:
What's the smallest input that breaks this? What happens on restart
mid-batch? What happens if the source schema changes between the batch
being read and the batch being applied? If you can't answer, that's a gap
to close, not a reviewer's problem to discover later.

## Output Format

For each finding: severity, `file:line`, the broken invariant or contract
in one sentence, and — for anything non-obvious — a concrete trace showing
the failure path, not just an assertion that one exists. Group findings by
gate, not by file, so Blockers surface first regardless of where they live.

## Receiving Review Feedback

Treat a maintainer's review comment as a request for more evidence, not a
verdict to accept or argue with symmetrically. If you disagree, show the
trace that supports your position; don't just restate your original claim.
When a reviewer is also the domain owner of the invariant or spec in
question, their reading of that spec's intent carries more weight than a
generic style preference — defer on domain intent, push back with evidence
on everything else.

## Sources
This skill's gate structure draws on general code-review methodology and
on the gate-based approach used by Altinity's ClickHouse review practice —
see `references/clickhouse-sink-connector-review.md` for the
project-specific per-invariant checklist adapted from that methodology.

## Cross-References
`code-quality-architecture`, `code-quality-jvm`, `code-quality-python`,
`code-quality-go`, `code-quality-bash`, `code-quality-sql`,
`mandatory-code-change-validation`, `destructive-operation-safety`,
`sink-connector-mysql-source-of-truth`, `design-note-debt`,
`code-reuse-review`, `code-reuse-first`.

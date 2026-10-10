---
name: mandatory-code-change-validation
description: >
  Use before considering any non-trivial code change complete — especially
  one touching config, a CLI flag, shared/hot-path code, or anything the
  automated gates don't fully cover. Defines what counts as real validation
  (beyond "tests pass") and a checklist for tracing a change's full effect
  before calling it done.
---

# Mandatory Code Change Validation

## Overview
`scripts/review_gates.py` enforces baseline checks in CI (the hygiene and
merge-stop gates catch build-breaking issues and unresolved merge
artifacts; the destructive and license gates cover their own narrow
concerns — see `destructive-operation-safety`). None of those gates can
tell you whether a change actually does what it claims. This skill covers
the validation that requires human judgment: tracing the real effect of a
change, not just checking that it compiles and the existing tests still
pass.

A written test plan in a PR description is not validation. Validation is
the code actually being executed, with the output quoted in the PR. The
two are easy to conflate, and the gap between them is where real defects
hide: a plausible-sounding test plan can describe a scenario that was never
actually run, or that was run against the wrong code path.

A past change that modified a template-inclusion path without tracing where
it was actually referenced from caused a runtime failure in production,
because the referenced file could not be resolved under the new path — a
reminder that config and path changes deserve the same execution-path
tracing as application code, not a quick glance at the diff.

Similarly, a past "defensive" change made a per-event check more thorough
without checking its cost; the check's complexity grew with total data
volume rather than with the size of the thing it was actually inspecting,
and it became the bottleneck under production load. The general lesson —
a validator's or guard's cost must be bounded by what it inspects, not by
total repo/data size — is exactly the discipline this project's own
Invariant I14 (Bounded Bookkeeping) enforces for the connector's
bookkeeping paths, and `scripts/validate_specs.py` pass 8 checks for it
mechanically. The same question belongs in any code review: does this
check's cost scale with the input, or with everything?

## Mandatory Pre-Commit Checklist

1. **Trace the full execution path.** For any change to config, a flag, or
   a schema-mapping rule: config → generator/parser → consumer → runtime
   effect. This is `AGENTS.md`'s own stated rule for this project — follow
   it literally, not just in spirit. Don't stop at "the code that reads the
   new flag looks right"; confirm the flag actually reaches that code under
   the conditions it's meant to.
2. **Check pattern consistency against recent history.** Look at how
   similar changes were made recently (`git log -p` on the touched file or
   area) before diverging from an established pattern without a reason.
   A new approach that's locally reasonable but inconsistent with
   everything else in the module is itself a maintenance cost.
3. **Functional validation, not a described test plan.** Actually run the
   change against a realistic scenario — not just the unit test added for
   it — and include the real output in the PR description. If the change
   can't practically be run end-to-end before review, say so explicitly
   and explain what was validated instead.
4. **Error-handling assessment.** For every new failure mode the change
   introduces, confirm it fails loudly (Invariant I9) rather than being
   silently absorbed, and that the resulting error message gives an
   operator enough context to act.
5. **Blast-radius verification.** For a change to shared or widely-used
   code, enumerate (don't guess) every caller/consumer affected — see
   `code-quality-architecture` and `code-reuse-review` — and confirm the
   change doesn't break an assumption one of them depends on.

## Integration with Other Review Steps

This checklist runs as part of `pr-self-review`'s triggered-expansion step
for config/flag/hot-path changes. For destructive operations specifically,
use `destructive-operation-safety` instead/in addition. For a change to
MySQL/Postgres-to-ClickHouse replication semantics, cross-check against
`sink-connector-mysql-source-of-truth`.

## Anti-Patterns
A PR description that says "added tests" with no actual test output shown
· a config change validated only by reading the code, never by running it
· a per-event or per-row check whose cost was never measured against a
realistic data volume · diverging from an established pattern with no
stated reason · treating "no automated gate complained" as equivalent to
"this was validated."

## Cross-References
`pr-self-review`, `destructive-operation-safety`, `code-quality-architecture`,
`sink-connector-mysql-source-of-truth`.

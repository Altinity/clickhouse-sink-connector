---
name: repro-harness-negative-proof
description: >
  Use when building or running a reproduction harness, a cross-version
  comparison, or any test asserting that a bug is absent/fixed. Defines the
  rules for proving a negative result is real rather than a harness
  failure masquerading as a clean result.
---

# Repro Harness Negative Proof

## Overview
"No error was reported" is not the same as "the thing we were testing for
is actually absent." A harness that silently fails to execute its setup
phase on one target, then reports no error on the comparison phase,
produces exactly the same output as a harness that genuinely found nothing
wrong. This skill is the discipline for telling those two apart.

A past investigation into a suspected defect reported one version as
"unaffected," when in fact the harness's setup phase had silently failed
to run against that version — the absence of errors was mistaken for a
clean result, when it was actually a harness failure. This is the single
most common way a negative-proof harness lies, and it is exactly what the
rules below are designed to catch.

This matters especially for this project because of where cross-version
and cross-dialect behavior differences actually live: DDL translation
across source database versions, type-mapping edge cases, and ClickHouse
version-specific behavior are all places where "it worked" and "the test
never actually ran" look identical unless the harness proves otherwise.

## The Rules

1. **Positive proof per phase.** Every phase of a harness (setup, the
   actual trigger/action, verification) must emit positive evidence that
   it ran and ran correctly — not just the absence of an error. A setup
   phase should assert the precondition it created actually exists before
   moving on; a trigger phase should assert the action it took actually
   happened (e.g., a row was actually inserted, not just that the INSERT
   statement didn't throw).
2. **Non-zero exits abort immediately — never continue past them.** A
   harness that logs a warning and keeps going when a setup step fails can
   end up "testing" a target that was never actually prepared correctly.
3. **A zero-denominator case is a harness failure, not a 0% defect rate.**
   If the comparison phase finds zero things to compare (e.g., zero rows
   matched the condition being tested), that's not evidence the defect is
   absent — it's evidence the harness didn't actually exercise the
   scenario. Treat it as a failure, investigate, don't report it as a pass.
4. **Never hardcode version-dependent details into the harness itself.**
   A harness whose assertions assume one specific version's file format,
   error code, or behavior will silently stop testing anything meaningful
   the moment that detail changes upstream, while still appearing to run
   successfully. Derive expected values from the system under test, or
   make the harness fail loudly when an assumption no longer holds.

## Worked Example (illustrative — a real upstream ClickHouse class of bug)

A mutation applied against a table whose data was written in an older
on-disk mark-file format could silently stop progressing on some versions
while completing without error on others, with the difference traceable to
a mark-file format change between versions (e.g., `.mrk3` vs `.mrk4`
variants) rather than to the mutation logic itself. A harness checking "did
the mutation complete" without also checking "did it actually touch the
expected number of parts" would report the broken version as healthy,
because the symptom was a silently-reduced work set, not an error.

The lesson generalizes: a harness must verify the *shape* of what happened
(how much work was done, on what), not just whether an error was raised.

## Pre-Publish Checklist (before trusting/reporting a result)

- [ ] Every phase (setup, trigger, verify) has positive proof it executed,
      not just "no exception was thrown"
- [ ] Any non-zero exit code anywhere in the harness aborted the run
      rather than being logged and ignored
- [ ] Any ratio/percentage reported has a non-zero, sanity-checked
      denominator
- [ ] No assertion in the harness hardcodes a version-specific detail that
      could silently go stale
- [ ] The harness's own exit code/verdict was checked, not just its stdout
- [ ] The test ran in an isolated test environment matching the target
      configuration, not a convenient but non-representative one

## Anti-Patterns
Treating "no error" as proof of correctness · a comparison with a
zero-row denominator reported as a clean pass · a harness that logs and
continues past a failed setup step · hardcoding a specific version's file
format or error code into an assertion meant to generalize across
versions · trusting a harness's stdout over its actual exit code.

## Cross-References
`pr-self-review`, `sink-connector-mysql-source-of-truth`.

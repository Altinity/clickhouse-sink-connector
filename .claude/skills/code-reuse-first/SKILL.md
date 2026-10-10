---
name: code-reuse-first
description: >
  Use before writing any new code — a function, a script, a helper. Walks
  the "reuse ladder" from checking whether the thing already exists down to
  writing the minimum new code, so you don't duplicate logic that's already
  in this repo, the standard library, or a dependency already in use.
---

# Code Reuse First

## Overview
Before writing new code, climb down this ladder one rung at a time. Stop
as soon as a rung answers the need — most new-code requests can be
satisfied without writing anything new at all.

## The Ladder

1. **Is this actually requested, or assumed?** Confirm the task actually
   needs new code before writing any — sometimes the "new feature" is
   already achievable with an existing flag, config option, or CLI
   invocation.
2. **Does it already exist in this repo?** Grep for the concept across
   `sink-connector`, `sink-connector-lightweight`, `sink-connector-client`,
   and `sink-connector/python`. Search by behavior/concept, not just by
   guessing a function name.
3. **Have you actually read what you found?** A file with a plausible name
   is not the same as confirming it does what you need. Open it.
4. **Does the standard library already do this?** Especially true in
   Python and Go — reaching for a new dependency or a hand-rolled utility
   for something `itertools`, `pathlib`, or the Go standard library already
   does well is a common source of needless code.
5. **Does the platform/framework already provide this?** JDBC, the
   ClickHouse/MySQL/Postgres client libraries already in use, or the
   project's own CDC-engine abstractions may already cover the need.
6. **Is there already a dependency in this repo that does this?** Prefer
   extending or configuring an existing, already-vetted, already-pinned
   dependency over adding a new one for a narrow need.
7. **Only then, write the minimum new code** — the smallest addition that
   solves the actual, confirmed need, placed where similar logic already
   lives (see `code-quality-architecture`) rather than in a new, parallel
   location.

## When Reuse Is the Wrong Answer

Reuse is wrong when the "existing" thing was built for a different
contract and bending it to fit would make both call sites worse, or when
reusing a module would reverse a dependency direction that shouldn't exist
(e.g., `sink-connector-client` reaching into JVM-only internals). In that
case, write new code — but check whether the change affects existing
callers first (grep for consumers, trace blast radius) rather than
assuming a one-off addition is isolated.

## Checklist

- [ ] Confirmed the need is real, not assumed
- [ ] Searched the repo for an existing equivalent (by concept, not just name)
- [ ] Actually read what was found before deciding it doesn't fit
- [ ] Checked stdlib / platform features before a new dependency
- [ ] Checked existing dependencies before adding a new one
- [ ] New code, if written, lives alongside similar existing logic
- [ ] Checked who else would be affected by extending vs. duplicating

## Anti-Patterns
Writing a new helper without searching first · finding an existing file and
assuming its contents from the name alone · reaching for a new dependency
before checking the standard library · adding a parallel implementation
instead of extending the existing one "to avoid touching other code."

## Cross-References
`code-reuse-review`, `code-quality-architecture`, `design-note-debt`.

---
name: design-note-debt
description: >
  Use when a change deliberately simplifies something (a shortcut, a
  hardcoded limit, a "good enough for now" branch), and when reviewing a PR
  to check that existing simplifications are still within their stated
  ceiling. Defines the DESIGN-NOTE comment convention for tracking
  intentional technical debt.
---

# Design-Note Debt

## Overview
Not all technical debt is accidental — sometimes the right call is a
deliberate simplification with a known ceiling. The problem is when that
decision is made silently, with no record of what was simplified, what its
limit is, or what should trigger revisiting it. This skill defines a single
grep-able convention for recording that decision.

## The Convention

```
# DESIGN-NOTE: <what was simplified>; <ceiling>; <upgrade trigger>.
```

Three parts, always in this order:

1. **What was simplified** — the actual shortcut taken, in plain language.
2. **Ceiling** — the concrete limit beyond which this breaks (a row count,
   a throughput number, a specific unsupported case).
3. **Upgrade trigger** — what observable condition means it's time to
   revisit this (a metric crossing a threshold, a new requirement, a
   specific feature request).

```java
// DESIGN-NOTE: batch size is fixed at 1000 rows rather than adaptive;
// ceiling is ~50 MB/batch before heap pressure shows up under default JVM
// settings; revisit if operators report OOM at the default heap size or if
// source tables with >10KB average row size become common.
```

## When to Add One

Any time you take a shortcut that a more thorough implementation would
avoid: a hardcoded limit instead of a computed one, a single-source-version
assumption instead of generalized handling, a synchronous call where an
async one would scale better, a narrower error-handling path than the full
problem deserves. If you catch yourself writing "for now" or "good enough"
in a comment or PR description, that's the signal to write a `DESIGN-NOTE`
instead.

## When Reviewing

During `pr-self-review`'s Gate 5 pass, grep the diff (and, periodically,
the whole repo) for `DESIGN-NOTE:`:

```bash
grep -rn "DESIGN-NOTE:" sink-connector sink-connector-lightweight \
    sink-connector-client sink-connector/python
```

For each hit:
1. Is the ceiling still accurate, or has the system grown past it unnoticed?
2. Has the upgrade trigger already fired, with nobody following up?
3. Is this DESIGN-NOTE now covering code so central that it should instead
   become its own spec entry under `specs/`, and be fixed properly?

A `DESIGN-NOTE` comment is not permission to defer forever — it's a promise
with a trip wire. Reviewing stale ones periodically is how that promise
gets kept.

## Anti-Patterns
A shortcut with no comment at all (the debt is invisible) · a comment that
says "TODO: fix this later" with no ceiling or trigger (not actionable) ·
a `DESIGN-NOTE` whose trigger fired long ago with no follow-up PR · treating
the comment as sufficient documentation for something that should actually
be a spec.

## Cross-References
`code-reuse-first`, `pr-self-review`.

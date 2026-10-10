---
name: code-quality-architecture
description: >
  Use for the cross-cutting design pass on a PR — module boundaries,
  dependency direction, API surface, and build/test/CI hygiene — across
  clickhouse-sink-connector's Java modules (sink-connector,
  sink-connector-lightweight), its Python tools (sink-connector/python),
  and its Go client (sink-connector-client). Run after the per-language
  deep checklist, as the last pass in pr-self-review.
---

# Architecture & Cross-Language Quality

## Overview
Language-specific style lives in `code-quality-jvm`, `code-quality-python`,
`code-quality-go`, `code-quality-bash`, and `code-quality-sql`. This skill
is the layer above: does the change fit the module it lives in, does it
leak across a boundary it shouldn't, and does the project's build/test
machinery actually exercise it.

## Language Skill Router

| Language | Skill | Primary module(s) in this repo |
|---|---|---|
| Java | `code-quality-jvm` | `sink-connector`, `sink-connector-lightweight` |
| Python | `code-quality-python` | `sink-connector/python` (db_compare, ch_sink_tools, etc.) |
| Go | `code-quality-go` | `sink-connector-client` |
| Bash | `code-quality-bash` | build/CI helper scripts |
| SQL | `code-quality-sql` | embedded queries, DDL, migrations, test fixtures |

## When to Use
- The design/architecture pass at the end of a multi-language PR
- A user asks for "worldclass" structure, module boundaries, or API design
- Reviewing whether a new dependency or module split is justified

## Core Principles (portable across languages)

1. **Dependency direction matches the module graph.** `sink-connector-client`
   (Go) should not reach back into JVM-only concerns; `sink-connector/python`
   tooling should depend on stable interfaces (CLI, file formats, DB
   schemas) rather than reaching into JVM internals.
2. **Single Responsibility at the module and class/package level.** A
   module that mixes CDC-engine concerns with verification-tooling concerns
   is a sign the split is wrong, not that more branches are needed.
3. **Program to interfaces, not implementations**, at module boundaries
   especially — a consumer of the offset store or the schema cache should
   depend on an interface/contract, not a concrete class, so the
   implementation can change without rippling outward.
4. **Explicit dependencies, pinned builds.** Lockfiles/pinned versions
   committed for every language toolchain in use; don't let an unpinned
   transitive dependency change behavior silently between builds.
5. **Build/test/CI as a gate, not a suggestion.** A change that builds
   locally but isn't exercised by `mvn -o test` (offline, per `AGENTS.md`),
   the Python test suite, or the Go module's tests is unverified, not
   verified-by-absence-of-complaint.
6. **Spec-first still applies at the architecture level** — a module
   boundary change (new module, new package, new public interface) is
   itself a design decision that belongs in `specs/` before the code, per
   the Smart Ralph Protocol.

## Review Checklist (architecture pass)

1. **New dependency added** — is it justified over stdlib / an existing
   in-repo utility? (See `code-reuse-first`.) Is it pinned?
2. **New public API/interface** — is it the minimum surface needed, or
   does it expose internals that should stay package-private/internal?
3. **Module boundary crossed** — does a change in one module (e.g.
   `sink-connector`) require a change in another (`sink-connector-client`,
   `sink-connector-lightweight`) to stay consistent? If so, is that
   captured by a shared spec rather than duplicated knowledge?
4. **Test pyramid shape** — does the change add a unit test at the right
   level, or only a slow end-to-end test that will be the only thing that
   ever catches a regression here?
5. **CI gates** — `scripts/review_gates.py` enforces four baseline gates in
   CI (destructive, merge-stop, license, hygiene); this pass checks what
   those gates can't: whether the design itself is sound.

## Anti-Patterns
God modules that mix CDC-engine, verification-tooling, and CLI concerns ·
circular dependencies between modules · concrete-type APIs where an
interface would do · unpinned dependencies · a module boundary that exists
only because of how the code grew, not because of any real separation of
concerns.

## Sources
General SOLID/clean-architecture principles · this project's own
`AGENTS.md` (module shape, build rules) and `specs/SMART_RALPH_PROTOCOL.md`
(spec-first discipline).

## Cross-References
`pr-self-review`, `mandatory-code-change-validation`, `code-reuse-first`,
`code-reuse-review`.

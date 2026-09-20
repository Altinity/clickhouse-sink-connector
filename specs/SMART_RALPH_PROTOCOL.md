# Smart Ralph Protocol: Spec-Driven Agentic Engineering

## 1. Overview & Inspiration

This repository adopts the **Smart Ralph** spec-driven development methodology (inspired by [tzachbon/smart-ralph](https://github.com/tzachbon/smart-ralph)).

In an agentic software development environment, uncontrolled code modification leads to architectural drift, subtle regression, and unverified assumptions. The Smart Ralph protocol enforces that:
> **Every agentic change to the code must first declare the specification for the change and implement exactly what was declared.**

No autonomous agent, AI copilot, or human developer may modify production code without first documenting the intent, boundaries, invariants, and verification criteria in a structured specification.

Specifications are organized into **small, highly encapsulated documents** across 11 architectural domains (`specs/01-cdc-engine` through `specs/11-verification-tooling`), ensuring modular evolvability.

---

## 2. The Spec-Driven Development Lifecycle

The engineering workflow follows seven discrete, sequential phases:

```
+-----------------------------------------------------------------------------------+
|                           SMART RALPH WORKFLOW PHASES                             |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  [Phase 1: Triage]                                                                |
|       Evaluate request scope; identify affected domain (Domains 01–11).           |
|       Determine whether change touches an existing micro-spec or adds a new one.  |
|                                                                                   |
|  [Phase 2: Research]                                                              |
|       Analyze live 2.11.0 codebase; trace data paths and method contracts.        |
|       Identify concurrency hazards, locks, and ordering constraints.              |
|                                                                                   |
|  [Phase 3: Requirements Specification]                                            |
|       Author `specs/<domain>/<number>-<feature-slug>/requirements.md`             |
|       (or update the corresponding micro-spec in `specs/<domain>/`).             |
|       Define user stories, inputs, outputs, and edge conditions.                  |
|                                                                                   |
|  [Phase 4: Architectural Design & Invariant Alignment]                            |
|       Author/update design sections mapping state transitions.                    |
|       Check against System Constitution (Invariants I1–I13).                      |
|       Update Lean 4 formal model (`formal_specs/lean/`) if invariants change.     |
|                                                                                   |
|  [Phase 5: Task Breakdown]                                                        |
|       Define atomic, numbered implementation tasks with test criteria.            |
|                                                                                   |
|  [Phase 6: Implementation]                                                        |
|       Execute code changes strictly conforming to declared tasks.                 |
|       Maintain `.progress.md` tracking completed items. Zero unrequested scope.  |
|                                                                                   |
|  [Phase 7: Dual Verification]                                                     |
|       Run unit & integration test suites.                                         |
|       Validate formal Lean simulation proofs (`lake build`).                       |
|       Run spec validator (`python3 scripts/validate_specs.py`).                  |
|                                                                                   |
+-----------------------------------------------------------------------------------+
```

---

## 3. Specification Encapsulation Standards

Every specification document in this repository must adhere to the standard encapsulation schema:
1. **Executive Summary & Purpose**: Concrete statement of the component's role and boundaries.
2. **Codebase Mapping**: Specific Java classes, methods, and files on the active branch.
3. **Operational Specification / Flow**: Detailed algorithms, data structures, and state transitions.
4. **Invariants Preserved**: Explicit mapping to System Constitution Invariants (`I1`–`I13`).
5. **Verification Criteria**: Unit tests, integration tests, and formal verification links.

---

## 4. Agent Execution Rules (Non-Negotiable)

1. **Spec First, Code Second**:
   An agent must never emit file edits to `.java` or configuration files before the corresponding specification files exist and are populated.
2. **Strict Fidelity (Zero Scope Creep)**:
   An agent must implement *only* what was declared in the specification. It is strictly forbidden to:
   - Add speculative utility functions or generic wrappers.
   - Refactor adjacent methods or files not mentioned in the spec.
   - Introduce unrequested configuration options or dependencies.
3. **The Prime Directive Overrides All**:
   No specification or implementation shall violate the Prime Directive: MySQL is the source of truth, ClickHouse must conform, and MySQL values always win over ClickHouse defaults.
4. **Small Encapsulation Rule**:
   Prefer adding or updating a small, focused micro-spec within the relevant domain directory over creating sprawling, monolithic specification documents.
5. **Spec Validation Gate**:
   Before submitting any PR, `python3 scripts/validate_specs.py` must pass with exit code 0.

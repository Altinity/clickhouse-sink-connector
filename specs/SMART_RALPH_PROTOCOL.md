# Smart Ralph Protocol: Spec-Driven Agentic Engineering

## 1. Overview & Inspiration

This repository adopts the **Smart Ralph** spec-driven development methodology (inspired by [tzachbon/smart-ralph](https://github.com/tzachbon/smart-ralph)).

In an agentic software development environment, uncontrolled code modification leads to architectural drift, subtle regression, and unverified assumptions. The Smart Ralph protocol enforces that:
> **Every agentic change to the code must first declare the specification for the change and implement exactly what was declared.**

No autonomous agent, AI copilot, or human developer may modify production code without first documenting the intent, boundaries, invariants, and verification criteria in a structured specification.

---

## 2. The Spec-Driven Development Lifecycle

The engineering workflow follows seven discrete, sequential phases:

```
+-----------------------------------------------------------------------------------+
|                           SMART RALPH WORKFLOW PHASES                             |
+-----------------------------------------------------------------------------------+
|                                                                                   |
|  [Phase 1: Triage]                                                                |
|       Evaluate request scope; verify if a feature, bug fix, or refactor.          |
|       Determine affected components (Specs 001–009).                             |
|                                                                                   |
|  [Phase 2: Research]                                                              |
|       Analyze live 2.11.0 codebase; trace data paths and method contracts.        |
|       Identify concurrency hazards, locks, and ordering constraints.              |
|                                                                                   |
|  [Phase 3: Requirements Specification]                                            |
|       Author `specs/<change-slug>/requirements.md`.                               |
|       Define user stories, inputs, outputs, and edge conditions.                  |
|                                                                                   |
|  [Phase 4: Architectural Design & Invariant Alignment]                            |
|       Author `specs/<change-slug>/design.md`.                                     |
|       Map state transitions; check against System Constitution (I1–I10).          |
|       Define Lean 4 formal model extensions if replication logic changes.         |
|                                                                                   |
|  [Phase 5: Task Breakdown]                                                        |
|       Author `specs/<change-slug>/tasks.md`.                                      |
|       Break down work into atomic, independently verifiable tasks.                |
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

## 3. Specification Artifact Structure

For new features, bug fixes, or non-trivial modifications, a dedicated subfolder under `specs/` must be created containing the standard Smart Ralph artifacts:

```
specs/<feature-or-fix-slug>/
├── requirements.md     # Acceptance criteria, user story, problem statement
├── design.md           # Architecture, data structures, state machine, Lean model alignment
├── tasks.md            # Numbered, atomic implementation tasks
└── .progress.md        # State tracker and execution log
```

### 3.1 `requirements.md` Standard
Must contain:
- **Problem Statement**: What bug, failure mode, or gap is being addressed?
- **Root Cause Analysis**: Why does the current 2.11.0 codebase behave this way?
- **Acceptance Criteria**: Concrete, measurable conditions that define success.
- **Invariants Preserved**: Explicit confirmation of which System Constitution invariants (I1–I10) apply.

### 3.2 `design.md` Standard
Must contain:
- **Component Mapping**: Which classes (`DebeziumChangeEventCapture`, `ClickHouseBatchRunnable`, etc.) are modified?
- **Data Flow & State Transitions**: Sequence diagram or step-by-step state transition mapping.
- **Concurrency & Locking**: Explicit definition of locks held (`OFFSET_COMMIT_LOCK`, executor `gate`, etc.).
- **Lean Formal Invariant Impact**: Does this change affect binlog position ordering, version monotonicity, or convergence? If so, document the theorem update in `formal_specs/lean/`.

### 3.3 `tasks.md` Standard
Must contain:
- Numbered list of incremental, test-backed tasks.
- Each task must define: (a) file to modify, (b) specific change, (c) test asserting the change.
- Mandatory checkpoint tasks:
  - `[ ] Task N: Unit / Regression Test`
  - `[ ] Task N+1: Spec Validator Check (python3 scripts/validate_specs.py)`
  - `[ ] Task N+2: Formal Verification Proof Check`

---

## 4. Agent Execution Rules (Non-Negotiable)

1. **Spec First, Code Second**:
   An agent must never emit file edits to `.java` or configuration files before the corresponding specification files exist and are populated.
2. **Strict Fidelity (Zero Scope Creep)**:
   An agent must implement *only* what was declared in `design.md` and `tasks.md`. It is strictly forbidden to:
   - Add speculative utility functions or generic wrappers.
   - Refactor adjacent methods or files not mentioned in the spec.
   - Introduce unrequested configuration options or dependencies.
3. **The Prime Directive Overrides All**:
   No specification or implementation shall violate the Prime Directive: MySQL is the source of truth, ClickHouse must conform, and MySQL values always win over ClickHouse defaults.
4. **Clean Commits & Traceability**:
   Every pull request and commit message must cite the corresponding spec document (e.g. `spec(versioning): enforce commit monotonicity per specs/002-monotonic-versioning.md`).
5. **Spec Validation**:
   Before submitting any PR, `python3 scripts/validate_specs.py` must pass with exit code 0.

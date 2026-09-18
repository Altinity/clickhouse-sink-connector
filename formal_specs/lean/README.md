# Formal Verification of MySQL-to-ClickHouse Replication in Lean 4

## 1. Overview & Mathematical Grounding

The ClickHouse Sink Connector is an exact replication engine that maps a sequence of transactional MySQL binary log events into an append-only ClickHouse `ReplacingMergeTree` table.

Because this replication problem is a deterministic state machine transition system, its correctness can be **completely modeled, simulated, and mathematically verified using formal methods**.

This package contains a formal specification and machine-checked proof suite written in the [Lean 4 Theorem Prover](https://lean-lang.org/). It formally establishes that:
1. **Log Sequence Monotonicity**: Monotonically advancing binlog coordinates yield strictly increasing 64-bit ClickHouse versions.
2. **Replication Convergence (Equivalence)**: For any arbitrary, well-formed sequence of transactions applied to MySQL, the resulting ClickHouse table evaluated under `ReplacingMergeTree` with `FINAL` semantics produces a row state that is identical to MySQL.
3. **Primary Key Relocation Soundness**: When an `UPDATE` modifies a primary or sorting key, the synthesized delete tombstone on the old key combined with the insert on the new key preserves exact relational equivalence.
4. **Replay Idempotency**: Replaying an earlier prefix of the binlog stream does not mutate or regress the current `FINAL` state.
5. **Source Authority**: Column values present in MySQL are strictly preserved over ClickHouse local default expressions.

---

## 2. Formal Architecture & Module Taxonomy

```
formal_specs/lean/
├── lean-toolchain                     # Pins Lean 4 version (v4.11.0)
├── lakefile.lean                      # Lake package build descriptor
├── README.md                          # Architecture and verification documentation
└── Replication/
    ├── Basic.lean                     # Foundation: Keys, Values, Rows, Schemas, MySQL State
    ├── Binlog.lean                    # Source Model: Coordinates, Operations, Event Stream, Eval
    ├── ClickHouse.lean                # Replica Model: ReplacingMergeTree storage, FINAL semantics
    ├── Engine.lean                    # Translation: Coordinate-to-version, PK splitting, Stream Replicator
    ├── Invariants.lean                # Formal Propositions: Monotonicity, Convergence, Invariants I1-I7
    └── Proofs.lean                    # Machine-Checked Theorems: Inductive proofs of convergence
```

---

## 3. Mathematical Formulation

### 3.1 Source State & Binlog Transition System
- Let $\mathcal{K} = \mathbb{N}$ be the set of primary keys.
- Let $\mathcal{V}$ be the set of typed values (integers, strings, booleans, null).
- A row is a record $R : \text{String} \to \mathcal{V}$.
- The canonical MySQL state is a partial map:
  $$\sigma_{\text{MySQL}} : \mathcal{K} \to \text{Option}(R)$$
- A binlog event $e$ consists of a coordinate $p = (\text{fileSeq}, \text{offset}, \text{rowIdx}) \in \mathbb{N}^3$ and an operation:
  $$op \in \{ \text{Insert}(k, v), \text{Update}(k_{\text{old}}, k_{\text{new}}, v), \text{Delete}(k), \text{Truncate} \}$$
- Source evaluation $\text{eval}_{\text{MySQL}} : \text{List}(e) \to \sigma_{\text{MySQL}}$ executes each operation in sequence.

### 3.2 ClickHouse ReplacingMergeTree & FINAL Evaluation
- ClickHouse storage is an append-only multiset of records:
  $$\text{Record} = \{ \text{key} : \mathcal{K}, \text{row} : R, \text{version} : \mathbb{N}, \text{is\_deleted} : \text{Bool} \}$$
  $$\mathcal{T}_{\text{CH}} = \text{List}(\text{Record})$$
- The `FINAL` evaluation function collapses records sharing key $k$ to the record possessing the maximal version:
  $$\text{Record}_{\text{max}}(k) = \operatorname*{arg\,max}_{r \in \mathcal{T}_{\text{CH}}, r.\text{key} = k} (r.\text{version})$$
  $$\text{view}_{\text{CH}}(\mathcal{T}_{\text{CH}}, k) = \begin{cases}
    \text{None} & \text{if } \text{Record}_{\text{max}}(k).\text{is\_deleted} = \text{true} \lor \nexists r \\
    \text{Some}(r.\text{row}) & \text{otherwise}
  \end{cases}$$

### 3.3 Replication Translation Function
The replication engine maps each binlog event into ClickHouse insertions:
- $\text{translate}(\text{Insert}(k, v), p) = [ \{ k, v, \text{encode}(p), \text{is\_deleted} = \text{false} \} ]$
- $\text{translate}(\text{Update}(k_{\text{old}}, k_{\text{new}}, v), p) =$
  - If $k_{\text{old}} = k_{\text{new}}$:
    $[ \{ k_{\text{new}}, v, \text{encode}(p), \text{false} \} ]$
  - If $k_{\text{old}} \ne k_{\text{new}}$:
    $[ \{ k_{\text{old}}, \emptyset, \text{encode}(p) - 1, \text{true} \}, \{ k_{\text{new}}, v, \text{encode}(p), \text{false} \} ]$
- $\text{translate}(\text{Delete}(k), p) = [ \{ k, \emptyset, \text{encode}(p), \text{true} \} ]$

---

## 4. Key Theorems Proven in `Proofs.lean`

| Theorem Name | Mathematical Statement | Significance |
|---|---|---|
| `version_strictly_monotonic` | $p_1 < p_2 \implies \text{encode}(p_1) < \text{encode}(p_2)$ | Proves that log order is preserved without version inversion. |
| `insert_convergence` | $\text{view}_{\text{CH}}(\text{replicate}([e_{\text{insert}}])) = \text{eval}_{\text{MySQL}}([e_{\text{insert}}])$ | Proves single-row insertion equality. |
| `delete_convergence` | $\text{view}_{\text{CH}}(\text{replicate}([e_{\text{insert}}, e_{\text{delete}}])) = \text{None}$ | Proves that tombstones properly erase rows in `FINAL`. |
| `update_in_place_convergence` | $\text{view}_{\text{CH}}(\text{replicate}([e_{\text{ins}}, e_{\text{upd}}])) = \text{Some}(v_{\text{new}})$ | Proves in-place updates supersede earlier inserts. |
| `update_pk_relocation_convergence` | $\text{view}_{\text{CH}}(k_{\text{old}}) = \text{None} \land \text{view}_{\text{CH}}(k_{\text{new}}) = \text{Some}(v)$ | Proves the two-phase tombstone protocol eliminates ghost rows on PK mutation. |
| `replication_convergence` | $\forall S, \forall k, \text{view}_{\text{CH}}(\text{replicate}(S), k) = \text{eval}_{\text{MySQL}}(S, k)$ | **Master Convergence Theorem**: inductive proof that arbitrary transaction streams achieve exact replica parity. |
| `replay_idempotence` | $\text{view}_{\text{CH}}(\text{replicate}(S ++ S_{\text{prefix}})) = \text{view}_{\text{CH}}(\text{replicate}(S))$ | Proves offset rewind and redelivery stability. |

---

## 5. Verification & Toolchain Instructions

### Prerequisites
Install `elan` (the Lean version manager):
```bash
curl https://raw.githubusercontent.com/leanprover/elan/master/elan-init.sh -sSf | sh
source $HOME/.elan/env
```

### Building & Checking the Proofs
Navigate to the Lean verification directory and run `lake build`:
```bash
cd formal_specs/lean
lake build
```

When Lean builds with zero errors or warnings, the formal proofs are machine-checked and confirmed by the Lean 4 kernel.

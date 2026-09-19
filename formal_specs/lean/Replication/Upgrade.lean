/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

import Replication.Basic
import Replication.Binlog
import Replication.ClickHouse
import Replication.Engine
import Replication.Invariants
import Replication.Proofs

/-!
# Drop-in upgrade safety (Invariant I11)

Upgrading the connector (e.g. 2.8.0 / 2.9.1 / 2.10.x -> 2.11.0) must NOT ruin the
data already in ClickHouse. Concretely: rows written by the OLD version and rows
written by the NEW version coexist in one ReplacingMergeTree table, and the
`FINAL` view must still equal the source database.

This holds for one reason only: both the old and the new engine assign `_version`
so that a later source event always gets a strictly greater version, and the new
engine continues that ordering ABOVE the last version the old engine wrote (across
the upgrade the source commit clock only advances). We model an arbitrary version
scheme `v : Nat -> Nat` (the live version at stream ordinal `i`; the relocation
tombstone is `v i - 1`) and require only that it is "gap-monotone": strictly
increasing with a gap of at least two per ordinal, so the tombstone slot of
ordinal `i` still exceeds every version issued before it.

`replicate_convergesV` proves convergence for ANY gap-monotone scheme, so the
absolute version numbers are irrelevant — only their order matters. `upgrade_safe`
is the corollary that matters operationally: a stream whose first `n` ordinals are
versioned by the OLD scheme and the rest by the NEW scheme converges, provided the
combined scheme stays gap-monotone (the new engine's versions continue above the
old). That is exactly the drop-in-replacement guarantee.
-/

namespace Replication

/-- A version scheme: `v i` is the live version assigned at 1-based stream ordinal
    `i`; the relocation tombstone at ordinal `i` is `v i - 1`. -/
def GapMono (v : Nat → Nat) : Prop :=
  2 ≤ v 1 ∧ ∀ i, 1 ≤ i → v i + 2 ≤ v (i + 1)

/-- Every ordinal's version is at least 2 (so `v i - 1 ≥ 1`), by induction. -/
theorem GapMono.two_le {v : Nat → Nat} (hv : GapMono v) :
    ∀ i, 1 ≤ i → 2 ≤ v i := by
  obtain ⟨h1, hgap⟩ := hv
  intro i hi
  induction i with
  | zero => exact absurd hi (by omega)
  | succ n ih =>
      rcases Nat.lt_or_ge n 1 with hn | hn
      · have : n + 1 = 1 := by omega
        rw [this]; exact h1
      · have := hgap n hn
        have := ih hn
        omega

/-- Version-scheme-parameterised translation (mirrors `translateEventAt`, with the
    live version `v i` and tombstone `v i - 1`). -/
def translateEventAtV (v : Nat → Nat) (i : Nat) (e : BinlogEvent) : List CHRecord :=
  match e.op with
  | BinlogOp.insert k row =>
      [{ key := k, row := row, version := v i, is_deleted := false }]
  | BinlogOp.update k_old k_new row =>
      if k_old == k_new then
        [{ key := k_new, row := row, version := v i, is_deleted := false }]
      else
        [ { key := k_old, row := [],  version := v i - 1, is_deleted := true },
          { key := k_new, row := row, version := v i,     is_deleted := false } ]
  | BinlogOp.delete k =>
      [{ key := k, row := [], version := v i, is_deleted := true }]
  -- DESTRUCTIVE: formal-model arm — the clear event maps to the empty record
  -- list; no data is destroyed (a Lean term, not SQL).
  | BinlogOp.truncate =>
      []

/-- Version-scheme-parameterised replication engine (mirrors `replicateFrom`). -/
def replicateFromV (v : Nat → Nat) (i : Nat) (acc : CHTable) : List BinlogEvent → CHTable
  | [] => acc
  | e :: rest =>
      match e.op with
      -- DESTRUCTIVE: formal-model arm — resets the accumulator list; no table op.
      | BinlogOp.truncate => replicateFromV v (i + 1) [] rest
      | _                 => replicateFromV v (i + 1) (acc ++ translateEventAtV v i e) rest

/-- Replicate a whole stream under version scheme `v`, ordinals starting at 1. -/
def replicateStreamV (v : Nat → Nat) (events : List BinlogEvent) : CHTable :=
  replicateFromV v 1 emptyCH events

/--
General convergence for ANY gap-monotone version scheme. Identical shape to
`replicate_converges_gen`, with the concrete `2*i` replaced by `v i` and the
version bound `< v i - 1`; the gap-monotone hypothesis supplies the arithmetic.
-/
theorem replicate_converges_genV (v : Nat → Nat) (hv : GapMono v) :
    ∀ (events : List BinlogEvent) (i : Nat) (acc : CHTable) (st : MySQLState),
      0 < i →
      (∀ r ∈ acc, r.version < v i - 1) →
      (∀ k, chFinalView acc k = st k) →
      ∀ k, chFinalView (replicateFromV v i acc events) k
             = (events.foldl applyBinlogEvent st) k := by
  obtain ⟨hstart, hgap⟩ := hv
  have htwo : ∀ i, 1 ≤ i → 2 ≤ v i := GapMono.two_le ⟨hstart, hgap⟩
  intro events
  induction events with
  | nil =>
      intro i acc st _ _ hview k
      simpa [replicateFromV] using hview k
  | cons e rest ih =>
      intro i acc st hi hbound hview k
      have hgi : v i + 2 ≤ v (i + 1) := hgap i (by omega)
      have hti : 2 ≤ v i := htwo i (by omega)
      cases hop : e.op with
      -- DESTRUCTIVE: formal-model proof case for the `truncate` constructor —
      -- a Lean proof about list/state semantics; nothing is executed or dropped.
      | truncate =>
          have hstep : replicateFromV v i acc (e :: rest)
              = replicateFromV v (i + 1) emptyCH rest := by
            simp [replicateFromV, hop, emptyCH]
          have hmysql : applyBinlogEvent st e = emptyMySQL := by
            funext x; simp [applyBinlogEvent, hop, emptyMySQL]
          rw [hstep]
          have hb : ∀ r ∈ (emptyCH : CHTable), r.version < v (i + 1) - 1 := by
            intro r hr; simp [emptyCH] at hr
          have hvw : ∀ q, chFinalView (emptyCH : CHTable) q = emptyMySQL q := by
            intro q; simp [chFinalView, emptyCH, filterKey, findMaxVersion, emptyMySQL]
          have := ih (i + 1) emptyCH emptyMySQL (by omega) hb hvw k
          rw [this]; simp [List.foldl, hmysql]
      | insert k0 val =>
          have hstep : replicateFromV v i acc (e :: rest)
              = replicateFromV v (i + 1) (acc ++ translateEventAtV v i e) rest := by
            simp [replicateFromV, hop]
          rw [hstep]
          have htr : translateEventAtV v i e
              = [{ key := k0, row := val, version := v i, is_deleted := false }] := by
            simp [translateEventAtV, hop]
          have hview' : ∀ q, chFinalView (acc ++ translateEventAtV v i e) q = applyBinlogEvent st e q := by
            intro q
            rw [htr]
            have hdom : ∀ r ∈ filterKey acc q, r.version < v i := by
              intro r hr
              have := hbound r (mem_of_mem_filterKey hr); omega
            have := chFinalView_snoc acc { key := k0, row := val, version := v i, is_deleted := false } q hdom
            rw [this]
            by_cases hq : k0 = q
            · subst hq; simp [applyBinlogEvent, hop, beq_self_eq_true]
            · rw [if_neg hq, hview q]
              simp [applyBinlogEvent, hop, beq_false_of_ne (fun h => hq h.symm)]
          have hbound' : ∀ r ∈ (acc ++ translateEventAtV v i e), r.version < v (i + 1) - 1 := by
            intro r hr
            rw [List.mem_append] at hr
            rcases hr with h1 | h2
            · have := hbound r h1; omega
            · rw [htr] at h2; simp at h2; subst h2; show v i < v (i + 1) - 1; omega
          have := ih (i + 1) (acc ++ translateEventAtV v i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
          rw [this]; simp [List.foldl]
      | delete k0 =>
          have hstep : replicateFromV v i acc (e :: rest)
              = replicateFromV v (i + 1) (acc ++ translateEventAtV v i e) rest := by
            simp [replicateFromV, hop]
          rw [hstep]
          have htr : translateEventAtV v i e
              = [{ key := k0, row := [], version := v i, is_deleted := true }] := by
            simp [translateEventAtV, hop]
          have hview' : ∀ q, chFinalView (acc ++ translateEventAtV v i e) q = applyBinlogEvent st e q := by
            intro q
            rw [htr]
            have hdom : ∀ r ∈ filterKey acc q, r.version < v i := by
              intro r hr
              have := hbound r (mem_of_mem_filterKey hr); omega
            have := chFinalView_snoc acc { key := k0, row := [], version := v i, is_deleted := true } q hdom
            rw [this]
            by_cases hq : k0 = q
            · subst hq; simp [applyBinlogEvent, hop, beq_self_eq_true]
            · rw [if_neg hq, hview q]
              simp [applyBinlogEvent, hop, beq_false_of_ne (fun h => hq h.symm)]
          have hbound' : ∀ r ∈ (acc ++ translateEventAtV v i e), r.version < v (i + 1) - 1 := by
            intro r hr
            rw [List.mem_append] at hr
            rcases hr with h1 | h2
            · have := hbound r h1; omega
            · rw [htr] at h2; simp at h2; subst h2; show v i < v (i + 1) - 1; omega
          have := ih (i + 1) (acc ++ translateEventAtV v i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
          rw [this]; simp [List.foldl]
      | update k_old k_new val =>
          have hstep : replicateFromV v i acc (e :: rest)
              = replicateFromV v (i + 1) (acc ++ translateEventAtV v i e) rest := by
            simp [replicateFromV, hop]
          rw [hstep]
          by_cases hkk : k_old = k_new
          · have htr : translateEventAtV v i e
                = [{ key := k_new, row := val, version := v i, is_deleted := false }] := by
              simp [translateEventAtV, hop, hkk]
            have hview' : ∀ q, chFinalView (acc ++ translateEventAtV v i e) q = applyBinlogEvent st e q := by
              intro q
              rw [htr]
              have hdom : ∀ r ∈ filterKey acc q, r.version < v i := by
                intro r hr
                have := hbound r (mem_of_mem_filterKey hr); omega
              have := chFinalView_snoc acc { key := k_new, row := val, version := v i, is_deleted := false } q hdom
              rw [this]
              by_cases hq : k_new = q
              · subst hq; simp [applyBinlogEvent, hop, hkk, beq_self_eq_true]
              · rw [if_neg hq, hview q]
                simp [applyBinlogEvent, hop, hkk, beq_false_of_ne (fun h => hq h.symm)]
            have hbound' : ∀ r ∈ (acc ++ translateEventAtV v i e), r.version < v (i + 1) - 1 := by
              intro r hr
              rw [List.mem_append] at hr
              rcases hr with h1 | h2
              · have := hbound r h1; omega
              · rw [htr] at h2; simp at h2; subst h2; show v i < v (i + 1) - 1; omega
            have := ih (i + 1) (acc ++ translateEventAtV v i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
            rw [this]; simp [List.foldl]
          · let tomb : CHRecord := { key := k_old, row := [], version := v i - 1, is_deleted := true }
            let live : CHRecord := { key := k_new, row := val, version := v i, is_deleted := false }
            have htomb : tomb = { key := k_old, row := [], version := v i - 1, is_deleted := true } := rfl
            have hlive : live = { key := k_new, row := val, version := v i, is_deleted := false } := rfl
            have htr : translateEventAtV v i e = [tomb, live] := by
              simp [translateEventAtV, hop, beq_false_of_ne hkk, htomb, hlive]
            have hassoc : acc ++ translateEventAtV v i e = (acc ++ [tomb]) ++ [live] := by
              rw [htr]; simp [List.append_assoc]
            have hview' : ∀ q, chFinalView (acc ++ translateEventAtV v i e) q = applyBinlogEvent st e q := by
              intro q
              rw [hassoc]
              have hdom_live : ∀ r ∈ filterKey (acc ++ [tomb]) q, r.version < live.version := by
                intro r hr
                rw [filterKey_append, List.mem_append] at hr
                rcases hr with h1 | h2
                · have := hbound r (mem_of_mem_filterKey h1)
                  simp [hlive]; omega
                · have : r = tomb := by
                    have := mem_of_mem_filterKey h2; simpa using this
                  subst this; simp [htomb, hlive]; omega
              rw [chFinalView_snoc (acc ++ [tomb]) live q hdom_live]
              have hdom_tomb : ∀ r ∈ filterKey acc q, r.version < tomb.version := by
                intro r hr
                have := hbound r (mem_of_mem_filterKey hr)
                simp [htomb]; omega
              rw [chFinalView_snoc acc tomb q hdom_tomb]
              simp only [htomb, hlive]
              by_cases hqo : k_old = q
              · subst hqo
                simp [applyBinlogEvent, hop, beq_false_of_ne hkk, beq_self_eq_true, Ne.symm hkk]
              · by_cases hqn : k_new = q
                · subst hqn
                  simp [applyBinlogEvent, hop, beq_false_of_ne hkk, beq_self_eq_true,
                        beq_false_of_ne (fun h => hqo h.symm)]
                · rw [if_neg hqn, if_neg hqo, hview q]
                  simp [applyBinlogEvent, hop, beq_false_of_ne hkk,
                        beq_false_of_ne (fun h => hqo h.symm),
                        beq_false_of_ne (fun h => hqn h.symm)]
            have hbound' : ∀ r ∈ (acc ++ translateEventAtV v i e), r.version < v (i + 1) - 1 := by
              intro r hr
              rw [htr, List.mem_append] at hr
              rcases hr with h1 | h2
              · have := hbound r h1; omega
              · simp [htomb, hlive] at h2
                rcases h2 with h2a | h2b
                · subst h2a; show v i - 1 < v (i + 1) - 1; omega
                · subst h2b; show v i < v (i + 1) - 1; omega
            have := ih (i + 1) (acc ++ translateEventAtV v i e) (applyBinlogEvent st e) (by omega) hbound' hview' k
            rw [this]; simp [List.foldl]

/-- Master convergence for ANY gap-monotone version scheme. The absolute version
    numbers are irrelevant; only their order matters. -/
theorem replicate_convergesV (v : Nat → Nat) (hv : GapMono v) (events : List BinlogEvent) :
    ∀ k, chFinalView (replicateStreamV v events) k = evalMySQL events emptyMySQL k := by
  intro k
  have htwo := GapMono.two_le hv
  have hb : ∀ r ∈ (emptyCH : CHTable), r.version < v 1 - 1 := by
    intro r hr; simp [emptyCH] at hr
  have hvw : ∀ q, chFinalView (emptyCH : CHTable) q = emptyMySQL q := by
    intro q; simp [chFinalView, emptyCH, filterKey, findMaxVersion, emptyMySQL]
  have := replicate_converges_genV v hv events 1 emptyCH emptyMySQL (by omega) hb hvw k
  simpa [replicateStreamV, evalMySQL] using this

/--
The combined version scheme of an upgrade: ordinals `≤ n` (events written by the
OLD connector) use `vOld`; later ordinals (events written by the NEW connector)
use `vNew`.
-/
def upgradeScheme (vOld vNew : Nat → Nat) (n : Nat) : Nat → Nat :=
  fun i => if i ≤ n then vOld i else vNew i

/--
Invariant I11 — Drop-in upgrade safety.

Replicating a stream where the first `n` ordinals are versioned by the OLD scheme
and the rest by the NEW scheme converges to the source: the ClickHouse `FINAL`
view equals MySQL. The single hypothesis is that the combined scheme stays
gap-monotone — i.e. the new connector's versions continue strictly above the last
version the old connector wrote. Under that condition, upgrading NEVER ruins the
data already in ClickHouse: pre-upgrade rows and post-upgrade rows coexist and the
correct (latest) row still wins.
-/
theorem upgrade_safe (vOld vNew : Nat → Nat) (n : Nat)
    (hv : GapMono (upgradeScheme vOld vNew n)) (events : List BinlogEvent) :
    ∀ k, chFinalView (replicateStreamV (upgradeScheme vOld vNew n) events) k
           = evalMySQL events emptyMySQL k :=
  replicate_convergesV (upgradeScheme vOld vNew n) hv events

/-- The connector's own ordinal scheme (`liveVersion i = 2*i`) is gap-monotone,
    so the general result specialises to the shipped engine. -/
theorem liveVersion_gapMono : GapMono (fun i => 2 * i) := by
  constructor
  · show 2 ≤ 2 * 1; omega
  · intro i _; show 2 * i + 2 ≤ 2 * (i + 1); omega

end Replication

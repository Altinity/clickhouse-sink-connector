/-
Copyright (c) 2026 Altinity Inc. All rights reserved.
Released under Apache 2.0 license as described in the file LICENSE.
Authors: ClickHouse Sink Connector Maintainers
-/

/-!
# Version floor across a restart (Invariant I2 at the restart boundary, specs 02.02 / 02.04)

The lightweight connector versions every row with the shipped 2.8.0 formula

    version = effectiveTs * 1_000_000 + counter

where `effectiveTs` is the row's source timestamp clamped up to a process-wide
floor (`sequenceMaxSourceTs`) for every FIRST delivery, and `counter` is the
intra-window sequence counter (`sequenceNumber`, seeded at 500m after a start and
at 1000m after every reset). This module models that state machine exactly as
`DebeziumChangeEventCapture.nextSequenceNumber` implements it and proves the two
properties the restart fix relies on:

* **Restart boundary.** Before the fix the floor, the anchor and the high-water
  position were process-local statics: after a restart the floor was `0`, so the
  first genuinely newer rows were versioned in their own (lagging) source second
  and ranked BELOW rows the previous run had clamped to a later second. With the
  floor seeded from the persisted high-water version `v` as `v / 1_000_000 + 1`
  (`seed`), every first delivery of the new run receives a version strictly
  greater than `v`, hence strictly greater than every version the previous run
  assigned (`restart_boundary`). No counter bound and no timing assumption is
  needed: the floor never decreases within a run (`floor_mono`) and a first
  delivery's version is at least `floor * M + 1` (`version_ge_floor`).
* **Control records leave the sequence alone.** Heartbeats and transaction
  metadata carry the connector's wall clock, not a source commit time, and
  produce no row. The fixed dispatch loop never runs them through the sequence
  (`dispatch_control_preserves_state`); the pre-fix loop did, which pinned the
  floor to the connector clock (`old_dispatch_control_moves_floor`).

The concrete scenario of `DebeziumChangeEventCaptureTest
.newerEventAfterSeededRestartRanksAboveOlderPreRestartEvent` is checked as an
executable example (`seeded_restart_example`).
-/

set_option autoImplicit false

namespace Replication
namespace VersionFloor

/-- The multiplier of the shipped formula (`1_000_000L`). -/
def M : Nat := 1000000
/-- `SEQUENCE_START`: the counter seed after every reset. -/
def seqStart : Nat := 1000000000
/-- `SEQUENCE_START_INITIAL`: the counter seed for the first record after a start. -/
def seqStartInitial : Nat := 500000000

/-- The four statics of `nextSequenceNumber`. Log positions are abstracted to
    naturals ordered like `SourcePosition.compareTo`; `anchor = 0` means unset. -/
structure SeqState where
  floor   : Nat        -- sequenceMaxSourceTs
  anchor  : Nat        -- sequenceAnchorTs
  counter : Nat        -- sequenceNumber
  mark    : Option Nat -- sequenceHighWaterPosition
deriving Repr, DecidableEq

/-- The state of a freshly started JVM. -/
def initial : SeqState := ⟨0, 0, seqStart, none⟩

/-- A record that reaches the sequence: its source timestamp (ms) and its log
    position, `none` for a row without coordinates. -/
structure Rec where
  ts  : Nat
  pos : Option Nat
deriving Repr, DecidableEq

/-- A positioned record above the high-water mark (or any positioned record when
    the mark is unset) is a first delivery. -/
def isFirst (s : SeqState) (r : Rec) : Bool :=
  match r.pos with
  | none => false
  | some p =>
    match s.mark with
    | none => true
    | some m => decide (m < p)

/-- The clamped timestamp: only a first delivery is floored. -/
def effTs (s : SeqState) (r : Rec) : Nat :=
  if isFirst s r then max r.ts s.floor else r.ts

/-- The anchor in force for this record (the first record after a start anchors on
    its own timestamp). -/
def anchor0 (s : SeqState) (r : Rec) : Nat :=
  if s.anchor = 0 then r.ts else s.anchor

/-- The counter in force before this record (re-seeded at 500m after a start). -/
def counter0 (s : SeqState) : Nat :=
  if s.anchor = 0 then seqStartInitial else s.counter

/-- `diff > 1` in the Java code (`(int)((effectiveTs - anchor) / 1000)`); a negative
    difference never resets, which truncated subtraction reproduces. -/
def resets (s : SeqState) (r : Rec) : Bool :=
  decide ((effTs s r - anchor0 s r) / 1000 > 1)

def nextCounter (s : SeqState) (r : Rec) : Nat :=
  if resets s r then seqStart else counter0 s + 1

/-- The `_version` returned by `nextSequenceNumber`. -/
def version (s : SeqState) (r : Rec) : Nat :=
  effTs s r * M + nextCounter s r

/-- The statics after `nextSequenceNumber`. -/
def step (s : SeqState) (r : Rec) : SeqState :=
  { floor   := max s.floor (effTs s r),
    anchor  := if resets s r then effTs s r else anchor0 s r,
    counter := nextCounter s r,
    mark    := if isFirst s r then r.pos else s.mark }

/-- `DebeziumChangeEventCapture.seedVersionFloor(v)`: raise the floor to
    `v / 1_000_000 + 1` (never lower it). -/
def seed (s : SeqState) (v : Nat) : SeqState :=
  { s with floor := max s.floor (v / M + 1) }

/-- Versions handed out to the FIRST deliveries of a run, in order. -/
def firstDeliveryVersions (s : SeqState) : List Rec → List Nat
  | [] => []
  | r :: rs =>
    if isFirst s r then version s r :: firstDeliveryVersions (step s r) rs
    else firstDeliveryVersions (step s r) rs

/-! ## Single-step facts -/

theorem effTs_ge_floor_of_first (s : SeqState) (r : Rec) (h : isFirst s r = true) :
    s.floor ≤ effTs s r := by
  simp only [effTs, h, if_true]
  exact Nat.le_max_right _ _

theorem nextCounter_pos (s : SeqState) (r : Rec) : 1 ≤ nextCounter s r := by
  unfold nextCounter
  split <;> simp [seqStart]

/-- A first delivery is versioned at least `floor * M + 1`. -/
theorem version_ge_floor (s : SeqState) (r : Rec) (h : isFirst s r = true) :
    s.floor * M + 1 ≤ version s r := by
  have h1 := effTs_ge_floor_of_first s r h
  have h2 := nextCounter_pos s r
  unfold version M
  have h3 : s.floor * 1000000 ≤ effTs s r * 1000000 := Nat.mul_le_mul_right _ h1
  unfold M at h3
  omega

/-- The floor never decreases within a run. -/
theorem floor_mono (s : SeqState) (r : Rec) : s.floor ≤ (step s r).floor := by
  show s.floor ≤ max s.floor (effTs s r)
  exact Nat.le_max_left _ _

/-- Seeding never lowers the floor. -/
theorem seed_floor_ge (s : SeqState) (v : Nat) : s.floor ≤ (seed s v).floor := by
  show s.floor ≤ max s.floor (v / M + 1)
  exact Nat.le_max_left _ _

/-- The seeded floor's whole-second slot lies strictly above the high-water version:
    `v < (v / M + 1) * M`. -/
theorem seed_floor_gt (s : SeqState) (v : Nat) : v < (seed s v).floor * M := by
  have hle : v / M + 1 ≤ (seed s v).floor := by
    show v / M + 1 ≤ max s.floor (v / M + 1)
    exact Nat.le_max_right _ _
  have hmul : (v / M + 1) * M ≤ (seed s v).floor * M := Nat.mul_le_mul_right _ hle
  have hv : v < (v / M + 1) * M := by
    unfold M
    omega
  exact Nat.lt_of_lt_of_le hv hmul

/-! ## The restart boundary -/

/-- **Every first delivery of a run whose floor slot lies above `v` is versioned above
    `v`.** The hypothesis is exactly what `seed` establishes and `floor_mono` keeps. -/
theorem firstDeliveryVersions_gt (v : Nat) :
    ∀ (rs : List Rec) (s : SeqState), v < s.floor * M →
      ∀ w ∈ firstDeliveryVersions s rs, v < w := by
  intro rs
  induction rs with
  | nil => intro s _ w hw; simp [firstDeliveryVersions] at hw
  | cons r rest ih =>
    intro s hs w hw
    have hnext : v < (step s r).floor * M :=
      Nat.lt_of_lt_of_le hs (Nat.mul_le_mul_right _ (floor_mono s r))
    unfold firstDeliveryVersions at hw
    by_cases hf : isFirst s r = true
    · rw [if_pos hf] at hw
      rcases List.mem_cons.mp hw with hw | hw
      · subst hw
        have := version_ge_floor s r hf
        omega
      · exact ih (step s r) hnext w hw
    · rw [if_neg hf] at hw
      exact ih (step s r) hnext w hw

/--
**Restart boundary (Invariant I2 across a restart).** Let `pre` be the versions
the previous run assigned and `v` a high-water mark at or above all of them (the
persisted mark, or `max(_version)` over the targets). Seeding the fresh statics
from `v` makes every first delivery of the new run rank strictly above every
pre-restart version -- whatever the source timestamps, the lag, the clock skew
between MySQL and the connector, or the counter seeds.
-/
theorem restart_boundary (pre : List Nat) (v : Nat) (hpre : ∀ u ∈ pre, u ≤ v)
    (post : List Rec) :
    ∀ u ∈ pre, ∀ w ∈ firstDeliveryVersions (seed initial v) post, u < w := by
  intro u hu w hw
  have hv : v < (seed initial v).floor * M := seed_floor_gt initial v
  have := firstDeliveryVersions_gt v post (seed initial v) hv w hw
  have := hpre u hu
  omega

/-- After a restart the mark is unset, so a positioned record is a first delivery:
    the boundary theorem applies to the very first row of the new run. -/
theorem first_row_after_restart_is_first (v : Nat) (r : Rec) (p : Nat) (h : r.pos = some p) :
    isFirst (seed initial v) r = true := by
  simp [isFirst, seed, initial, h]

/-! ## Control records -/

/-- What the dispatch loop sees: a row (or DDL) record that reaches the sequence,
    or a control record (heartbeat / transaction metadata) carrying only the
    connector's wall-clock timestamp. -/
inductive Event where
  | row (r : Rec)
  | control (wallClockTs : Nat)
deriving Repr

/-- The fixed loop: control records produce no row, need no version, and are not
    run through the sequence. -/
def dispatch (s : SeqState) : Event → SeqState
  | Event.row r => step s r
  | Event.control _ => s

/-- The pre-fix loop: every record, control records included, was versioned as a
    positionless record (`nextSequenceNumber(envelopeTs, null)`). -/
def oldDispatch (s : SeqState) : Event → SeqState
  | Event.row r => step s r
  | Event.control ts => step s ⟨ts, none⟩

/-- **A heartbeat does not alter the sequence state.** -/
theorem dispatch_control_preserves_state (s : SeqState) (ts : Nat) :
    dispatch s (Event.control ts) = s := rfl

/-- The pre-fix behaviour, concretely: a source lagging 30 s behind the connector
    clock `W` has its floor pinned to `W` by one heartbeat -- every later row is then
    versioned in the connector's second, not the source's. -/
theorem old_dispatch_control_moves_floor :
    (oldDispatch (step initial ⟨1787635797000 - 40000, some 400⟩)
        (Event.control 1787635797000)).floor = 1787635797000 := by
  decide

/-- The same heartbeat under the fixed loop leaves the floor at the source clock. -/
theorem dispatch_control_keeps_source_floor :
    (dispatch (step initial ⟨1787635797000 - 40000, some 400⟩)
        (Event.control 1787635797000)).floor = 1787635797000 - 40000 := by
  decide

/-! ## The regression scenario, executed -/

/-- The scenario of the unit test: a run versions rows at `W-40000` and `W-30000`
    (the second one after a heartbeat at the connector clock `W`), the process
    restarts, the floor is seeded from the last assigned version `v1`, and a
    genuinely newer row at `W-25000` is versioned. Its version `v2` exceeds `v1`.
    Before the fix `v2 < v1` (the floor restarted at 0 and the newer row was
    versioned in its own lagging second). -/
theorem seeded_restart_example :
    let W : Nat := 1787635797000
    let s1 := step initial ⟨W - 40000, some 400⟩
    let s1' := dispatch s1 (Event.control W)
    let v1 := version s1' ⟨W - 30000, some 500⟩
    let s3 := seed initial v1
    let v2 := version s3 ⟨W - 25000, some 600⟩
    v1 < v2 := by
  decide

/-- The pre-fix arithmetic on the same scenario: with the floor back at 0 the newer
    post-restart row ranks BELOW the pre-restart row. -/
theorem unseeded_restart_inverts :
    let W : Nat := 1787635797000
    let s1 := step initial ⟨W - 40000, some 400⟩
    let s1' := oldDispatch s1 (Event.control W)
    let v1 := version s1' ⟨W - 30000, some 500⟩
    let v2 := version initial ⟨W - 25000, some 600⟩
    v2 < v1 := by
  decide

end VersionFloor
end Replication

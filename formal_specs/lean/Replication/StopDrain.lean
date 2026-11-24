/-
spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::DebeziumChangeEventCapture.stop
request: stop() shut the batch worker pool down and never looked at whether the pool terminated. A pool still
running after its bound had its worker connections closed, its offset FIFO reset and its active-engine
registration cleared while its workers still used all three.
property: P1 a pool that has not terminated keeps its connections, its FIFO units and its registration, and stop()
abandons nothing. P2 the connection close, the FIFO reset and the registration clear happen only when the pool
has terminated, which requires that no worker is busy. P3 stop() acknowledges nothing, and an acknowledged unit
was written.
model: the state is counts. `busy` is the number of workers inside a write, a retry or a backoff; `outstanding`
the number of handed-off units neither acknowledged nor retired. The await result is an input that may be true
only when no worker is busy. Bounds: none; the properties hold for every count. The pre-fix body is kept as a
passing witness (`old_stop_closes_live_connections`): it closes live connections while a worker is busy.
-/

namespace StopDrain

/-- The state the stop decision reads and writes. -/
structure St where
  poolRunning : Bool
  busy : Nat
  connectionsOpen : Bool
  outstanding : Nat
  written : Nat
  acked : Nat
  activeEngine : Bool
  deferredClose : Bool
deriving Repr, DecidableEq

/-- Well-formed: an acknowledgement never outruns a write, and a busy worker holds an outstanding unit. -/
def Valid (s : St) : Prop := s.acked ≤ s.written ∧ s.busy ≤ s.outstanding

/-- The stop decision after the pool await. `terminated` is the await result. -/
def stopPool (s : St) (terminated : Bool) : St :=
  if terminated then
    { s with poolRunning := false, connectionsOpen := false, outstanding := 0, activeEngine := false }
  else
    { s with deferredClose := true }

/-- The number of units this call abandons. -/
def abandonedBy (s : St) (terminated : Bool) : Nat :=
  if terminated then s.outstanding else 0

/-- The pre-fix body: the await result is ignored, so the teardown always runs. -/
def oldStop (s : St) : St :=
  { s with poolRunning := false, connectionsOpen := false, outstanding := 0, activeEngine := false }

/-- P1: a pool that has not terminated keeps its connections, its FIFO units and its registration. -/
theorem timeout_releases_nothing (s : St) :
    (stopPool s false).connectionsOpen = s.connectionsOpen ∧
    (stopPool s false).outstanding = s.outstanding ∧
    (stopPool s false).activeEngine = s.activeEngine ∧
    abandonedBy s false = 0 := by
  simp [stopPool, abandonedBy]

/-- A terminated pool is torn down in full: connections closed, FIFO reset, registration cleared. -/
theorem terminated_tears_down (s : St) :
    (stopPool s true).connectionsOpen = false ∧
    (stopPool s true).outstanding = 0 ∧
    (stopPool s true).activeEngine = false := by
  simp [stopPool]

/-- P2: whatever the await returns, a busy pool keeps everything. The teardown needs `busy = 0`
    because the await reports termination only once no worker is busy. -/
theorem busy_pool_keeps_everything (s : St) (t : Bool)
    (hok : t = true → s.busy = 0) (hb : s.busy ≠ 0) :
    (stopPool s t).connectionsOpen = s.connectionsOpen ∧
    (stopPool s t).outstanding = s.outstanding ∧
    (stopPool s t).activeEngine = s.activeEngine := by
  cases t with
  | false => simp [stopPool]
  | true => exact absurd (hok rfl) hb

/-- P3: stop() acknowledges nothing and writes nothing. -/
theorem stop_acknowledges_nothing (s : St) (t : Bool) :
    (stopPool s t).acked = s.acked ∧ (stopPool s t).written = s.written := by
  cases t <;> simp [stopPool]

/-- P3: the acknowledgement-after-write invariant survives stop(). -/
theorem stop_keeps_valid (s : St) (t : Bool) (hv : Valid s) (hok : t = true → s.busy = 0) :
    Valid (stopPool s t) := by
  unfold Valid at hv ⊢
  obtain ⟨h1, h2⟩ := hv
  cases t with
  | false => exact ⟨h1, h2⟩
  | true =>
    have hb : s.busy = 0 := hok rfl
    show s.acked ≤ s.written ∧ s.busy ≤ 0
    exact ⟨h1, by omega⟩

/-- A busy pool with a live connection, one outstanding unit and one active registration. -/
def busyWitness : St :=
  { poolRunning := true, busy := 1, connectionsOpen := true, outstanding := 1,
    written := 0, acked := 0, activeEngine := true, deferredClose := false }

/-- Witness for the pre-fix behaviour: the old body closes the connections of a busy pool. -/
theorem old_stop_closes_live_connections :
    (oldStop busyWitness).connectionsOpen = false ∧ busyWitness.busy ≠ 0 := by
  decide

/-- The fixed body keeps the same connections and unit for the same input. -/
theorem fixed_stop_keeps_live_connections :
    (stopPool busyWitness false).connectionsOpen = true ∧
    (stopPool busyWitness false).outstanding = 1 := by
  decide

end StopDrain

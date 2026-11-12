/-
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogRowImagePreflight.java::check
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/BinlogEventAudit.java::audit
  spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/cdc/DebeziumChangeEventCapture.java::handleEngineCompletion

  Property: a binlog source is replicated from only when every row image it logs is FULL and no
  session option hides a partial JSON update. An unreadable source is never accepted. An engine that
  has read a PARTIAL_UPDATE_ROWS_EVENT is stopped and is never recreated. An unreadable re-check at
  restart spends one retry, and a refusal is terminal.

  Model: pure functions over finite enumerations. The Java is not executed here. Bounds: row image
  and value options are the four and five cases listed below; the retry budget is any natural
  number; the opt-out is a Bool.
-/

namespace Replication.BinlogGuards

inductive RowImage where
  | full
  | minimal
  | noblob
  | unreadable
  deriving DecidableEq, Repr

inductive ValueOptions where
  | empty
  | other
  | partialJson
  | absent
  | unreadable
  deriving DecidableEq, Repr

structure Src where
  rowImage : RowImage
  valueOptions : ValueOptions
  deriving DecidableEq, Repr

inductive Decision where
  | pass
  | refuse
  | unreadable
  deriving DecidableEq, Repr

/-- The start check, and the re-check before every restart. Both variables are read before any
    decision (an unreadable value wins over a refusal). `skip` is the operator opt-out. Error 1193
    on binlog_row_value_options is not an unreadable value: it is modelled as `absent`, which is not
    PARTIAL_JSON. -/
def startDecision (src : Src) (skip : Bool) : Decision :=
  if skip then .pass
  else if src.rowImage == .unreadable || src.valueOptions == .unreadable then .unreadable
  else if src.rowImage != .full || src.valueOptions == .partialJson then .refuse
  else .pass

inductive Engine where
  | idle
  | running
  | completing
  | terminal
  deriving DecidableEq, Repr

inductive Stop where
  | clear
  | raised
  deriving DecidableEq, Repr

structure St where
  engine : Engine
  stop : Stop
  budget : Nat
  deriving Repr

inductive Event where
  | row
  | partialUpdate
  deriving DecidableEq, Repr

/-- The event observer: a PARTIAL_UPDATE_ROWS_EVENT raises the stop; ordinary row events do not. -/
def record (s : St) (e : Event) : St :=
  match e with
  | .row => s
  | .partialUpdate => { s with stop := .raised }

/-- The engine-completion step. A raised stop is terminal before any retry is looked at. With the
    stop clear and budget left, the re-check decides: pass restarts, refuse is terminal, unreadable
    spends the retry and completes again. An exhausted budget is terminal. -/
def handle (s : St) (d : Decision) : St :=
  match s.stop, s.budget, d with
  | .raised, _, _ => { s with engine := .terminal }
  | .clear, 0, _ => { s with engine := .terminal }
  | .clear, b + 1, .refuse => { s with engine := .terminal, budget := b }
  | .clear, b + 1, .pass => { s with engine := .running, budget := b }
  | .clear, b + 1, .unreadable => { s with engine := .completing, budget := b }

/-! ## The start and restart gate -/

/-- A source is accepted only when it is FULL, does not name PARTIAL_JSON, and both reads worked. -/
theorem pass_needs_full_and_no_partial_json (src : Src) :
    startDecision src false = .pass →
      src.rowImage = .full ∧ src.valueOptions ≠ .partialJson ∧ src.valueOptions ≠ .unreadable := by
  intro h
  cases src with
  | mk ri vo =>
    cases ri <;> cases vo <;> revert h <;> decide

/-- An unreadable row image or value option is never accepted. -/
theorem unreadable_never_passes (src : Src) :
    (src.rowImage = .unreadable ∨ src.valueOptions = .unreadable) →
      startDecision src false = .unreadable := by
  intro h
  cases src with
  | mk ri vo =>
    cases ri <;> cases vo <;> revert h <;> decide

/-- The opt-out accepts every source (the Java logs a WARN in that case). -/
theorem skip_accepts_every_source (src : Src) : startDecision src true = .pass := by
  cases src with
  | mk ri vo =>
    cases ri <;> cases vo <;> rfl

/-- PARTIAL_JSON in the session value options is refused for every row image. -/
theorem partial_json_is_refused (ri : RowImage) :
    startDecision ⟨ri, .partialJson⟩ false ≠ .pass := by
  cases ri <;> decide

/-- Error 1193 (variable absent, MySQL 5.7) is not an error: a FULL source with no value options
    variable is accepted. -/
theorem absent_value_options_is_not_an_error :
    startDecision ⟨.full, .absent⟩ false = .pass := rfl

/-- Any row image other than FULL is refused, for every readable value option. -/
theorem non_full_row_image_is_refused (vo : ValueOptions) :
    vo ≠ .unreadable → startDecision ⟨.minimal, vo⟩ false = .refuse := by
  intro h
  cases vo <;> revert h <;> decide

/-- Witness (documentation): the source switched to MINIMAL while the connector ran is refused by
    the re-check that every restart now runs. -/
theorem minimal_source_is_refused_at_restart :
    startDecision ⟨.minimal, .empty⟩ false = .refuse := rfl

/-! ## The engine-completion step -/

/-- A raised stop is terminal, whatever the budget and whatever the re-check says. -/
theorem stopped_engine_is_terminal (b : Nat) (d : Decision) :
    (handle ⟨.running, .raised, b⟩ d).engine = .terminal := by
  cases d <;> rfl

/-- A restart happens only when the re-check passes. -/
theorem restart_needs_pass (b : Nat) (d : Decision) :
    (handle ⟨.running, .clear, b + 1⟩ d).engine = .running → d = .pass := by
  cases d <;> simp [handle]

/-- An unreadable re-check spends exactly one retry and completes again. -/
theorem unreadable_recheck_spends_one_retry (b : Nat) :
    handle ⟨.running, .clear, b + 1⟩ .unreadable =
      { engine := .completing, stop := .clear, budget := b } := rfl

/-- With no retries left, a clear stop is terminal. -/
theorem exhausted_budget_is_terminal (d : Decision) :
    (handle ⟨.running, .clear, 0⟩ d).engine = .terminal := by
  cases d <;> rfl

/-! ## The event observer and the stop -/

/-- The first partial update raises the stop. -/
theorem partial_update_raises_the_stop (s : St) :
    (record s .partialUpdate).stop = .raised := rfl

/-- Ordinary row events leave the state unchanged. -/
theorem row_event_is_silent (s : St) : record s .row = s := rfl

/-- A raised stop stays raised, whatever events follow. -/
theorem stop_is_sticky (b : Nat) (e : Event) :
    (record ⟨.running, .raised, b⟩ e).stop = .raised := by
  cases e <;> rfl

/-- End to end: after a PARTIAL_UPDATE, the engine completes terminally whatever the re-check says,
    so no engine is recreated. -/
theorem partial_update_then_completion_is_terminal (b : Nat) (d : Decision) :
    (handle (record ⟨.running, .clear, b⟩ .partialUpdate) d).engine = .terminal := by
  cases d <;> rfl

end Replication.BinlogGuards

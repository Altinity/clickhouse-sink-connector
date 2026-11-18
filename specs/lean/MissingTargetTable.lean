/-!
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java::isTableAbsent
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java::isTargetTableAbsent
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java::processBatchRecords
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java::processRecordsByTopic
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java::classify
request: a staging connector stopped every 13 minutes for hours on a dropped target table (2026-11-09)
spec: specs/08-schema-catalog/07-missing-target-table-failure.md, invariants 3.1-3.7

The property in words. Two decisions were conflated, and separating them is the fix.

First, the probe. An unresolved writer -- column map empty -- is produced by three
different states of the world: the table does not exist, the catalog could not be
read, and the connection was never established. Only the first can never settle by
retrying, and an empty map does not say which one happened. So the probe answers
three values, and the two collapse directions are both wrong: reporting absence for
an unreadable catalog stops a healthy connector on a blip, and reporting
"could not answer" as presence restores the unbounded retry this change removes.

Second, the classification. The new exception carries no `Code: NNN` -- there is no
server response behind it -- so code extraction alone files it UNKNOWN, and UNKNOWN
is retried. Registering the type makes it FATAL, and the registration must reach it
through the wrapper the single-threaded engine adds, without widening into "every
batch failure stops the connector".

Model. The catalog is a three-valued state; the probe and the decision are pure
functions of it; the thrown value is a cause chain. Only Lean core is used and every
proof is kernel-checked (`rfl`, `simp`, `decide`). Run: `lean MissingTargetTable.lean`.
-/

namespace MissingTargetTable

/-! ## What the catalog says, and what the probe answers -/

/-- What a live `system.tables` read produced. `unreadable` is a null connection or
a query that threw; it is not a statement about the table. -/
inductive Catalog where
  | absent
  | present
  | unreadable
  deriving DecidableEq, Repr

/-- `DBMetadata.isTableAbsent`: `TRUE` absent, `FALSE` present, `null` could not answer. -/
inductive Probe where
  | isTrue
  | isFalse
  | isNone
  deriving DecidableEq, Repr

/-- `probeAnswers c` is `DBMetadata.isTableAbsent`. Note it is a function of the
catalog ALONE: nothing is remembered between calls, so the same table can be
reported absent at one instant and present at the next (section 3.3). -/
def probeAnswers : Catalog → Probe
  | .absent => .isTrue
  | .present => .isFalse
  | .unreadable => .isNone

/-- `DbWriter.isTargetTableAbsent` is `Boolean.TRUE.equals(...)`, so exactly one of the
three answers becomes `true`. -/
def isTargetTableAbsent (c : Catalog) : Bool :=
  match probeAnswers c with
  | .isTrue => true
  | .isFalse => false
  | .isNone => false

/-! ## The decision the batch takes -/

/-- What the batch does. `stop` raises `MissingTargetTableException`; `retry` returns
the existing `false` and the batch is attempted again. -/
inductive Action where
  | stop
  | retry
  deriving DecidableEq, Repr

/-- The batch path: the probe runs only when the writer is unresolved (section 3.4),
because a resolved writer never reaches this branch. -/
def decide (metadataRetrieved : Bool) (c : Catalog) : Action :=
  if metadataRetrieved then .retry
  else if isTargetTableAbsent c then .stop
  else .retry

/-- The behaviour before this change: an unresolved writer always retried, whatever
the catalog said. This is the defect the spec removes. -/
def decideBefore (metadataRetrieved : Bool) (_c : Catalog) : Action :=
  if metadataRetrieved then .retry else .retry

/-! ## Section 3.1/3.2: the stop is taken exactly on proven absence -/

/-- A table the catalog lists is never reported absent, so a present table cannot
stop the connector. -/
theorem present_is_not_absent : isTargetTableAbsent .present = false := rfl

/-- An unreadable catalog is never reported absent, so a network blip cannot stop the
connector (section 3.5). -/
theorem unreadable_is_not_absent : isTargetTableAbsent .unreadable = false := rfl

/-- The probe keeps `false` and `could not answer` apart, which a boolean probe
could not do (section 3.2). -/
theorem probeKeepsUnknownDistinct : probeAnswers .present ≠ probeAnswers .unreadable := by
  decide

/-- An unresolved writer whose table the catalog positively reports missing stops,
and the message names the remedy instead of a row count. -/
theorem absentUnresolvedStops : decide false .absent = .stop := rfl

/-- A writer whose metadata WAS retrieved takes the ordinary path and never probes. -/
theorem resolvedNeverStops : decide true .absent = .retry := rfl

/-- A table that is there keeps the retry: the empty column map is then a read
problem, which the existing metadata path handles. -/
theorem presentStaysARetry : decide false .present = .retry := rfl

/-- A catalog that cannot answer keeps the retry (section 3.5). -/
theorem unreadableStaysARetry : decide false .unreadable = .retry := rfl

/-- The whole decision in one statement: the batch stops if and only if the writer is
unresolved and the catalog positively said the table is missing. -/
theorem stopsIffProvenAbsent (retrieved : Bool) (c : Catalog) :
    decide retrieved c = .stop ↔ retrieved = false ∧ c = .absent := by
  cases retrieved <;> cases c <;> simp [decide, isTargetTableAbsent, probeAnswers]

/-- The old decision retried on a proven-absent table, which is unbounded: the table
cannot appear by retrying. -/
theorem beforeRetriedAProvenAbsence : decideBefore false .absent = .retry := rfl

/-- ... so for that state the two decisions differ, i.e. this is a behaviour change and
only for that state. -/
theorem changeIsExactlyTheAbsentCase :
    decide false .absent ≠ decideBefore false .absent := by
  decide

/-- And for every other state the decision is unchanged, so no working connector
changes behaviour. -/
theorem changeTouchesNothingElse (retrieved : Bool) (c : Catalog) (h : c ≠ .absent) :
    decide retrieved c = decideBefore retrieved c := by
  cases retrieved <;> cases c <;> simp_all [decide, decideBefore, isTargetTableAbsent, probeAnswers]

/-! ## Section 3.3: the probe is not cached -/

/-- A table recreated between two attempts is seen by the second, so the batch that
failed on the previous tick replays against the new table without a restart. -/
theorem recreatedTableIsSeenByTheNextProbe :
    (probeAnswers .absent, probeAnswers .present) = (.isTrue, .isFalse) := rfl

/-! ## Section 3.6: the classification, over a cause chain -/

/-- What a batch can throw. `missingTable` carries no ClickHouse code; `withCode` is a
server error; `noCode` is anything else with no parseable code; `wrapper` is the
`BatchPersistenceException` the single-threaded engine adds around a cause. -/
inductive Thrown where
  | missingTable
  | withCode (code : Int)
  | noCode
  | wrapper (inner : Thrown)
  deriving DecidableEq, Repr

/-- `ClickHouseErrorClassifier.hasTerminalCause`, walked to the root with `isInstance`. -/
def hasTerminalCause : Thrown → Bool
  | .missingTable => true
  | .withCode _ => false
  | .noCode => false
  | .wrapper inner => hasTerminalCause inner

/-- `extractErrorCode`: the first parseable code in the chain, `-1` when there is none. -/
def codeOf : Thrown → Int
  | .withCode c => c
  | .wrapper inner => codeOf inner
  | _ => -1

/-- `FATAL_ERROR_CODES`, as used by `classify`. -/
def inFatalSet (c : Int) : Bool :=
  c == 516 || c == 497 || c == 50 || c == 53 || c == 60 || c == 81 || c == 16 ||
  c == 396 || c == 27 || c == 33 || c == 69 || c == 349

inductive Verdict where
  | retriable
  | fatal
  | unknown
  deriving DecidableEq, Repr

/-- `ClickHouseErrorClassifier.classify`: a terminal type anywhere in the chain wins,
then a missing code is UNKNOWN, then the code set, else RETRIABLE. -/
def classify (t : Thrown) : Verdict :=
  if hasTerminalCause t then .fatal
  else if codeOf t < 0 then .unknown
  else if inFatalSet (codeOf t) then .fatal
  else .retriable

/-- The bare throw from the multi-threaded engine is terminal. -/
theorem missingTableIsFatal : classify .missingTable = .fatal := rfl

/-- The single-threaded engine wraps it; the wrapper must not hide the reason. -/
theorem wrapperDoesNotHideTheReason : classify (.wrapper .missingTable) = .fatal := rfl

/-- Nesting does not hide it either: the walk goes to the root. -/
theorem nestedWrappersDoNotHideTheReason :
    classify (.wrapper (.wrapper (.wrapper .missingTable))) = .fatal := rfl

/-- Non-widening: a wrapper with no terminal cause and no code is still UNKNOWN, so a
transient failure keeps its retry. -/
theorem plainWrapperStaysUnknown : classify (.wrapper .noCode) = .unknown := rfl

/-- ... and an ordinary server code is classified by the code, exactly as before. -/
theorem ordinaryCodeIsUnchanged : classify (.wrapper (.withCode 202)) = .retriable := rfl

/-- The code the incident produced -- UNKNOWN_TABLE -- is FATAL on its own, which is
why the row it could never reach did not stall: the INSERT was refused outright. The
stall came from the empty column map, which carries no code at all. -/
theorem unknownTableIsFatal : classify (.withCode 60) = .fatal := rfl

/-- The wrapper the single-threaded engine adds is transparent: it changes neither
the terminal-cause walk nor the code, so it cannot change the classification. This is
the general form of "the wrapper must not hide the reason". -/
theorem wrapperIsTransparent (inner : Thrown) : classify (.wrapper inner) = classify inner := by
  cases inner <;> rfl

end MissingTargetTable

// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DBMetadata.java::isTableAbsent
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/db/DbWriter.java::isTargetTableAbsent
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchRunnable.java::processBatchRecords
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/executor/ClickHouseBatchWriter.java::processRecordsByTopic
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/common/ClickHouseErrorClassifier.java::classify
// request: a staging connector stopped every 13 minutes for hours on a dropped target table (2026-11-09)
// spec: specs/08-schema-catalog/07-missing-target-table-failure.md, invariants 3.1-3.7
//
// The property in words. An unresolved writer -- an empty column map -- is produced by
// three states of the world: the table does not exist, the catalog could not be read,
// and the connection was never established. Only the first can never settle by
// retrying, and the empty map does not say which one happened. So the probe answers
// three values and the two collapse directions are both wrong: absence for an
// unreadable catalog stops a healthy connector on a blip, and "could not answer" read
// as presence restores the unbounded retry this change removes.
//
// The new exception carries no `Code: NNN`, so code extraction alone files it UNKNOWN
// and UNKNOWN is retried. Registering the type makes it FATAL, and the registration
// must reach it through the wrapper the single-threaded engine adds without widening
// into "every batch failure stops the connector".
//
// Same model as specs/lean/MissingTargetTable.lean.
// Run: dafny verify MissingTargetTable.dfy

datatype Catalog = Absent | Present | Unreadable
datatype Probe = IsTrue | IsFalse | IsNone
datatype Action = Stop | Retry

// DBMetadata.isTableAbsent: a pure function of the catalog, so nothing is cached.
function ProbeAnswers(c: Catalog): Probe
{
  match c
  case Absent => IsTrue
  case Present => IsFalse
  case Unreadable => IsNone
}

// DbWriter.isTargetTableAbsent is Boolean.TRUE.equals(...).
function IsTargetTableAbsent(c: Catalog): bool
{
  match ProbeAnswers(c)
  case IsTrue => true
  case IsFalse => false
  case IsNone => false
}

// The batch path. The probe is reached only when the writer is unresolved.
function Decide(metadataRetrieved: bool, c: Catalog): Action
{
  if metadataRetrieved then Retry
  else if IsTargetTableAbsent(c) then Stop
  else Retry
}

// The behaviour before this change.
function DecideBefore(metadataRetrieved: bool, c: Catalog): Action
{
  if metadataRetrieved then Retry else Retry
}

// Section 3.1/3.2: the stop is taken exactly on proven absence.
lemma PresentIsNotAbsent()
  ensures IsTargetTableAbsent(Present) == false
{}

lemma UnreadableIsNotAbsent()
  ensures IsTargetTableAbsent(Unreadable) == false
{}

// The probe keeps "present" and "could not answer" apart, which a boolean cannot.
lemma ProbeKeepsUnknownDistinct()
  ensures ProbeAnswers(Present) != ProbeAnswers(Unreadable)
{}

lemma AbsentUnresolvedStops()
  ensures Decide(false, Absent) == Stop
{}

lemma ResolvedNeverStops()
  ensures Decide(true, Absent) == Retry
{}

lemma PresentStaysARetry()
  ensures Decide(false, Present) == Retry
{}

lemma UnreadableStaysARetry()
  ensures Decide(false, Unreadable) == Retry
{}

// The whole decision: stop iff the writer is unresolved AND the catalog said absent.
lemma StopsIffProvenAbsent(retrieved: bool, c: Catalog)
  ensures (Decide(retrieved, c) == Stop) <==> (!retrieved && c == Absent)
{
  match c {
    case Absent =>
      if retrieved {
        assert Decide(retrieved, Absent) == Retry;
      } else {
        assert Decide(retrieved, Absent) == Stop;
      }
    case Present =>
      assert ProbeAnswers(Present) == IsFalse;
      assert Decide(retrieved, Present) == Retry;
    case Unreadable =>
      assert ProbeAnswers(Unreadable) == IsNone;
      assert Decide(retrieved, Unreadable) == Retry;
  }
}

// The old decision retried a proven absence, which cannot settle.
lemma BeforeRetriedAProvenAbsence()
  ensures DecideBefore(false, Absent) == Retry
{}

// ... so this is a behaviour change, and only for that state.
lemma ChangeIsExactlyTheAbsentCase()
  ensures Decide(false, Absent) != DecideBefore(false, Absent)
{}

lemma ChangeTouchesNothingElse(retrieved: bool, c: Catalog)
  requires c != Absent
  ensures Decide(retrieved, c) == DecideBefore(retrieved, c)
{
  match c {
    case Absent => // excluded by the precondition
    case Present =>
      assert ProbeAnswers(Present) == IsFalse;
    case Unreadable =>
      assert ProbeAnswers(Unreadable) == IsNone;
  }
}

// Section 3.3: not cached -- a recreated table is seen by the next probe.
lemma RecreatedTableIsSeenByTheNextProbe()
  ensures ProbeAnswers(Absent) == IsTrue
  ensures ProbeAnswers(Present) == IsFalse
{}

// ---------------------------------------------------------------------------
// Section 3.6: the classification, over a cause chain.
// ---------------------------------------------------------------------------

datatype Thrown =
  MissingTable
  | WithCode(code: int)
  | NoCode
  | Wrapper(inner: Thrown)

// ClickHouseErrorClassifier.hasTerminalCause: walked to the root with isInstance.
function HasTerminalCause(t: Thrown): bool
{
  match t
  case MissingTable => true
  case WithCode(_) => false
  case NoCode => false
  case Wrapper(inner) => HasTerminalCause(inner)
}

// extractErrorCode: the first parseable code in the chain, -1 when there is none.
function CodeOf(t: Thrown): int
{
  match t
  case WithCode(c) => c
  case Wrapper(inner) => CodeOf(inner)
  case _ => -1
}

// FATAL_ERROR_CODES, as classify uses it.
function InFatalSet(c: int): bool
{
  c == 516 || c == 497 || c == 50 || c == 53 || c == 60 || c == 81 || c == 16 ||
  c == 396 || c == 27 || c == 33 || c == 69 || c == 349
}

datatype Verdict = Retriable | Fatal | Unknown

// classify: a terminal type anywhere wins, then a missing code is UNKNOWN, then the
// code set, else RETRIABLE.
function Classify(t: Thrown): Verdict
{
  if HasTerminalCause(t) then Fatal
  else if CodeOf(t) < 0 then Unknown
  else if InFatalSet(CodeOf(t)) then Fatal
  else Retriable
}

lemma MissingTableIsFatal()
  ensures Classify(MissingTable) == Fatal
{}

lemma WrapperDoesNotHideTheReason()
  ensures Classify(Wrapper(MissingTable)) == Fatal
{}

lemma NestedWrappersDoNotHideTheReason()
  ensures Classify(Wrapper(Wrapper(Wrapper(MissingTable)))) == Fatal
{}

// The wrapper is transparent: it changes neither the walk nor the code.
lemma WrapperIsTransparent(t: Thrown)
  ensures Classify(Wrapper(t)) == Classify(t)
{
  match t {
    case MissingTable => {}
    case WithCode(c) => {}
    case NoCode => {}
    case Wrapper(_) => {}
  }
}

// Non-widening: a wrapper with no terminal cause and no code is still UNKNOWN.
lemma PlainWrapperStaysUnknown()
  ensures Classify(Wrapper(NoCode)) == Unknown
{}

// An ordinary server code is classified by the code, exactly as before.
lemma OrdinaryCodeIsUnchanged()
  ensures Classify(Wrapper(WithCode(202))) == Retriable
{}

// The code the incident produced -- UNKNOWN_TABLE -- is FATAL on its own; the stall
// came from the empty column map, which carries no code at all.
lemma UnknownTableIsFatal()
  ensures Classify(WithCode(60)) == Fatal
{}

// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::keeps
// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/DdlColumnFilter.java::from
// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsCreateColumn
// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::keepsAlterColumn
// spec-for: sink-connector-lightweight/src/main/java/com/altinity/clickhouse/debezium/embedded/ddl/parser/MySqlDDLParserListenerImpl.java::declaresAnyKeptColumn
// request: community issue 1579 -- with enable.snapshot.ddl the tables are created but
//          column.include.list is ignored, so an excluded column such as a password is
//          still created, empty (2026-11-11)
// spec: specs/06-ddl-replication/04-alter-table-translation.md section 3.6,
//       specs/08-schema-catalog/05-auto-table-creation.md section 3.1.2
//
// The property in words. Debezium applies `column.include.list` /
// `column.exclude.list` to the RECORD schema, so the writer never sends an excluded
// column's value. The schema-DDL path translates the raw MySQL `CREATE TABLE` text
// instead and applied no filter at all, so the two creation paths disagreed about one
// table: the DDL path built the column and the record path never filled it. The fix
// asks the same matcher the record path asks, once per declared column.
//
// Four things must hold, and each is a way the fix could go wrong: an exclusion
// removes the column; a key column is always kept (ordering a ReplacingMergeTree by a
// column that was filtered out either references a missing column or changes which
// source rows collapse); a CREATE is never stranded with zero columns (Debezium's key
// policy throws on one), so a statement whose every column the lists reject falls back
// to every source column with a WARN; and an unjudged identifier -- null or empty
// database or column name, which in CATALOG mode matches nothing -- is never rejected.
//
// Same model as specs/lean/ColumnFilter.lean.
// Run: dafny verify ColumnFilter.dfy

datatype Configured = Neither | IncludeOnly | ExcludeOnly | Both
datatype Lists = Unset | Include | Exclude
datatype Verdict = Unjudged | Selected | Rejected
datatype Col = Col(verdict: Verdict, isKey: bool)

// DdlColumnFilter.from. Debezium's rule: an include list, when set, is the whole rule.
function From(c: Configured): Lists
{
  match c
  case Neither => Unset
  case IncludeOnly => Include
  case ExcludeOnly => Exclude
  case Both => Include
}

lemma NoListIsUnset()
  ensures From(Neither) == Unset
{}

lemma IncludeWinsOverExclude()
  ensures From(Both) == From(IncludeOnly)
{}

lemma ExcludeListIsUsedOnItsOwn()
  ensures From(ExcludeOnly) == Exclude
{}

// DdlColumnFilter.keeps.
function Keeps(lists: Lists, v: Verdict): bool
{
  if lists == Unset then true
  else
    match v
    case Unjudged => true
    case Selected => true
    case Rejected => false
}

lemma NoListKeepsEveryColumn(v: Verdict)
  ensures Keeps(Unset, v)
{}

lemma AnUnjudgedColumnIsNeverRejected(lists: Lists)
  ensures Keeps(lists, Unjudged)
{}

lemma ASelectedColumnIsKept(lists: Lists)
  ensures Keeps(lists, Selected)
{}

lemma AConfiguredListRejects(lists: Lists, v: Verdict)
  requires lists != Unset
  requires v == Rejected
  ensures !Keeps(lists, v)
{}

// keepsColumn: the key promotion. A column the lists reject is still kept when the key
// names it, and the skip is logged at WARN instead of INFO.
function KeepsColumn(lists: Lists, c: Col): bool
{
  Keeps(lists, c.verdict) || c.isKey
}

lemma AKeyColumnIsAlwaysKept(lists: Lists, c: Col)
  requires c.isKey
  ensures KeepsColumn(lists, c)
{}

lemma ARejectedColumnLosesItsClause(lists: Lists, c: Col)
  requires lists != Unset
  requires c.verdict == Rejected
  requires !c.isKey
  ensures !KeepsColumn(lists, c)
{}

// declaresAnyKeptColumn asks the RAW matcher: a column kept only because the key names
// it does not count as a column the lists allow.
function AnyRawKept(lists: Lists, cols: seq<Col>): bool
{
  exists i | 0 <= i < |cols| :: Keeps(lists, cols[i].verdict)
}

// columnFilterBypassedForCreate.
function Bypassed(lists: Lists, cols: seq<Col>): bool
{
  lists != Unset && !AnyRawKept(lists, cols) && |cols| > 0
}

// What the CREATE emits for one column.
function Emitted(lists: Lists, cols: seq<Col>, c: Col): bool
{
  KeepsColumn(lists, c) || Bypassed(lists, cols)
}

// The translator before this change: every declared column was created.
function EmittedBefore(lists: Lists, cols: seq<Col>, c: Col): bool
{
  true
}

lemma UnsetEmitsEveryColumn(cols: seq<Col>, c: Col)
  ensures Emitted(Unset, cols, c)
{}

lemma AnUnjudgedColumnIsAlwaysCreated(lists: Lists, cols: seq<Col>)
  ensures Emitted(lists, cols, Col(Unjudged, false))
{}

lemma ARejectedColumnIsNotCreated(lists: Lists, cols: seq<Col>, c: Col)
  requires lists != Unset
  requires c.verdict == Rejected
  requires !c.isKey
  requires !Bypassed(lists, cols)
  ensures !Emitted(lists, cols, c)
{}

lemma ABypassedCreateEmitsEveryColumn(lists: Lists, cols: seq<Col>, c: Col)
  requires Bypassed(lists, cols)
  ensures Emitted(lists, cols, c)
{}

lemma ACreateWithNoKeptColumnBypasses(lists: Lists, cols: seq<Col>)
  requires lists != Unset
  requires |cols| > 0
  requires !AnyRawKept(lists, cols)
  ensures Bypassed(lists, cols)
{}

// The bypass stays narrow: a table the lists select any column of is never bypassed.
lemma ATableWithASelectedColumnIsNotBypassed(lists: Lists, cols: seq<Col>, c: Col)
  requires c in cols
  requires c.verdict == Selected
  ensures !Bypassed(lists, cols)
{
  assert Keeps(lists, Selected);
  assert AnyRawKept(lists, cols);
}

// ---------------------------------------------------------------------------
// The table of issue 1579
// ---------------------------------------------------------------------------

// CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(100))
// under column.include.list = app.users.(id|name).
function Users(): seq<Col>
{
  [Col(Selected, true), Col(Selected, false), Col(Rejected, false)]
}

function Password(): Col
{
  Col(Rejected, false)
}

lemma TheKeyIsCreated()
  ensures Emitted(Include, Users(), Col(Selected, true))
{}

lemma TheNameIsCreated()
  ensures Emitted(Include, Users(), Col(Selected, false))
{}

// The users table declares a column the lists select, so no schedule ever bypasses it:
// this is the witness that keeps the zero-column fallback from widening.
lemma UsersHasASelectedColumn(lists: Lists)
  ensures AnyRawKept(lists, Users())
{
  var u := Users();
  assert u[0] == Col(Selected, true);
  assert Keeps(lists, Selected);
  assert Keeps(lists, u[0].verdict);
}

lemma ThePasswordIsNotCreated()
  ensures !Emitted(Include, Users(), Password())
{
  assert !Keeps(Include, Rejected);
  assert !KeepsColumn(Include, Password());
  UsersHasASelectedColumn(Include);
  assert !Bypassed(Include, Users());
}

// The password was created, empty, before this change -- the reported symptom.
lemma ThePasswordWasCreatedBefore()
  ensures EmittedBefore(Include, Users(), Password())
{}

// The whole change on the reported table, in both directions: a users column leaves
// the CREATE exactly when the lists are configured, reject it, and the key does not
// name it. Split into one lemma per direction so each obligation is dischargeable.

lemma AColumnThatLeavesTheCreateIsARejectedNonKeyColumn(lists: Lists, v: Verdict, k: bool)
  requires !Emitted(lists, Users(), Col(v, k))
  ensures lists != Unset && v == Rejected && !k
{
  if lists == Unset {
    assert Keeps(Unset, v);
    assert KeepsColumn(Unset, Col(v, k));
    assert Emitted(Unset, Users(), Col(v, k));
  } else {
    // Emitted is KeepsColumn || Bypassed, so a dropped column fails both.
    assert !KeepsColumn(lists, Col(v, k));
    assert !Keeps(lists, v);
    match v
    case Unjudged => {}
    case Selected => {}
    case Rejected => {}
  }
}

lemma ARejectedNonKeyColumnLeavesTheCreate(lists: Lists, v: Verdict, k: bool)
  requires lists != Unset
  requires v == Rejected
  requires !k
  ensures !Emitted(lists, Users(), Col(v, k))
{
  UsersHasASelectedColumn(lists);
  assert !Keeps(lists, v);
  assert !KeepsColumn(lists, Col(v, k));
  assert !Bypassed(lists, Users());
}

lemma TheFixRemovesExactlyTheRejectedNonKeyColumn(lists: Lists, v: Verdict, k: bool)
  ensures !Emitted(lists, Users(), Col(v, k)) ==>
            (lists != Unset && v == Rejected && !k)
  ensures (lists != Unset && v == Rejected && !k) ==>
            !Emitted(lists, Users(), Col(v, k))
{
  if !Emitted(lists, Users(), Col(v, k)) {
    AColumnThatLeavesTheCreateIsARejectedNonKeyColumn(lists, v, k);
  }
  if lists != Unset && v == Rejected && !k {
    ARejectedNonKeyColumnLeavesTheCreate(lists, v, k);
  }
}

// CREATE TABLE t (a INT, b INT) with an include list that names neither: a CREATE of no
// columns cannot be translated, so every source column is kept.
function AllRejected(): seq<Col>
{
  [Col(Rejected, false), Col(Rejected, false)]
}

lemma EveryColumnSurvivesWhenNoneIsKept(c: Col)
  requires c in AllRejected()
  ensures Emitted(Include, AllRejected(), c)
{
  assert !Keeps(Include, Rejected);
  assert !AnyRawKept(Include, AllRejected());
  assert Bypassed(Include, AllRejected());
}

// ---------------------------------------------------------------------------
// The ALTER: the same predicate, against the target table's sorting key
// ---------------------------------------------------------------------------

lemma AnAlterOnASortingKeyColumnIsApplied(lists: Lists, c: Col)
  requires c.isKey
  ensures KeepsColumn(lists, c)
{}

lemma AnAlterOnARejectedNonKeyColumnIsSkipped(lists: Lists, c: Col)
  requires lists != Unset
  requires c.verdict == Rejected
  requires !c.isKey
  ensures !KeepsColumn(lists, c)
{}

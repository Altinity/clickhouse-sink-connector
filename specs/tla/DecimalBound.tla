-------------------------- MODULE DecimalBound --------------------------
(***************************************************************************)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMax *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMin *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::RangePolicy.boundDecimal *)
(* request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release" *)
(* spec: specs/07-type-system/02-floating-decimal-types.md, section 3.2.1 *)
(*                                                                         *)
(* A decimal a column cannot hold is replaced by the bound OF THAT COLUMN, *)
(* and the replacement is itself a value the column accepts.               *)
(*                                                                         *)
(* Why this model is temporal. One release cannot show the defect: whatever *)
(* value it stores for a source value, it stores the same value again when  *)
(* it rewrites that value, so a single-release model proves nothing. The    *)
(* defect needs TWO releases. A Decimal column is commonly part of a        *)
(* ReplacingMergeTree sorting key, which collapses two rows only when the   *)
(* WHOLE key is equal, so if the release that wrote the row and the release *)
(* that writes over it replace the same source value with different stored *)
(* values, the second row never replaces the first, FINAL returns both, and *)
(* ClickHouse holds MORE rows than MySQL.                                  *)
(*                                                                         *)
(* The state is therefore `applied`: the set of releases that have written  *)
(* this source value so far. Repeated writes by a release already in the    *)
(* set add nothing -- the stored value is a function of                     *)
(* (release, column, source value) -- so the set is the whole of the        *)
(* relevant state, and a release absent from it has not written yet.        *)
(*                                                                         *)
(* MAGNITUDES. TLC's integers are 32-bit, and a Decimal(38) bound is        *)
(* 10^38 - 1. The model therefore carries the four widths ClickHouse spells *)
(* as stand-ins that keep their ORDER and their distinctness, which is all  *)
(* the state machine uses:                                                  *)
(*   Decimal32  ->  MaxOf(9)   =  9    (really 999999999, 9 nines)          *)
(*   Decimal64  ->  MaxOf(18)  = 18    (really 999999999999999999)          *)
(*   Decimal128 ->  MaxOf(38)  = 38    (really 10^38 - 1)                   *)
(*   Decimal256 ->  MaxOf(65)  = 65    (really 10^65 - 1; Decimal(65, 30)   *)
(*                                       is the widest storable shape)      *)
(*   OldConstant ->              50    (really 2^127 - 1, which has 39      *)
(*                                       digits: above 10^38 - 1 and below  *)
(*                                       10^65 - 1, exactly as modelled)    *)
(* The exact values are carried by specs/lean, specs/dafny and              *)
(* specs/hypothesis, which work in unbounded integers.                      *)
(*                                                                         *)
(* `PerRelease` selects what the CURRENT release does:                      *)
(*   FALSE -- it replaces an out-of-range value with the bound of THE       *)
(*            COLUMN (this change), so it cannot disagree with a release    *)
(*            that wrote the row under the same rule, and the invariant     *)
(*            HOLDS;                                                        *)
(*   TRUE  -- it replaces it with the fixed old constant (2.11.1 and        *)
(*            earlier), which is above the bound of a Decimal128 column and *)
(*            below the ceiling of a Decimal(65, 30) one, so either the     *)
(*            stored value is one the column refuses or it differs from the *)
(*            value the column's own bound produces.                        *)
(*                                                                         *)
(* TLC runs this module with PerRelease = FALSE: the rule the code must     *)
(* satisfy. The TRUE run is the regression demonstration.                   *)
(***************************************************************************)
EXTENDS Integers, FiniteSets

CONSTANT PerRelease

\* The column maximum of a Decimal with p digits, as a small stand-in that
\* keeps the order of the real values.
MaxOf(p) == p

\* The column minimum: the negation of the same bound.
MinOf(p) == -MaxOf(p)

\* Decimal(p, s) accepts exactly the counts in [MinOf(p), MaxOf(p)].
Accepts(p, x) == MinOf(p) <= x /\ x <= MaxOf(p)

\* The constant the defect replaced out-of-range values with, 2^127 - 1. It has
\* 39 digits, so it sits strictly between the Decimal128 bound (38 digits) and
\* the Decimal65 bound (65 digits) -- which is exactly the stand-in's position.
OldConstant == 50

\* The two releases that can touch one row: the one that wrote it (always the
\* column's-own-bound rule, i.e. this change or any release before 2.11.1 for a
\* column wide enough) and the one writing now. They stay DISTINCT whatever
\* `PerRelease` is, so the cardinality invariant below is never satisfied merely
\* because there is only one release to compare -- a singleton would make it
\* hold unconditionally and prove nothing.
Historical == "historical"
Current == "current"
Releases == {Historical, Current}

\* The value a release stores in place of an out-of-range one.
SaturationValue(release, p) ==
  IF release = Current /\ PerRelease THEN OldConstant
  ELSE MaxOf(p)

\* boundDecimal: the value the connector stores in a column of p digits.
Stored(release, p, v) ==
  IF v > MaxOf(p) THEN SaturationValue(release, p)
  ELSE IF v < MinOf(p) THEN MinOf(p)
  ELSE v

\* The widths a source column can have in this model.
Widths == {9, 18, 38, 65}

\* The source values modelled: both sides of the minimum and the maximum of
\* every width, the old constant from both sides, and a value one unit past the
\* widest bound.
SourceValues ==
  {MinOf(9) - 1, MinOf(9), MinOf(9) + 1,
   MaxOf(9), MaxOf(9) + 1,
   MaxOf(18), MaxOf(38),
   OldConstant - 1, OldConstant, OldConstant + 1,
   MaxOf(65), MaxOf(65) + 1}

\* applied: the set of releases that have written this source value.
VARIABLES applied

Init == applied = {}

\* Unguarded on purpose: once every release has written, the union adds nothing
\* and the step stutters. Guarding it on `Releases \ applied` would leave no
\* enabled action in that state, which TLC reports as a deadlock, not as a
\* clean end.
Next == \E r \in Releases : applied' = applied \union {r}

Spec == Init /\ [][Next]_applied

\* ------------------------------------------------------------------ *
\* The invariant ReplacingMergeTree needs: one source value, one key.  *
\* ------------------------------------------------------------------ *

\* The values the column holds for one source value, over every release that
\* has written it.
StoredByReleases(p, v) == {Stored(r, p, v) : r \in applied}

OneStoredValuePerSourceValue ==
  \A p \in Widths, v \in SourceValues :
    IsFiniteSet(StoredByReleases(p, v)) /\ Cardinality(StoredByReleases(p, v)) <= 1

\* B3: whatever a release stores is a value the column accepts. This is what a
\* constant chosen for another width does not give.
EveryStoredValueIsAccepted ==
  \A r \in Releases, p \in Widths, v \in SourceValues : Accepts(p, Stored(r, p, v))

\* B1: an in-range value is never replaced, whatever the release.
InRangeIsKept ==
  \A r \in Releases, p \in Widths, v \in SourceValues :
    (MinOf(p) <= v /\ v <= MaxOf(p)) => Stored(r, p, v) = v

\* B6: the old constant is above the Decimal128 bound and below the Decimal65
\* one, so on the one it is refused and on the other it is not the bound.
OldConstantIsPastDecimal128 == MaxOf(38) < OldConstant
OldConstantIsBelowDecimal65 == OldConstant < MaxOf(65)

\* B5: the two bounds are distinct, so a low and a high saturation never collide.
TheTwoBoundsAreDistinct == \A p \in Widths : MinOf(p) # MaxOf(p)

\* ONE conjunct, deliberately: the property TLC must invalidate under the old
\* rule is the write-time key split, and it has to appear only AFTER both
\* releases have written. Folding a static fact such as
\* `OldConstantIsPastDecimal128` in here would make the run fail in the initial
\* state instead -- true, but it would demonstrate the arithmetic rather than the
\* production symptom. The static facts are defined above and are proved over
\* unbounded integers by specs/lean, specs/dafny and specs/hypothesis.
Inv == OneStoredValuePerSourceValue

=============================================================================

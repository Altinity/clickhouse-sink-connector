---------------------------- MODULE HistoryTimeInstant ----------------------------
(***************************************************************************)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::TimestampConverter.instantEpochText *)
(* spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java::executeInsertWithStructs *)
(* request: Altinity/clickhouse-sink-connector pull request 1572, "Bind binlog history _time as the instant of source.ts_ms" *)
(* spec: specs/12-replication-history/04-binlog-history-audit-table.md, property T1 *)
(*                                                                         *)
(* Property T1 in words. The history _time is bound from source.ts_ms, a   *)
(* true instant (milliseconds after the epoch). For every ts_ms and every  *)
(* column zone the bound text is read by ClickHouse as exactly that        *)
(* instant. It is epoch text "<seconds>.<6 fraction digits>" (zone-free)   *)
(* except for the one second before 1970-01-01 UTC, where ClickHouse drops *)
(* the sign of the text ("-0.500000" reads as +0.5 s); that second is      *)
(* bound as wall digits in the column zone, exact there because the zone   *)
(* has no overlap hour at 1969-12-31 23:59:59.                             *)
(*                                                                         *)
(* Two independent finite models, selected by `mode`:                      *)
(*  "epoch"  every millisecond from -2200 to 2200 (both sides of the -1000 *)
(*           and 0 boundaries, 4401 instants);                             *)
(*  "digits" a zone and an instant on a coarse clock: 1 tick = 15 minutes, *)
(*           HOUR = 4 ticks. Zones: utc (offset 0), cst (-6 h), cdt (-5 h),*)
(*           chicago (cdt before tick FALLBACK, cst from it: one repeated  *)
(*           hour). Instants range over -30..30 ticks.                     *)
(* TLC proves the invariants only inside these bounds. The all-integer    *)
(* proofs are in specs/lean and specs/dafny.                               *)
(***************************************************************************)
EXTENDS Integers

\* ------------------------------ epoch text ----------------------------------
\* \div and % are floor division and non-negative modulo for a positive divisor
\* (Math.floorDiv / Math.floorMod, which Instant.ofEpochMilli uses).
ASSUME /\ ((-1) \div 1000) = (-1)
       /\ ((-1) % 1000) = 999
       /\ ((-1000) \div 1000) = (-1)
       /\ ((-999) \div 1000) = (-1)

MsRange == -2200..2200

Abs(v) == IF v < 0 THEN -v ELSE v
EpochSecond(ms) == ms \div 1000
NanoOfSecond(ms) == (ms % 1000) * 1000000
\* epochSecond + nano / 1e9 at scale 6, truncated, as a count of microseconds
Scaled(ms) == EpochSecond(ms) * 1000000 + (NanoOfSecond(ms) \div 1000)
\* BigDecimal.toPlainString of a value in microseconds
Render(v) == [neg |-> (v < 0), whole |-> Abs(v) \div 1000000, frac |-> Abs(v) % 1000000]
\* What ClickHouse reads, in microseconds; a leading minus on a text whose whole
\* part is 0 is dropped (measured on 24.8.14: '-0.500000' reads as +0.5 s).
ChRead(t) == IF t.neg /\ t.whole = 0 THEN t.frac
             ELSE IF t.neg THEN -(t.whole * 1000000 + t.frac)
             ELSE t.whole * 1000000 + t.frac
BindsAsEpochText(ms) == ~(EpochSecond(ms) = -1 /\ NanoOfSecond(ms) > 0)

\* ------------------------------ wall digits ---------------------------------
HOUR == 4
FALLBACK == 12
Ticks == -80..80
InstantTicks == -30..30
Zones == {"utc", "cst", "cdt", "chicago"}

Off(z, s) == IF z = "utc" THEN 0
             ELSE IF z = "cst" THEN -6 * HOUR
             ELSE IF z = "cdt" THEN -5 * HOUR
             ELSE (IF s < FALLBACK THEN -5 * HOUR ELSE -6 * HOUR)

Wall(z, s) == s + Off(z, s)
Readings(z, w) == {s \in Ticks : Wall(z, s) = w}
\* ClickHouse resolves wall digits to the first instant that shows them.
FirstReading(z, w) == CHOOSE m \in Readings(z, w) : \A r \in Readings(z, w) : m <= r

VARIABLES mode, ms, z, t
vars == <<mode, ms, z, t>>

Init == \/ /\ mode = "epoch" /\ ms \in MsRange /\ z = "-" /\ t = 0
        \/ /\ mode = "digits" /\ ms = 0 /\ z \in Zones /\ t \in InstantTicks

Next == UNCHANGED vars

Spec == Init /\ [][Next]_vars

\* ------------------------------ invariants ----------------------------------
TypeOK == /\ mode \in {"epoch", "digits"}
          /\ ms \in MsRange
          /\ z \in Zones \cup {"-"}
          /\ t \in InstantTicks

\* Six fraction digits hold every millisecond instant exactly.
ScaledIsMs == mode = "epoch" => Scaled(ms) = ms * 1000

\* T1, epoch branch: bound as epoch text, read back exactly.
EpochExact == (mode = "epoch" /\ BindsAsEpochText(ms)) => ChRead(Render(Scaled(ms))) = ms * 1000

\* The digits fallback fires exactly when epoch text would be misread.
FallbackIffMisread == mode = "epoch" =>
                        (~BindsAsEpochText(ms) <=> ChRead(Render(Scaled(ms))) # ms * 1000)

\* The fallback is exactly the sub-second before the epoch.
FallbackSet == mode = "epoch" => (~BindsAsEpochText(ms) <=> (-1000 < ms /\ ms < 0))

\* A zone of fixed offset reads its own digits back as the same instant.
ConstZonesExact == (mode = "digits" /\ z \in {"utc", "cst", "cdt"}) =>
                     FirstReading(z, Wall(z, t)) = t

\* The old rendering: UTC digits read in a zone of fixed offset are a different instant ...
LegacyShift == (mode = "digits" /\ z \in {"cst", "cdt"}) =>
                 FirstReading(z, Wall("utc", t)) = t - Off(z, t)
\* ... 5 h late in cdt, 6 h late in cst.
LegacyWrong == (mode = "digits" /\ z \in {"cst", "cdt"}) =>
                 FirstReading(z, Wall("utc", t)) # t

\* The repeated hour: the second occurrence is read as the first (one hour early),
\* every other instant reads back exactly. Epoch text has no zone in it.
OverlapSecondOccurrence == (mode = "digits" /\ z = "chicago") =>
    IF t >= FALLBACK /\ t < FALLBACK + HOUR
    THEN FirstReading(z, Wall(z, t)) = t - HOUR
    ELSE FirstReading(z, Wall(z, t)) = t

=============================================================================

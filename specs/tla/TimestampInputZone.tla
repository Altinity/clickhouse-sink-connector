------------------------ MODULE TimestampInputZone ------------------------
(***************************************************************************)
(* spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::timestamp_input_datatype *)
(* spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::load_data_mysqlshell *)
(* request: Altinity/clickhouse-sink-connector pull request, "Pin the dump's TIMESTAMP zone in the loader's input() declaration" *)
(* spec: specs/13-python-toolset/04-clickhouse-loader-and-mysql-ddl-translation.md, section 3.9 *)
(*                                                                         *)
(* Property Z1 in words. A MySQL Shell dump writes TIMESTAMP values as     *)
(* text in the zone the dump records ("tzUtc": true in @.json, so UTC).    *)
(* The loader hands that text to clickhouse-client inside                 *)
(* FROM input('<structure>'), and the declaration of the field inside      *)
(* that structure decides how the text is read: a zone-less DateTime64(p)  *)
(* is read in the AMBIENT zone of the load (the client zone under          *)
(* --use_client_time_zone 1, else the server's), so the instant it stores  *)
(* moves with the machine that runs the load; a declaration naming a zone  *)
(* is read in that zone, so the stored instant is the same everywhere.     *)
(*                                                                         *)
(* Two finite models, selected by `mode`:                                  *)
(*  "pinned"   the loader resolved a zone and the declaration names it:    *)
(*             every ambient zone stores the same instant;                *)
(*  "zoneLess" the declaration names no zone, which is the defect: the     *)
(*             stored instant is one the ambient zone chose, so it        *)
(*             equals the dump's instant only when the two offsets agree.  *)
(*                                                                         *)
(* TLC holds integers in 32 bits, so the model counts whole MINUTES after  *)
(* the epoch rather than microseconds: every offset here is a whole number *)
(* of hours and readings are read at whole-minute resolution. The         *)
(* subtraction Stored performs is the one the loader's text parse performs, *)
(* and the microsecond vectors are proved in specs/lean and specs/dafny and *)
(* asserted against the measurement in specs/hypothesis.                   *)
(***************************************************************************)
EXTENDS Integers

\* Whole minutes in an hour, and the zones of the measurement by UTC offset in
\* minutes: utc, America/Chicago in summer (-05:00) and in winter (-06:00), and
\* Europe/London in summer (+01:00).
HOUR == 60
Zones == {0, -5 * HOUR, -6 * HOUR, HOUR}

\* The dump's two readings in whole minutes after the epoch:
\* "2018-04-05 20:44:35" and "2018-12-01 00:18:03", truncated to the minute.
April == 1522961075 \div 60
December == 1543623483 \div 60
Readings == {April, December}

\* The declarations under test: the fix pins the dump's zone, the defect does not.
Declared == {"zoneLess", "pinned"}

\* The dump's zone: UTC, because the dump records "tzUtc": true.
DumpZone == 0

\* The stored instant, in whole minutes: reading offset-free text in a zone of
\* that offset is one subtraction.
Stored(d, ambient, w) == IF d = "zoneLess" THEN w - ambient ELSE w - DumpZone

VARIABLES mode, d, ambient, w

vars == <<mode, d, ambient, w>>

Init == \/ /\ mode = "pinned" /\ d = "pinned" /\ ambient \in Zones /\ w \in Readings
        \/ /\ mode = "zoneLess" /\ d = "zoneLess" /\ ambient \in Zones /\ w \in Readings

Next == UNCHANGED vars

Spec == Init /\ [][Next]_vars

\* ------------------------------ invariants ----------------------------------

TypeOK == /\ mode \in {"pinned", "zoneLess"}
          /\ d \in Declared
          /\ ambient \in Zones
          /\ w \in Readings

\* Z1: under the declaration the fix emits the stored instant does not depend on
\* the ambient zone at all, so two loads on two machines agree.
IgnoresAmbient == mode = "pinned" =>
                    \A a \in Zones : Stored("pinned", a, w) = w - DumpZone

\* Z2: with no zone in the declaration the stored instant moves with the ambient
\* zone, so it equals the dump's instant only when the ambient zone IS that zone.
ZoneLessFollowsAmbient == mode = "zoneLess" =>
                            (Stored("zoneLess", ambient, w) = w <=> ambient = DumpZone)

\* Z3: no single ambient zone makes the zone-less declaration right for every
\* zone at once, which is why the instant moved between two machines.
ZoneLessNeverAgreesEverywhere == mode = "zoneLess" =>
    \A a, b \in Zones : a # b => \/ Stored("zoneLess", a, w) # w
                                \/ Stored("zoneLess", b, w) # w

\* The defect is off by exactly the ambient zone's offset, at both readings.
ZoneLessErrorIsTheOffset == mode = "zoneLess" =>
                              \A a \in Zones : Stored("zoneLess", a, w) - w = -a

\* Z4, measured: the two readings under the three measured offsets. The zone-less
\* declaration is exact only for the zone the dump used; the pinned declaration is
\* exact everywhere.
Measured == mode = "zoneLess" =>
    /\ Stored("zoneLess", 0, April) = April
    /\ Stored("zoneLess", HOUR, April) = April - HOUR
    /\ Stored("zoneLess", -5 * HOUR, April) = April + 5 * HOUR
    /\ Stored("zoneLess", 0, December) = December
    /\ Stored("zoneLess", -6 * HOUR, December) = December + 6 * HOUR

MeasuredPinned == mode = "pinned" =>
    /\ \A a \in Zones : Stored("pinned", a, April) = April
    /\ \A a \in Zones : Stored("pinned", a, December) = December

=============================================================================

/-!
spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::timestamp_input_datatype
spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::load_data_mysqlshell
request: Altinity/clickhouse-sink-connector pull request, "Pin the dump's TIMESTAMP zone in the loader's input() declaration"
spec: specs/13-python-toolset/04-clickhouse-loader-and-mysql-ddl-translation.md, section 3.9

Property Z1 in words. A MySQL Shell dump writes `TIMESTAMP` values as text in the
zone the dump records (`"tzUtc": true` in `@.json`, so UTC). The loader hands
that text to `clickhouse-client` inside `FROM input('<structure>')`, and the
declaration of the field inside that structure decides how the text is read:

* a zone-less `DateTime64(p)` is read in the AMBIENT zone of the load -- the
  client zone under `--use_client_time_zone 1`, else the server's -- so the
  instant it stores moves with the machine that runs the load;
* a declaration naming a zone, `DateTime64(p, '<zone>')`, is read in that zone,
  so the stored instant is the same everywhere.

`timestamp_input_datatype(datatype, timezone)` pins the zone the loader resolved
from the dump, or from `--source_timezone`, into the declaration.

Model. An instant is an `Int` count of microseconds after the epoch and a zone
is its UTC offset in microseconds; a constant offset is enough for every
property below, because each property is stated for a pair of offsets and the
measured offsets of America/Chicago and Europe/London appear as literals. Only
Lean core is used and every proof is kernel-checked (`simp`, `omega`, `decide`).
Run: `lean TimestampInputZone.lean`.
-/

namespace TimestampInputZone

/-- The declaration the loader writes into the `input()` structure, as the zone
it names. `zoneLess` is the defect: nothing in it says which zone reads it. -/
inductive Decl where
  | zoneLess
  | pinned (offset : Int)
  deriving DecidableEq, Repr

/-- `timestamp_input_datatype(datatype, timezone)` in words. A declaration that
already names a zone is returned unchanged, so `--clickhouse_datetime_timezone`
still decides that column; otherwise the zone the loader resolved is pinned into
it. A `none` resolved zone is the case the load refuses before any DDL, kept
here only so the function is total. -/
def timestampInputDatatype (declaredZone resolvedZone : Option Int) : Decl :=
  match declaredZone with
  | some c => .pinned c
  | none =>
    match resolvedZone with
    | some d => .pinned d
    | none => .zoneLess

/-- The instant ClickHouse stores for text whose offset-free reading is
`wallMicros`, when the declaration is `d` and the ambient zone's offset is
`ambientOffset`. -/
def stored (d : Decl) (ambientOffset wallMicros : Int) : Int :=
  match d with
  | .zoneLess => wallMicros - ambientOffset
  | .pinned c => wallMicros - c

/-! ## Z1, Z2: the stored instant under a named zone and under no zone -/

/-- Z1. Two loads of the same text under two ambient zones store the same instant
when the declaration names a zone. -/
theorem pinned_ignores_ambient (c a a' w : Int) :
    stored (.pinned c) a w = stored (.pinned c) a' w := by
  simp [stored]

/-- Z2. A zone-less declaration stores an instant that moves with the ambient
zone: whenever the two ambient offsets differ, so do the stored instants. -/
theorem zone_less_follows_ambient (a a' w : Int) (h : a ≠ a') :
    stored .zoneLess a w ≠ stored .zoneLess a' w := by
  have hread : ∀ b : Int, stored .zoneLess b w = w - b := by
    intro b
    simp [stored]
  rw [hread a, hread a']
  intro heq
  exact h (by omega)

/-! ## Z3: the fix stores the dump's instant, the defect followed the load's zone -/

/-- Z3a. For a dump whose text is in the resolved zone, the declaration the fix
emits stores the instant that text names, whatever the ambient zone is. -/
theorem the_fix_stores_the_instant (dumpOffset a w : Int) :
    stored (timestampInputDatatype none (some dumpOffset)) a w = w - dumpOffset := by
  simp [timestampInputDatatype, stored]

/-- Z3b. The defect this pins: with no zone in the declaration the stored instant
is the ambient zone's offset away from the dump's instant, so only an ambient
zone equal to the dump's zone stored it correctly. -/
theorem before_the_fix_followed_the_ambient (a w : Int) (h : a ≠ 0) :
    stored (timestampInputDatatype none none) a w ≠ w := by
  have hread : stored (timestampInputDatatype none none) a w = w - a := by
    simp [stored, timestampInputDatatype]
  rw [hread]
  intro heq
  exact h (by omega)

/-! ## Z4: the zone the flag names is kept, and a second pass changes nothing -/

/-- Z4a. A declaration that already names a zone is returned unchanged, so
`--clickhouse_datetime_timezone` still decides that column, not the dump zone. -/
theorem an_already_named_zone_is_kept (declaredZone resolvedZone a w : Int) :
    stored (timestampInputDatatype (some declaredZone) (some resolvedZone)) a w
      = w - declaredZone := by
  simp [timestampInputDatatype, stored]

/-- Z4b. A second pass over the emitted declaration keeps the zone the first pass
pinned, whatever zone a later run resolves, so declaring twice cannot drift. -/
theorem a_second_pass_keeps_the_zone (resolvedZone laterZone a w : Int) :
    stored (timestampInputDatatype (some resolvedZone) (some laterZone)) a w
      = stored (timestampInputDatatype none (some resolvedZone)) a w := by
  simp [timestampInputDatatype, stored]

/-! ## The measured vectors -/

/-- The measured instants, in microseconds, of the dump's UTC text
`2018-04-05 20:44:35.772929` and `2018-12-01 00:18:03.545683`, under a zone-less
declaration and the offsets of America/Chicago in summer (-05:00) and winter
(-06:00) and of Europe/London in summer (+01:00). Measured on 24.8.14. -/
theorem measured_zone_less :
    stored .zoneLess (-18000000000) 1522961075772929 = 1522979075772929 ∧
    stored .zoneLess (-21600000000) 1543623483545683 = 1543645083545683 ∧
    stored .zoneLess 3600000000 1522961075772929 = 1522957475772929 ∧
    stored .zoneLess 0 1543623483545683 = 1543623483545683 := by
  simp only [stored]
  omega

/-- The same text under the declaration the fix emits, the dump recording UTC: one
instant under every ambient zone, and it is the instant the text names. -/
theorem measured_pinned :
    stored (timestampInputDatatype none (some 0)) (-18000000000) 1522961075772929
      = 1522961075772929 ∧
    stored (timestampInputDatatype none (some 0)) 3600000000 1522961075772929
      = 1522961075772929 ∧
    stored (timestampInputDatatype none (some 0)) (-21600000000) 1543623483545683
      = 1543623483545683 := by
  simp only [stored, timestampInputDatatype]
  omega

end TimestampInputZone

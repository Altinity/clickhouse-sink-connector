// spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::timestamp_input_datatype
// spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::load_data_mysqlshell
// request: Altinity/clickhouse-sink-connector pull request, "Pin the dump's TIMESTAMP zone in the loader's input() declaration"
// spec: specs/13-python-toolset/04-clickhouse-loader-and-mysql-ddl-translation.md, section 3.9
//
// Property Z1 in words. A MySQL Shell dump writes TIMESTAMP values as text in the
// zone the dump records ("tzUtc": true in @.json, so UTC). The loader hands that
// text to clickhouse-client inside FROM input('<structure>'), and the
// declaration of the field inside that structure decides how the text is read:
// a zone-less DateTime64(p) is read in the AMBIENT zone of the load (the client
// zone under --use_client_time_zone 1, else the server's), so the instant it
// stores moves with the machine that runs the load; a declaration naming a zone
// is read in that zone, so the stored instant is the same everywhere.
// timestamp_input_datatype(datatype, timezone) pins the resolved zone into it.
//
// Model: an instant is an int count of microseconds after the epoch; a zone is
// its UTC offset in microseconds. Same model as specs/lean/TimestampInputZone.lean.
// MaybeInt stands in for the Python option: Nothing is None, Just(z) is a zone.
// Run: dafny verify TimestampInputZone.dfy

datatype MaybeInt = Nothing | Just(value: int)

datatype Decl = ZoneLess | Pinned(offset: int)

// timestamp_input_datatype(datatype, timezone) in words: a declaration that
// already names a zone is kept (--clickhouse_datetime_timezone decides it);
// otherwise the zone the loader resolved is pinned into it.
function Resolve(declared: MaybeInt, resolved: MaybeInt): MaybeInt
{
  match declared
  case Just(c) => Just(c)
  case Nothing => resolved
}

function TimestampInputDatatype(declared: MaybeInt, resolved: MaybeInt): Decl
{
  match Resolve(declared, resolved)
  case Just(z) => Pinned(z)
  case Nothing => ZoneLess
}

// The instant ClickHouse stores for wall text whose offset-free reading is
// wallMicros, when the declaration is d and the ambient zone's offset is
// ambientOffset.
function Stored(d: Decl, ambientOffset: int, wallMicros: int): int
{
  match d
  case ZoneLess => wallMicros - ambientOffset
  case Pinned(c) => wallMicros - c
}

// ---- Z1, Z2: the stored instant under a named zone and under no zone ----

// Z1: two loads of the same text under two ambient zones store one instant when
// the declaration names a zone.
lemma PinnedIgnoresAmbient(c: int, a: int, a': int, w: int)
  ensures Stored(Pinned(c), a, w) == Stored(Pinned(c), a', w)
{
}

// Z2: a zone-less declaration stores an instant that moves with the ambient zone.
lemma ZoneLessFollowsAmbient(a: int, a': int, w: int)
  requires a != a'
  ensures Stored(ZoneLess, a, w) != Stored(ZoneLess, a', w)
{
}

// ---- Z3: the fix stores the dump's instant, the defect followed the load's zone ----

// Z3a: the declaration the fix emits stores the dump's own instant, whatever the
// ambient zone is.
lemma TheFixStoresTheInstant(dumpOffset: int, a: int, w: int)
  ensures Stored(TimestampInputDatatype(Nothing, Just(dumpOffset)), a, w) == w - dumpOffset
{
}

// Z3b: with no zone in the declaration the stored instant is the ambient zone's
// offset away from the dump's instant.
lemma BeforeTheFixFollowedTheAmbient(a: int, w: int)
  requires a != 0
  ensures Stored(TimestampInputDatatype(Nothing, Nothing), a, w) != w
{
}

// ---- Z4: the zone the flag names is kept ----

// Z4a: a declaration that already names a zone is returned unchanged.
lemma AnAlreadyNamedZoneIsKept(declaredZone: int, resolvedZone: int, a: int, w: int)
  ensures Stored(TimestampInputDatatype(Just(declaredZone), Just(resolvedZone)), a, w)
        == w - declaredZone
{
}

// Z4b: a second pass over the emitted declaration keeps the zone the first pass
// pinned, whatever zone a later run resolves.
lemma ASecondPassKeepsTheZone(resolvedZone: int, laterZone: int, a: int, w: int)
  ensures Stored(TimestampInputDatatype(Just(resolvedZone), Just(laterZone)), a, w)
        == Stored(TimestampInputDatatype(Nothing, Just(resolvedZone)), a, w)
{
}

// ---- the measured vectors ----

// The dump's UTC text 2018-04-05 20:44:35.772929 and 2018-12-01 00:18:03.545683,
// in microseconds, under a zone-less declaration: America/Chicago summer (-05:00)
// and winter (-06:00) and Europe/London summer (+01:00). Measured on 24.8.14.
lemma MeasuredZoneLess()
  ensures Stored(ZoneLess, -18000000000, 1522961075772929) == 1522979075772929
  ensures Stored(ZoneLess, -21600000000, 1543623483545683) == 1543645083545683
  ensures Stored(ZoneLess, 3600000000, 1522961075772929) == 1522957475772929
  ensures Stored(ZoneLess, 0, 1543623483545683) == 1543623483545683
{
}

// The same text under the declaration the fix emits, the dump recording UTC:
// one instant under every ambient zone, and it is the instant the text names.
lemma MeasuredPinned()
  ensures Stored(TimestampInputDatatype(Nothing, Just(0)), -18000000000, 1522961075772929)
        == 1522961075772929
  ensures Stored(TimestampInputDatatype(Nothing, Just(0)), 3600000000, 1522961075772929)
        == 1522961075772929
  ensures Stored(TimestampInputDatatype(Nothing, Just(0)), -21600000000, 1543623483545683)
        == 1543623483545683
{
}

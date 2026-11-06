// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::TimestampConverter.instantEpochText
// spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java::executeInsertWithStructs
// request: Altinity/clickhouse-sink-connector pull request 1572, "Bind binlog history _time as the instant of source.ts_ms"
// spec: specs/12-replication-history/04-binlog-history-audit-table.md, property T1
//
// Property T1 in words. The history `_time` is bound from source.ts_ms, a true instant
// (milliseconds after the epoch). For every ts_ms and every column zone the bound text
// is read by ClickHouse as exactly that instant. It is epoch text
// "<seconds>.<6 fraction digits>" (zone-free) except for the one second before
// 1970-01-01 UTC, where ClickHouse drops the sign of the text ("-0.500000" reads
// as +0.5 s); that second is bound as wall digits in the column zone, which are
// exact there because the zone has no overlap hour at 1969-12-31 23:59:59.
//
// Model: time is an int count of milliseconds; a zone is its UTC offset at each
// instant. Same model as specs/lean/HistoryTimeInstant.lean.
// Run: dafny verify HistoryTimeInstant.dfy

datatype EpochText = EpochText(neg: bool, whole: nat, frac: nat)

function Abs(v: int): nat
{
  if v < 0 then -v else v
}

// BigDecimal.toPlainString of a value held in microseconds.
function Render(v: int): EpochText
{
  EpochText(v < 0, Abs(v) / 1000000, Abs(v) % 1000000)
}

// The number ClickHouse reads from epoch text, in microseconds. A leading minus on a
// text whose whole part is 0 is dropped (measured on 24.8.14).
function ChRead(t: EpochText): int
{
  if t.neg && t.whole == 0 then t.frac
  else if t.neg then -(t.whole * 1000000 + t.frac)
  else t.whole * 1000000 + t.frac
}

// Instant.ofEpochMilli(ms).getEpochSecond(): floor division.
function EpochSecond(ms: int): int
{
  ms / 1000
}

// Instant.ofEpochMilli(ms).getNano(): floor modulo, in nanoseconds.
function NanoOfSecond(ms: int): int
{
  (ms % 1000) * 1000000
}

// epochSecond + nano / 1e9 at scale 6, truncated, as a count of microseconds.
function Scaled(ms: int): int
{
  EpochSecond(ms) * 1000000 + NanoOfSecond(ms) / 1000
}

function EpochTextOf(ms: int): EpochText
{
  Render(Scaled(ms))
}

// DebeziumConverter.bindsAsEpochText(DateTime64, Instant.ofEpochMilli(ms))
predicate BindsAsEpochText(ms: int)
{
  !(EpochSecond(ms) == -1 && NanoOfSecond(ms) > 0)
}

// Six fraction digits hold every millisecond instant exactly: nothing is truncated.
lemma ScaledIsMsTimes1000(ms: int)
  ensures Scaled(ms) == ms * 1000
{
}

// What ClickHouse reads from the rendering of a microsecond count.
lemma ChReadRender(v: int)
  ensures ChRead(Render(v)) == (if -1000000 < v < 0 then -v else v)
{
}

// The digits fallback covers exactly the sub-second before the epoch.
lemma FallbackSet(ms: int)
  ensures !BindsAsEpochText(ms) <==> (-1000 < ms < 0)
{
}

// T1, epoch branch: whenever epoch text is bound, ClickHouse reads the instant back exactly.
lemma EpochTextExact(ms: int)
  requires BindsAsEpochText(ms)
  ensures ChRead(EpochTextOf(ms)) == ms * 1000
{
  ScaledIsMsTimes1000(ms);
  ChReadRender(Scaled(ms));
  FallbackSet(ms);
}

// The fallback fires exactly when epoch text would be misread.
lemma FallbackIffMisread(ms: int)
  ensures !BindsAsEpochText(ms) <==> ChRead(EpochTextOf(ms)) != ms * 1000
{
  ScaledIsMsTimes1000(ms);
  ChReadRender(Scaled(ms));
  FallbackSet(ms);
}

// The boundary instants.
lemma BoundaryPoints()
  ensures BindsAsEpochText(-1000) && !BindsAsEpochText(-999) && !BindsAsEpochText(-1) && BindsAsEpochText(0)
{
}

// The Java unit-test vectors: -2.5 s, the overlap instant, the epoch, -1 s.
lemma JavaTestVectors()
  ensures EpochTextOf(-2500) == EpochText(true, 2, 500000)
  ensures EpochTextOf(1793518200000) == EpochText(false, 1793518200, 0)
  ensures EpochTextOf(0) == EpochText(false, 0, 0)
  ensures EpochTextOf(-1000) == EpochText(true, 1, 0)
{
}

// ---- The digits fallback and the old rendering ----

type Zone = int -> int

function Wall(z: Zone, t: int): int
{
  t + z(t)
}

// ClickHouse resolves wall digits to the first instant that shows them.
ghost predicate ReadsAs(z: Zone, w: int, t: int)
{
  Wall(z, t) == w && forall s: int :: s < t ==> Wall(z, s) != w
}

// No earlier instant shows the same digits: the instant is not the second of an overlap.
ghost predicate Unambiguous(z: Zone, t: int)
{
  forall s: int :: s < t ==> Wall(z, s) != Wall(z, t)
}

function ConstZone(c: int): Zone
{
  (t: int) => c
}

lemma ConstZoneUnambiguous(c: int, t: int)
  ensures Unambiguous(ConstZone(c), t)
{
}

datatype Bound = Epoch(e: EpochText) | Digits(w: int)

function Bind(z: Zone, ms: int): Bound
{
  if BindsAsEpochText(ms) then Epoch(EpochTextOf(ms)) else Digits(Wall(z, ms))
}

ghost predicate ReadsInstant(z: Zone, b: Bound, ms: int)
{
  match b
  case Epoch(e) => ChRead(e) == ms * 1000
  case Digits(w) => ReadsAs(z, w, ms)
}

// T1: the bound _time is the instant, for every zone that is unambiguous where the digits fallback applies.
lemma T1(z: Zone, ms: int)
  requires BindsAsEpochText(ms) || Unambiguous(z, ms)
  ensures ReadsInstant(z, Bind(z, ms), ms)
{
  if BindsAsEpochText(ms) {
    EpochTextExact(ms);
  } else {
    assert Bind(z, ms) == Digits(Wall(z, ms));
    assert ReadsAs(z, Wall(z, ms), ms);
  }
}

// T1 in a zone of fixed offset (UTC; America/Chicago at 1969-12-31): every instant.
lemma T1ConstantZone(c: int, ms: int)
  ensures ReadsInstant(ConstZone(c), Bind(ConstZone(c), ms), ms)
{
  ConstZoneUnambiguous(c, ms);
  T1(ConstZone(c), ms);
}

// The old rendering: the instant's UTC digits read in a column zone of fixed offset c come back c later...
lemma LegacyShift(c: int, ms: int)
  ensures ReadsAs(ConstZone(c), Wall(ConstZone(0), ms), ms - c)
{
}

// ... which is a different instant whenever the column zone is not UTC.
lemma LegacyWrong(c: int, ms: int)
  requires c != 0
  ensures !ReadsAs(ConstZone(c), Wall(ConstZone(0), ms), ms)
{
}

// America/Chicago summer (UTC-05:00) and winter (UTC-06:00): 18000 s and 21600 s late.
lemma LegacyChicago(ms: int)
  ensures ReadsAs(ConstZone(-18000000), Wall(ConstZone(0), ms), ms + 18000000)
  ensures ReadsAs(ConstZone(-21600000), Wall(ConstZone(0), ms), ms + 21600000)
{
  LegacyShift(-18000000, ms);
  LegacyShift(-21600000, ms);
}

// America/Chicago on 2026-11-01: UTC-05:00 before 07:00 UTC, UTC-06:00 from then on.
function FallBack(): Zone
{
  (s: int) => if s < 1793516400000 then -18000000 else -21600000
}

// 07:30 UTC (the second 01:30 Chicago) shows the digits of 06:30 UTC, so digits cannot store it...
lemma OverlapSecondOccurrenceIsAmbiguous()
  ensures !Unambiguous(FallBack(), 1793518200000)
{
  assert Wall(FallBack(), 1793514600000) == Wall(FallBack(), 1793518200000);
}

// ... but epoch text stores it exactly.
lemma OverlapSecondOccurrenceEpochExact()
  ensures ChRead(EpochTextOf(1793518200000)) == 1793518200000 * 1000
{
  EpochTextExact(1793518200000);
}

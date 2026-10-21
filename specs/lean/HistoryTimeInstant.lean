/-!
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::TimestampConverter.instantEpochText
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java::executeInsertWithStructs
request: Altinity/clickhouse-sink-connector pull request 1572, "Bind binlog history _time as the instant of source.ts_ms"
spec: specs/12-replication-history/04-binlog-history-audit-table.md, property T1; specs/07-type-system/03-temporal-types.md section 3.1.4

Property T1 in words. The history `_time` is bound from `source.ts_ms`, which is a
true instant (milliseconds after the epoch), not a wall time. For every
`ts_ms` and every column zone, the text the connector binds is read by
ClickHouse as exactly that instant:

* it is epoch text `<seconds>.<6 fraction digits>`, which names the instant with no
  zone involved, in every case except the one second before 1970-01-01 UTC;
* ClickHouse drops the sign of an epoch text whose whole part is 0
  (measured on 24.8.14: '-0.500000' reads as +0.5 s), so exactly that second
  is bound as wall digits in the column zone, which are exact there because the
  zone has no overlap hour at 1969-12-31 23:59:59.

The file also proves why the old rendering was wrong: UTC digits read in a column
zone with offset c come back c later than the instant (+18000 s in America/Chicago
summer, +21600 s in winter), and why digits are not enough in general: the
second occurrence of a fall-back hour is stored as the first.

Model. Time is an `Int` count of milliseconds. A zone is its UTC offset at each
instant. Only Lean core is used; every proof is kernel-checked
(`omega`, `simp`, `decide`). Run: `lean HistoryTimeInstant.lean`.
-/

namespace HistoryTimeInstant

/-! ## The epoch text -/

/-- `<sign><whole seconds>.<6 fraction digits>`, the shape `epochText` renders. -/
structure EpochText where
  neg : Bool
  whole : Nat
  frac : Nat
  deriving DecidableEq, Repr

/-- The number ClickHouse reads from epoch text, in microseconds. A leading minus on
a text whose whole part is 0 is dropped (measured on 24.8.14: '-0.500000' reads as +0.5 s). -/
def chRead (t : EpochText) : Int :=
  if t.neg = true ∧ t.whole = 0 then (t.frac : Int)
  else if t.neg = true then -((t.whole : Int) * 1000000 + t.frac)
  else (t.whole : Int) * 1000000 + t.frac

/-- `BigDecimal.toPlainString` of a value held in microseconds: sign, whole seconds, 6 digits. -/
def render (v : Int) : EpochText :=
  { neg := decide (v < 0), whole := v.natAbs / 1000000, frac := v.natAbs % 1000000 }

/-- `Instant.ofEpochMilli(ms).getEpochSecond()`: floor division. -/
def epochSecond (ms : Int) : Int := ms / 1000

/-- `Instant.ofEpochMilli(ms).getNano()`: floor modulo, in nanoseconds. -/
def nanoOfSecond (ms : Int) : Int := (ms % 1000) * 1000000

/-- `epochSecond + nano / 1e9` at scale 6, truncated, as an integer count of microseconds. -/
def scaled (ms : Int) : Int := epochSecond ms * 1000000 + nanoOfSecond ms / 1000

/-- `DebeziumConverter.epochText(Instant.ofEpochMilli(ms))`. -/
def epochText (ms : Int) : EpochText := render (scaled ms)

/-- `DebeziumConverter.bindsAsEpochText(DateTime64, Instant.ofEpochMilli(ms))`. -/
def bindsAsEpochText (ms : Int) : Bool :=
  !(decide (epochSecond ms = -1) && decide (nanoOfSecond ms > 0))

/-- Six fraction digits hold every millisecond instant exactly: nothing is truncated. -/
theorem scaled_eq (ms : Int) : scaled ms = ms * 1000 := by
  unfold scaled epochSecond nanoOfSecond
  omega

/-- What ClickHouse reads from the rendering of a microsecond count. -/
theorem chRead_render (v : Int) :
    chRead (render v) = if -1000000 < v ∧ v < 0 then -v else v := by
  unfold chRead render
  by_cases h : v < 0
  · by_cases h2 : -1000000 < v
    · have hw : v.natAbs / 1000000 = 0 := by omega
      have hf : (v.natAbs % 1000000 : Nat) = v.natAbs := by omega
      simp [h, h2, hw, hf]
      omega
    · have hw : v.natAbs / 1000000 ≠ 0 := by omega
      simp [h, h2, hw]
      omega
  · have h' : ¬ (-1000000 < v ∧ v < 0) := by omega
    simp [h, h']
    omega

/-- The binding is exactly the instants where epoch text is not the digits fallback. -/
theorem fallback_set (ms : Int) : bindsAsEpochText ms = false ↔ (-1000 < ms ∧ ms < 0) := by
  unfold bindsAsEpochText epochSecond nanoOfSecond
  simp only [Bool.not_eq_false', Bool.and_eq_true, decide_eq_true_eq]
  omega

/-- T1, epoch branch: whenever the connector binds epoch text, ClickHouse reads the instant back exactly. -/
theorem epoch_text_exact (ms : Int) (h : bindsAsEpochText ms = true) :
    chRead (epochText ms) = ms * 1000 := by
  have hf : ¬ (-1000 < ms ∧ ms < 0) := by
    intro hin
    have := (fallback_set ms).mpr hin
    rw [this] at h
    exact Bool.false_ne_true h
  unfold epochText
  rw [chRead_render, scaled_eq]
  split <;> omega

/-- The digits fallback fires exactly when epoch text would be misread: neither too often nor too rarely. -/
theorem fallback_iff_misread (ms : Int) :
    bindsAsEpochText ms = false ↔ chRead (epochText ms) ≠ ms * 1000 := by
  unfold epochText
  rw [chRead_render, scaled_eq, fallback_set]
  split <;> omega

/-- The boundary instants: -1000 ms is a whole second and binds as epoch text; -999..-1 ms fall back. -/
theorem boundary_points :
    bindsAsEpochText (-1000) = true ∧ bindsAsEpochText (-999) = false ∧
    bindsAsEpochText (-1) = false ∧ bindsAsEpochText 0 = true := by
  decide

/-- The rendering of the Java unit-test vectors: -2.5 s and the overlap instant. -/
theorem java_test_vectors :
    epochText (-2500) = ⟨true, 2, 500000⟩ ∧
    epochText 1793518200000 = ⟨false, 1793518200, 0⟩ ∧
    epochText 0 = ⟨false, 0, 0⟩ ∧
    epochText (-1000) = ⟨true, 1, 0⟩ := by
  decide

/-! ## The digits fallback and the old rendering -/

/-- A zone: its offset from UTC, in milliseconds, at each instant. -/
abbrev Zone := Int → Int

/-- The wall-clock reading of an instant in a zone. -/
def wall (z : Zone) (t : Int) : Int := t + z t

/-- ClickHouse resolves wall digits to the first instant that shows them. -/
def ReadsAs (z : Zone) (w t : Int) : Prop :=
  wall z t = w ∧ ∀ s, s < t → wall z s ≠ w

/-- No earlier instant shows the same digits: the instant is not the second of an overlap. -/
def Unambiguous (z : Zone) (t : Int) : Prop :=
  ∀ s, s < t → wall z s ≠ wall z t

theorem digits_exact (z : Zone) (t : Int) (h : Unambiguous z t) : ReadsAs z (wall z t) t :=
  ⟨rfl, h⟩

/-- A zone with one fixed offset, such as UTC or America/Chicago around 1969-12-31. -/
def constZone (c : Int) : Zone := fun _ => c

theorem constZone_unambiguous (c t : Int) : Unambiguous (constZone c) t := by
  intro s hs
  simp only [wall, constZone]
  omega

/-- What the connector binds for an instant: epoch text, or digits in the column zone. -/
inductive Bound where
  | epoch (e : EpochText)
  | digits (w : Int)

def bind (z : Zone) (ms : Int) : Bound :=
  if bindsAsEpochText ms = true then .epoch (epochText ms) else .digits (wall z ms)

/-- What ClickHouse stores for a bound value, compared with the instant. -/
def ReadsInstant (z : Zone) (b : Bound) (ms : Int) : Prop :=
  match b with
  | .epoch e => chRead e = ms * 1000
  | .digits w => ReadsAs z w ms

/-- T1: the bound `_time` is the instant, for every zone that is unambiguous where the digits fallback applies. -/
theorem t1 (z : Zone) (ms : Int)
    (h : bindsAsEpochText ms = true ∨ Unambiguous z ms) : ReadsInstant z (bind z ms) ms := by
  by_cases hb : bindsAsEpochText ms = true
  · simp only [bind, hb, ↓reduceIte, ReadsInstant]
    exact epoch_text_exact ms hb
  · have hu : Unambiguous z ms := h.resolve_left hb
    simp only [bind, hb, ↓reduceIte, ReadsInstant]
    exact digits_exact z ms hu

/-- T1 in a zone of fixed offset (UTC; America/Chicago at 1969-12-31 is -06:00 with no transition): every instant. -/
theorem t1_constant_zone (c ms : Int) : ReadsInstant (constZone c) (bind (constZone c) ms) ms :=
  t1 (constZone c) ms (Or.inr (constZone_unambiguous c ms))

/-- The old rendering: the instant's UTC digits, read in a column zone of fixed offset c, come back c later... -/
theorem legacy_shift (c ms : Int) : ReadsAs (constZone c) (wall (constZone 0) ms) (ms - c) := by
  refine ⟨?_, ?_⟩
  · simp only [wall, constZone]
    omega
  · intro s hs
    simp only [wall, constZone]
    omega

/-- ... which is a different instant whenever the column zone is not UTC. -/
theorem legacy_wrong (c ms : Int) (hc : c ≠ 0) : ¬ ReadsAs (constZone c) (wall (constZone 0) ms) ms := by
  intro h
  have h1 := h.1
  simp only [wall, constZone] at h1
  omega

/-- America/Chicago in summer (UTC-05:00): the old `_time` is 18000 s late. -/
theorem legacy_chicago_summer (ms : Int) :
    ReadsAs (constZone (-18000000)) (wall (constZone 0) ms) (ms + 18000000) := by
  have h := legacy_shift (-18000000) ms
  simpa using h

/-- America/Chicago in winter (UTC-06:00): the old `_time` is 21600 s late. -/
theorem legacy_chicago_winter (ms : Int) :
    ReadsAs (constZone (-21600000)) (wall (constZone 0) ms) (ms + 21600000) := by
  have h := legacy_shift (-21600000) ms
  simpa using h

/-- America/Chicago on 2026-11-01: UTC-05:00 before 07:00 UTC, UTC-06:00 from then on. -/
def fallBack : Zone := fun s => if s < 1793516400000 then -18000000 else -21600000

/-- 07:30 UTC (the second 01:30 Chicago) shows the digits of 06:30 UTC, so digits cannot store it. -/
theorem overlap_second_occurrence_is_ambiguous : ¬ Unambiguous fallBack 1793518200000 := by
  intro h
  exact h 1793514600000 (by decide) (by simp [wall, fallBack])

/-- The same instant is stored exactly as epoch text. -/
theorem overlap_second_occurrence_epoch_exact :
    chRead (epochText 1793518200000) = 1793518200000 * 1000 :=
  epoch_text_exact _ (by decide)

end HistoryTimeInstant

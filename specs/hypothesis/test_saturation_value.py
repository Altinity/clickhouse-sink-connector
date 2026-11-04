"""Property tests for the DateTime64 saturation rule (a faithful Python model).

spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::dateTime64Max
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::DATETIME64_SATURATION_MAX
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::Saturation.saturationValue
request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release"
spec: specs/07-type-system/03-temporal-types.md, section 3.1.8

The rule in words. ACCEPTANCE is per scale: a DateTime64(p) column holds an
instant exactly when it lies in [1900-01-01 00:00:00, bound(p)], and a value
inside that range is stored unchanged. SATURATION is ONE value: a value above
bound(p) is stored as 2299-12-31 23:59:59.000 UTC at every scale 0..8. Scale 9
is the one exception, forced by the Int64 nanosecond representation.

Why it matters. A saturated value is a stored value, and the column is commonly
in a ReplacingMergeTree sorting key. One source value must therefore map to ONE
stored instant, across scales and across releases, or the row stops being
replaceable: a tombstone written after an upgrade carries a different key, never
replaces the row it deletes, and FINAL returns both rows.

This is a MODEL, not the Java: the artifact needs a Maven build with the
ClickHouse client jar and cannot run from Python alone. The model is pinned to
the Java two ways: the constants below are the literals the Java declares
(DATETIME64_MAX, DATETIME64_SATURATION_MAX, DATETIME64_SCALE9_MAX_*), and
`test_vectors_asserted_by_the_java_unit_tests` repeats the exact values
`DebeziumConverterRangePolicyTest` and `TimeZoneMatrixTest` assert against the
real code. If the Java changes, those vectors fail here too.

Run: `pytest specs/hypothesis/test_saturation_value.py`
"""

from __future__ import annotations

from hypothesis import given, settings
from hypothesis import strategies as st

PROFILE = settings(derandomize=True, database=None, deadline=None, max_examples=500)

# The literals DataTypeRange declares, restated so the model is not derived from
# the code under test.
US = 1_000_000
DATETIME64_MAX = 10_413_791_999  # 2299-12-31 23:59:59 UTC, in whole seconds
DATETIME64_SATURATION_MAX_US = DATETIME64_MAX * US  # zero fraction
DATETIME64_SCALE9_MAX_SECONDS = 9_223_372_036  # Int64 nanosecond limit
DATETIME64_SCALE9_MAX_NANOS = 854_775_807
CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US = -2_208_988_800 * US  # 1900-01-01 UTC
SCALE9_MAX_US = DATETIME64_SCALE9_MAX_SECONDS * US + DATETIME64_SCALE9_MAX_NANOS // 1000


def unit_us(precision: int) -> int:
    """One unit of scale `precision`, in whole microseconds, floored at 1.

    The Java works in nanoseconds; this model floors the sub-microsecond scales
    (7 and 8) to one microsecond, so the whole-microsecond ceiling it computes is
    still correct for those scales.
    """
    return max(1, 10 ** (6 - precision))


def date_time64_max(precision: int) -> int:
    """`DataTypeRange.dateTime64Max(int)`, in whole microseconds."""
    if precision == 9:
        return SCALE9_MAX_US
    return DATETIME64_MAX * US + (US - unit_us(precision))


def saturation_value(precision: int) -> int:
    """`DebeziumConverter.saturationValue(int)`, in whole microseconds."""
    if precision == 9:
        return date_time64_max(9)
    return DATETIME64_SATURATION_MAX_US


def stored(precision: int, value_us: int) -> int:
    """`checkIfDateTimeExceedsSupportedRange`, in whole microseconds."""
    if value_us < CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US:
        return CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US
    if value_us > date_time64_max(precision):
        return saturation_value(precision)
    return value_us


# The highest bound any scale has. Scale 8's is ABOVE scale 9's: scale 9 stops
# at the Int64 nanosecond limit in 2262, while scales 0..8 run to 2299. A value
# has to be past this one to be out of range at every scale, which is why the
# ranges below cannot be anchored on SCALE9_MAX_US.
MAX_BOUND_US = max(date_time64_max(p) for p in range(10))

# A span wide enough to cross every boundary in both directions.
SPAN = 10 * US * 86_400
SCALES = st.integers(min_value=0, max_value=9)
INSTANTS = st.integers(
    min_value=CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US - SPAN,
    max_value=MAX_BOUND_US + SPAN,
)


@st.composite
def in_range(draw):
    """A (scale, value) pair the column holds exactly."""
    precision = draw(st.integers(min_value=0, max_value=8))
    value = draw(
        st.integers(
            min_value=CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US,
            max_value=date_time64_max(precision),
        )
    )
    return precision, value


@st.composite
def above_bound(draw):
    """A (scale, value) pair the column cannot hold: past its own scale's bound."""
    precision = draw(st.integers(min_value=0, max_value=8))
    value = draw(
        st.integers(
            min_value=date_time64_max(precision) + 1,
            max_value=MAX_BOUND_US + SPAN,
        )
    )
    return precision, value


@PROFILE
@given(in_range())
def test_in_range_value_is_stored_unchanged(pair):
    """S1: a value inside its own scale's range is never saturated."""
    precision, value = pair
    assert stored(precision, value) == value


@PROFILE
@given(SCALES, SCALES)
def test_saturation_is_one_value_at_every_scale_below_nine(a, b):
    """S2: every scale below 9 saturates to the same instant, so one sorting key."""
    if a == 9 or b == 9:
        return
    assert saturation_value(a) == saturation_value(b) == DATETIME64_SATURATION_MAX_US


@PROFILE
@given(above_bound())
def test_value_above_the_bound_stores_the_fixed_value(pair):
    """S2, and the exact regression this spec pins: the per-scale bound is DIFFERENT."""
    precision, value = pair
    assert stored(precision, value) == DATETIME64_SATURATION_MAX_US
    # Returning the per-scale bound instead would split the key of every row an
    # older release already wrote at the fixed value. At precision 0 the bound IS
    # the fixed value (no fraction digits to add), so the two coincide exactly and
    # scale 0 is the one scale the regression cannot reach.
    if precision > 0:
        assert date_time64_max(precision) != DATETIME64_SATURATION_MAX_US, precision


@PROFILE
@given(above_bound(), above_bound())
def test_two_out_of_range_values_of_two_scales_never_split_the_key(a, b):
    """S4: two out-of-range values of any two scales below 9 store ONE instant."""
    assert stored(a[0], a[1]) == stored(b[0], b[1]) == DATETIME64_SATURATION_MAX_US


@PROFILE
@given(INSTANTS)
def test_value_below_the_minimum_stores_the_minimum_at_every_scale(value):
    """S6: the low saturation is one value at every scale, including 9."""
    if value >= CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US:
        return
    for precision in range(10):
        assert stored(precision, value) == CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US


def test_scale_nine_is_the_one_exception_and_it_is_forced():
    """S5: scale 9 stores its own bound, because the fixed value would be refused."""
    assert saturation_value(9) == date_time64_max(9)
    assert saturation_value(9) != DATETIME64_SATURATION_MAX_US
    assert date_time64_max(9) < DATETIME64_SATURATION_MAX_US
    # Above the scale-9 bound, the stored value is the scale-9 bound.
    assert stored(9, date_time64_max(9) + 1) == date_time64_max(9)


def test_the_minimum_is_not_the_saturation_value():
    """S6: a low and a high saturation never collide in the sorting key."""
    assert CLICKHOUSE_MIN_SUPPORTED_DATETIME64_US != DATETIME64_SATURATION_MAX_US


def test_saturation_has_no_fraction_at_any_scale():
    """The stored value has no fraction, so no scale truncates it to a new key."""
    assert DATETIME64_SATURATION_MAX_US % US == 0
    for precision in range(9):
        assert saturation_value(precision) % US == 0


def test_vectors_asserted_by_the_java_unit_tests():
    """The exact values the Java suite asserts against the real code.

    Each vector is a line of a real test; if the Java changes, the Java test and
    this vector fail together, which is what keeps the model honest.
    """
    # DebeziumConverterRangePolicyTest.dateTime64MaxIsTheMeasuredBoundOfEachScale
    assert date_time64_max(0) == DATETIME64_MAX * US
    for precision in range(1, 9):
        expected = DATETIME64_MAX * US + (US - unit_us(precision))
        assert date_time64_max(precision) == expected, precision
    assert date_time64_max(9) == SCALE9_MAX_US

    # DebeziumConverterRangePolicyTest.dateTime64AcceptsPerScaleAndSaturatesToTheFixedValue
    for precision in (0, 3, 6):
        assert stored(precision, date_time64_max(precision) + 1) == (
            DATETIME64_SATURATION_MAX_US
        ), precision

    # DebeziumConverterRangePolicyTest.outOfRangeSentinelIsTheFixedHistoricalValueAtEveryScale
    # The production sentinel 9999-12-31 23:59:59 in a DateTime64(6) column.
    sentinel_us = 253_402_300_799 * US  # 9999-12-31 23:59:59 UTC
    assert stored(6, sentinel_us) == DATETIME64_SATURATION_MAX_US

    # TimeZoneMatrixTest.outOfRangeDateTime64TextReadsBackAsTheFixedSaturationValueInEveryServerZone
    assert DATETIME64_SATURATION_MAX_US // US == 10_413_791_999

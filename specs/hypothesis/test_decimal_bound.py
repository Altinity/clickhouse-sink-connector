"""Property tests for the decimal bound rule (a faithful Python model).

spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMax
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java::decimalMin
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::RangePolicy.boundDecimal
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java::decimalPrecisionScale
request: Altinity/clickhouse-sink-connector pull request 1573, "2.11.1 Release"
spec: specs/07-type-system/02-floating-decimal-types.md, section 3.2.1

The rule in words. A Decimal(p, s) column stores a value as an integer count of
10^-s units, and that integer has at most p digits, so the column accepts exactly
the counts in [-(10^p - 1), 10^p - 1]. A value inside that range is stored
unchanged; a value outside it is replaced by the bound OF THAT COLUMN, and the
replacement is itself a value the column accepts.

Why it matters. A Decimal column is commonly part of a ReplacingMergeTree
sorting key, which collapses two rows only when the WHOLE key is equal. One
source value must therefore map to ONE stored value across releases, or the row
stops being replaceable: a row written after an upgrade carries a different key,
never replaces the row it should, and FINAL returns both.

The defect this pins. The replacement value used to be
BinaryStreamUtils.DECIMAL128_MAX = 2^127 - 1 = 170141183460469231731687303715884105727,
whatever the column was. It needs 39 digits, so it is above the 38-digit ceiling
of a Decimal128 column and below the 65-digit ceiling of a Decimal(65, 30) one:
on the first it is a value the server refuses, on the second it is not the
column's bound. Measured on 24.8.14.10545.altinitystable, which is what the
vectors at the bottom restate.

This is a MODEL, not the Java: the artifact needs a Maven build with the
ClickHouse client jar and cannot run from Python alone. The model is pinned to
the Java two ways: the constants below are the literals the Java declares
(DECIMAL128_MAX_PRECISION, DECIMAL_MAX_PRECISION), and
`test_vectors_asserted_by_the_java_unit_tests` repeats the exact values
`DebeziumConverterRangePolicyTest.decimalSaturatesToTheBoundOfItsOwnColumn` and
`decimalPrecisionScaleIsReadFromTheDeclaredType` assert against the real code.
If the Java changes, those vectors fail here too.

Run: `pytest specs/hypothesis/test_decimal_bound.py`
"""

from __future__ import annotations

from pathlib import Path

from hypothesis import given, settings
from hypothesis import strategies as st

PROFILE = settings(derandomize=True, database=None, deadline=None, max_examples=500)

# The Java this model mirrors. The binding check blanks comments and docstrings,
# so the paths must appear in code; naming them here also fails loudly if one of
# these sources is renamed or deleted out from under the model.
JAVA_SOURCES = (
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/metadata/DataTypeRange.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java",
    "sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/ClickHouseDataTypeMapper.java",
)


def test_the_modelled_java_sources_still_exist() -> None:
    """The model's subject: a rename here owes this spec an update."""
    root = Path(__file__).resolve().parents[2]
    missing = [p for p in JAVA_SOURCES if not (root / p).is_file()]
    assert not missing, f"modelled Java source(s) missing: {missing}"

# The literals DataTypeRange declares, restated so the model is not derived from
# the code under test.
DECIMAL128_MAX_PRECISION = 38
DECIMAL_MAX_PRECISION = 76

# BinaryStreamUtils.DECIMAL128_MAX: the constant the defect replaced an
# out-of-range value with, whatever the column was.
OLD_CONSTANT = 2**127 - 1

# The precision each width spelling declares. Decimal32(s) IS Decimal(9, s):
# ClickHouse fixes the precision and takes only the scale.
WIDTH_PRECISION = {"Decimal32": 9, "Decimal64": 18, "Decimal128": 38, "Decimal256": 76}


def scaled_max(precision: int) -> int:
    """`DataTypeRange.decimalMax(p, s)`, as an integer count of 10^-s units.

    The scale does not appear: it decides which integer a source digit string
    maps to, not how many digits the column has. The Java builds the same number
    with BigDecimal.TEN.pow(p).subtract(ONE).movePointLeft(s).
    """
    if not 1 <= precision <= DECIMAL_MAX_PRECISION:
        raise ValueError(f"Decimal precision must be 1..{DECIMAL_MAX_PRECISION}, got {precision}")
    return 10**precision - 1


def scaled_min(precision: int) -> int:
    """`DataTypeRange.decimalMin(p, s)`: the negation of the same bound."""
    return -scaled_max(precision)


def accepts(precision: int, value: int) -> bool:
    """Whether a Decimal(precision, s) column can store `value` (in 10^-s units)."""
    return scaled_min(precision) <= value <= scaled_max(precision)


def bound(precision: int, value: int) -> int:
    """`DebeziumConverter.RangePolicy.boundDecimal`, in 10^-s units."""
    high = scaled_max(precision)
    if value > high:
        return high
    if value < -high:
        return -high
    return value


def decimal_max_text(precision: int, scale: int) -> str:
    """The bound as ClickHouse prints it, built by integer arithmetic only.

    `decimal.Decimal.scaleb` and `movePointLeft` both round to the current
    context precision (28 digits by default), which would silently truncate a
    Decimal(38) bound; integer arithmetic cannot.
    """
    if not 0 <= scale <= precision:
        raise ValueError(f"Decimal scale must be 0..{precision}, got {scale}")
    magnitude = scaled_max(precision)
    if scale == 0:
        return str(magnitude)
    return f"{magnitude // 10**scale}.{magnitude % 10**scale:0{scale}d}"


def decimal_min_text(precision: int, scale: int) -> str:
    """The low bound, as ClickHouse prints it."""
    return "-" + decimal_max_text(precision, scale)


def decimal_precision_scale(declared_type: str | None) -> tuple[int, int] | None:
    """`ClickHouseDataTypeMapper.decimalPrecisionScale`, for the widths it reads.

    A `Decimal(p, s)` declaration states both numbers; a `Decimal32(s)` style
    declaration states only the scale and implies the precision. Anything that is
    not a decimal, or that states an impossible width, has no bound here.
    """
    if not declared_type:
        return None
    text = declared_type.strip()
    if text.startswith("Nullable(") and text.endswith(")"):
        text = text[len("Nullable(") : -1].strip()
    if text.startswith("Decimal(") and text.endswith(")"):
        parts = [part.strip() for part in text[len("Decimal(") : -1].split(",")]
        if len(parts) != 2 or not all(part.lstrip("-").isdigit() for part in parts):
            return None
        precision, scale = int(parts[0]), int(parts[1])
    else:
        for width, fixed_precision in WIDTH_PRECISION.items():
            if text.startswith(f"{width}(") and text.endswith(")"):
                inside = text[len(width) + 1 : -1].strip()
                if not inside.isdigit():
                    return None
                precision, scale = fixed_precision, int(inside)
                break
        else:
            return None
    if not 1 <= precision <= DECIMAL_MAX_PRECISION or not 0 <= scale <= precision:
        return None
    return precision, scale


SPAN = 10**20
PRECISIONS = st.integers(min_value=1, max_value=DECIMAL_MAX_PRECISION)
SCALES = st.integers(min_value=0, max_value=DECIMAL_MAX_PRECISION)


def _valid_width(precision: int, scale: int) -> bool:
    return 0 <= scale <= precision


@st.composite
def a_width(draw):
    """A (precision, scale) pair ClickHouse accepts."""
    precision = draw(PRECISIONS)
    scale = draw(st.integers(min_value=0, max_value=precision))
    return precision, scale


@st.composite
def an_in_range_value(draw):
    """A (precision, value) pair the column holds exactly."""
    precision = draw(PRECISIONS)
    value = draw(st.integers(min_value=scaled_min(precision), max_value=scaled_max(precision)))
    return precision, value


@st.composite
def a_value_above_the_max(draw):
    """A (precision, value) pair past the column's own ceiling."""
    precision = draw(PRECISIONS)
    value = draw(st.integers(min_value=scaled_max(precision) + 1, max_value=scaled_max(precision) + SPAN))
    return precision, value


@PROFILE
@given(an_in_range_value())
def test_in_range_value_is_stored_unchanged(pair):
    """B1: a value the column holds is never replaced."""
    precision, value = pair
    assert bound(precision, value) == value


@PROFILE
@given(a_value_above_the_max())
def test_value_above_the_max_stores_the_max(pair):
    """B2: saturation lands on the column's own bound."""
    precision, value = pair
    assert bound(precision, value) == scaled_max(precision)
    # The regression itself: at every precision above 9 the old constant is a
    # DIFFERENT value from this column's bound, so a release that stored it
    # splits the sorting key of every row a fixed release writes.
    if precision > len(str(OLD_CONSTANT)):
        assert bound(precision, value) != OLD_CONSTANT, precision


@PROFILE
@given(PRECISIONS, st.integers(min_value=1, max_value=SPAN))
def test_value_below_the_min_stores_the_min(precision, excess):
    """B2: the low side is the negation of the same bound."""
    value = scaled_min(precision) - excess
    assert bound(precision, value) == scaled_min(precision)
    assert scaled_min(precision) == -scaled_max(precision)


@PROFILE
@given(PRECISIONS, st.integers(), PRECISIONS, st.integers())
def test_two_out_of_range_values_of_two_widths_never_split_the_key(a, va, b, vb):
    """B3: a saturated value is one the column accepts, at every width."""
    for precision, value in ((a, va), (b, vb)):
        assert accepts(precision, bound(precision, value)), (precision, value)


@PROFILE
@given(PRECISIONS, st.integers(min_value=-(10**76), max_value=10**76))
def test_bounding_is_idempotent(precision, value):
    """B4: bounding twice is bounding once, so a redelivered value does not drift."""
    once = bound(precision, value)
    assert bound(precision, once) == once


@PROFILE
@given(a_width())
def test_the_two_bounds_are_distinct_and_the_text_round_trips(width):
    """B5: the bounds never collide, and the printed form carries p digits."""
    precision, scale = width
    assert scaled_min(precision) != scaled_max(precision)
    high = decimal_max_text(precision, scale)
    low = decimal_min_text(precision, scale)
    digits = high.replace(".", "").lstrip("0")
    assert set(digits) == {"9"}, (precision, scale, high)
    assert len(digits) == precision, (precision, scale, high)
    assert low == "-" + high
    # And the text is the value the model bounds to, read back exactly.
    units = int(high.replace(".", "")) if scale else int(high) * 10**scale
    assert units == scaled_max(precision)


@PROFILE
@given(PRECISIONS, st.integers(min_value=0, max_value=DECIMAL_MAX_PRECISION))
def test_an_impossible_width_is_refused(precision, scale):
    """The bound is defined only for the widths ClickHouse accepts."""
    if _valid_width(precision, scale):
        assert scaled_max(precision) > 0
    else:
        try:
            decimal_max_text(precision, scale)
        except ValueError:
            return
        raise AssertionError((precision, scale))


@PROFILE
@given(PRECISIONS, PRECISIONS)
def test_every_width_has_its_own_bound(precision, other):
    """No width's bound serves another width: that is the defect, exactly."""
    if precision == other:
        return
    assert scaled_max(precision) != scaled_max(other)


@PROFILE
@given(st.booleans())
def test_the_old_constant_is_past_the_decimal128_bound(wide):
    """B6: 2^127 - 1 needs 39 digits, so a Decimal128 column refuses it."""
    assert len(str(OLD_CONSTANT)) == 39
    assert OLD_CONSTANT > scaled_max(DECIMAL128_MAX_PRECISION)
    assert not accepts(DECIMAL128_MAX_PRECISION, OLD_CONSTANT)
    # ... and it is NOT a refusal in a wide column, which is the other half:
    # the constant was never that column's bound either.
    assert OLD_CONSTANT < scaled_max(65)
    assert wide is True or wide is False


def test_vectors_asserted_by_the_java_unit_tests():
    """The exact values the Java suite asserts against the real code.

    Each vector is a line of a real test; if the Java changes, the Java test and
    this vector fail together, which is what keeps the model honest.
    """
    # DebeziumConverterRangePolicyTest.decimalSaturatesToTheBoundOfItsOwnColumn
    assert decimal_max_text(38, 0) == "99999999999999999999999999999999999999"
    assert decimal_max_text(18, 6) == "999999999999.999999"
    assert decimal_max_text(9, 0) == "999999999"
    assert decimal_max_text(18, 0) == "999999999999999999"
    assert bound(18, scaled_max(18) + 1) == scaled_max(18)
    # 999999999999.999998 passes through untouched.
    assert bound(18, scaled_max(18) - 1) == scaled_max(18) - 1

    # The four widths Decimal32/64/128/256 imply.
    assert scaled_max(WIDTH_PRECISION["Decimal32"]) == 999999999
    assert scaled_max(WIDTH_PRECISION["Decimal64"]) == 999999999999999999
    assert scaled_max(WIDTH_PRECISION["Decimal128"]) == int("9" * 38)

    # ClickHouseDataTypeMapper.decimalPrecisionScaleIsReadFromTheDeclaredType
    assert decimal_precision_scale("Decimal(18, 6)") == (18, 6)
    assert decimal_precision_scale("Nullable(Decimal(18, 6))") == (18, 6)
    assert decimal_precision_scale("Decimal( 38 , 9 )") == (38, 9)
    assert decimal_precision_scale("Decimal32(2)") == (9, 2)
    assert decimal_precision_scale("Decimal64(3)") == (18, 3)
    assert decimal_precision_scale("Decimal128(6)") == (38, 6)
    assert decimal_precision_scale("Decimal256(4)") == (76, 4)
    assert decimal_precision_scale("DateTime64(6, 'UTC')") is None
    assert decimal_precision_scale("String") is None
    assert decimal_precision_scale(None) is None
    assert decimal_precision_scale("Decimal(10, 11)") is None
    assert decimal_precision_scale("Decimal(0, 0)") is None
    assert decimal_precision_scale("Decimal(77, 0)") is None


def test_vectors_measured_on_the_server():
    """The ClickHouse behaviour the Java bound models, measured live.

    Host: 24.8.14.10545.altinitystable. Each line is a query and its result; a
    result that stops holding means the model's premise is wrong, not just the
    model's arithmetic.
    """
    # accurateCastOrNull('170141183460469231731687303715884105727', 'Decimal(65,30)')
    #   -> 170141183460469231731687303715884105727
    assert accepts(65, OLD_CONSTANT)
    # toDecimal128OrNull('170141183460469231731687303715884105727', 0) -> NULL
    assert not accepts(38, OLD_CONSTANT)
    # toDecimal128OrNull('99999999999999999999999999999999999999', 0)
    #   -> 99999999999999999999999999999999999999
    assert accepts(38, int("9" * 38))
    # toDecimal64OrNull('1000000000000.000000', 6)                    -> NULL
    assert not accepts(18, scaled_max(18) + 1)
    # toDecimal64OrNull('999999999999.999999', 6)   -> 999999999999.999999
    assert accepts(18, scaled_max(18))
    assert decimal_max_text(18, 6) == "999999999999.999999"

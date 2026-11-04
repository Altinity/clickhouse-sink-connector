"""Property tests for the binlog history `_time` binding (a faithful Python model).

spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/converters/DebeziumConverter.java::TimestampConverter.instantEpochText
spec-for: sink-connector/src/main/java/com/altinity/clickhouse/sink/connector/history/BinLogHistory.java::executeInsertWithStructs
request: Altinity/clickhouse-sink-connector pull request 1572, "Bind binlog history _time as the instant of source.ts_ms"
spec: specs/12-replication-history/04-binlog-history-audit-table.md, property T1

Property T1 in words. The history `_time` is bound from `source.ts_ms`, a true
instant. For every `ts_ms` and every column zone the bound text is read by
ClickHouse as exactly that instant. It is epoch text `<seconds>.<6 fraction
digits>` (it names no zone) except for the one second before 1970-01-01 UTC,
where ClickHouse drops the sign of the text ('-0.500000' reads as +0.5 s):
that second is bound as wall digits in the column zone, exact there because
the zone has no overlap hour at 1969-12-31 23:59:59.

This is a MODEL, not the Java: the artifact needs a Maven build with the
ClickHouse client jar and cannot run from Python alone. The model is pinned to
the Java in two ways: `test_vectors_asserted_by_the_java_unit_tests` repeats the
values `DebeziumConverterTest` asserts against the real code, and the reader
(`ch_read`) encodes only behaviour measured on ClickHouse 24.8.14 and described
in specs/07-type-system/03-temporal-types.md section 3.1.4. Zone rules come from
the system tz database through `zoneinfo`: real transitions, not invented ones.

Run: `pytest specs/hypothesis/test_history_time_instant.py`
"""

from __future__ import annotations

import datetime as dt
from decimal import ROUND_DOWN, Decimal
from zoneinfo import ZoneInfo

from hypothesis import assume, given, settings
from hypothesis import strategies as st

PROFILE = settings(derandomize=True, database=None, deadline=None, max_examples=300)

EPOCH = dt.datetime(1970, 1, 1, tzinfo=dt.timezone.utc)
UTC_ZONE = ZoneInfo("UTC")
ZONE_NAMES = [
    "UTC",
    "America/Chicago",
    "America/New_York",
    "America/St_Johns",
    "Europe/London",
    "Asia/Kolkata",
    "Australia/Lord_Howe",
    "Pacific/Chatham",
]

# ---------------------------------------------------------------- Java model


def binds_as_epoch_text(epoch_second: int, nano: int) -> bool:
    """DebeziumConverter.bindsAsEpochText(DateTime64, instant)."""
    return not (epoch_second == -1 and nano > 0)


def epoch_text(epoch_second: int, nano: int) -> str:
    """DebeziumConverter.epochText: BigDecimal, scale 6, RoundingMode.DOWN, plain string."""
    value = Decimal(epoch_second) + Decimal(nano).scaleb(-9)
    return format(value.quantize(Decimal("0.000001"), rounding=ROUND_DOWN), "f")


def digits(ms: int, zone: ZoneInfo) -> str:
    """instant.atZone(zone).format("yyyy-MM-dd HH:mm:ss.SSS")."""
    local = (EPOCH + dt.timedelta(milliseconds=ms)).astimezone(zone)
    return local.strftime("%Y-%m-%d %H:%M:%S") + ".%03d" % (local.microsecond // 1000)


def instant_epoch_text(ms: int, zone: ZoneInfo) -> str:
    """TimestampConverter.instantEpochText(epochMillis, columnZone)."""
    epoch_second, milli = divmod(ms, 1000)  # Instant.ofEpochMilli: floorDiv / floorMod
    nano = milli * 1_000_000
    if not binds_as_epoch_text(epoch_second, nano):
        return digits(ms, zone)
    return epoch_text(epoch_second, nano)


# ------------------------------------------------------------ ClickHouse model


def ch_read(text: str, zone: ZoneInfo) -> int:
    """Microseconds since the epoch that a DateTime64 column of `zone` stores for `text`."""
    if " " not in text:  # epoch text
        value = Decimal(text)
        if text.startswith("-") and abs(value) < 1:
            value = abs(value)  # measured on 24.8.14: the sign of -0.5 is dropped
        return int(value * 1_000_000)
    naive = dt.datetime.strptime(text, "%Y-%m-%d %H:%M:%S.%f")
    # Wall digits resolve to the first instant that shows them (fold=0).
    aware = naive.replace(tzinfo=zone, fold=0)
    return (aware - EPOCH) // dt.timedelta(microseconds=1)


# --------------------------------------------------------------- zone helpers


def offset_seconds(zone: ZoneInfo, utc_second: int) -> int:
    local = (EPOCH + dt.timedelta(seconds=utc_second)).astimezone(zone)
    return int(local.utcoffset().total_seconds())


def offset_ms(zone: ZoneInfo, ms: int) -> int:
    return offset_seconds(zone, ms // 1000) * 1000


def transitions(zone: ZoneInfo, first_year: int = 2020, end_year: int = 2031) -> list[tuple[int, int, int]]:
    """(utc second of the transition, old offset s, new offset s) from the tz database."""
    lo = int((dt.datetime(first_year, 1, 1, tzinfo=dt.timezone.utc) - EPOCH).total_seconds())
    hi = int((dt.datetime(end_year, 1, 1, tzinfo=dt.timezone.utc) - EPOCH).total_seconds())
    found: list[tuple[int, int, int]] = []
    step = 3 * 3600
    cur = lo
    while cur < hi:
        nxt = cur + step
        old, new = offset_seconds(zone, cur), offset_seconds(zone, nxt)
        if old != new:
            a, b = cur, nxt
            while b - a > 1:
                mid = (a + b) // 2
                if offset_seconds(zone, mid) == old:
                    a = mid
                else:
                    b = mid
            found.append((b, old, new))
        cur = nxt
    return found


TRANSITIONS = {name: transitions(ZoneInfo(name)) for name in ZONE_NAMES}
FALL_BACKS = [(name, tr) for name, trs in TRANSITIONS.items() for tr in trs if tr[2] < tr[1]]
assert FALL_BACKS, "the tz database must supply fall-back transitions"

# -------------------------------------------------------------- strategies

MS_WIDE = st.integers(min_value=-2_208_988_800_000, max_value=4_102_444_800_000)  # 1900 .. 2100
MS_NEAR_EPOCH = st.integers(min_value=-3000, max_value=3000)  # both sides of -1000 and 0
ZONES = st.sampled_from(ZONE_NAMES)


def near_transition(name: str) -> st.SearchStrategy[int]:
    trs = TRANSITIONS[name]
    if not trs:
        return MS_NEAR_EPOCH
    return st.builds(
        lambda tr, d: tr[0] * 1000 + d,
        st.sampled_from(trs),
        st.integers(min_value=-7_200_000, max_value=7_200_000),
    )


# ------------------------------------------------------------------- tests


def test_vectors_asserted_by_the_java_unit_tests() -> None:
    """The values DebeziumConverterTest asserts against the real code."""
    chicago = ZoneInfo("America/Chicago")
    assert instant_epoch_text(1709290200123, chicago) == "1709290200.123000"
    assert instant_epoch_text(1709290200123, UTC_ZONE) == "1709290200.123000"
    assert instant_epoch_text(1793518200000, chicago) == "1793518200.000000"  # second 01:30 Chicago
    assert instant_epoch_text(0, chicago) == "0.000000"
    assert instant_epoch_text(-1000, chicago) == "-1.000000"
    assert instant_epoch_text(-2500, chicago) == "-2.500000"
    assert instant_epoch_text(-500, chicago) == "1969-12-31 17:59:59.500"
    assert instant_epoch_text(-1, UTC_ZONE) == "1969-12-31 23:59:59.999"


@PROFILE
@given(data=st.data(), name=ZONES)
def test_t1_the_bound_text_is_the_instant(data, name) -> None:
    """T1: for every instant and column zone, ClickHouse stores exactly ts_ms."""
    zone = ZoneInfo(name)
    ms = data.draw(st.one_of(MS_WIDE, MS_NEAR_EPOCH, near_transition(name)))
    assert ch_read(instant_epoch_text(ms, zone), zone) == ms * 1000


@PROFILE
@given(ms=st.one_of(MS_WIDE, MS_NEAR_EPOCH), a=ZONES, b=ZONES)
def test_epoch_text_names_no_zone(ms, a, b) -> None:
    """The epoch branch is the same text in every column zone."""
    epoch_second, milli = divmod(ms, 1000)
    assume(binds_as_epoch_text(epoch_second, milli * 1_000_000))
    assert instant_epoch_text(ms, ZoneInfo(a)) == instant_epoch_text(ms, ZoneInfo(b))


@PROFILE
@given(ms=st.one_of(MS_WIDE, MS_NEAR_EPOCH))
def test_digits_fallback_is_exactly_the_misread_set(ms) -> None:
    """The fallback fires exactly where epoch text would be misread: -1000 < ms < 0."""
    epoch_second, milli = divmod(ms, 1000)
    nano = milli * 1_000_000
    misread = ch_read(epoch_text(epoch_second, nano), UTC_ZONE) != ms * 1000
    assert (not binds_as_epoch_text(epoch_second, nano)) == misread
    assert (not binds_as_epoch_text(epoch_second, nano)) == (-1000 < ms < 0)


@PROFILE
@given(case=st.sampled_from(FALL_BACKS), k=st.integers(min_value=0, max_value=3_600_000))
def test_overlap_second_occurrence_needs_epoch_text(case, k) -> None:
    """Digits store the second of a repeated hour one hour early; epoch text stores it exactly."""
    name, (transition_second, old, new) = case
    drop_ms = (old - new) * 1000
    ms = transition_second * 1000 + k % drop_ms  # inside the repeated interval
    zone = ZoneInfo(name)
    assert ch_read(digits(ms, zone), zone) == (ms - drop_ms) * 1000
    assert ch_read(instant_epoch_text(ms, zone), zone) == ms * 1000


@PROFILE
@given(ms=MS_WIDE, name=ZONES)
def test_old_utc_digits_were_late_by_the_zone_offset(ms, name) -> None:
    """The replaced rendering: UTC digits read in a column zone come back `offset` late."""
    zone = ZoneInfo(name)
    off = offset_ms(zone, ms)
    shifted = ms - off
    # Away from any transition, so the reading is neither a gap nor an overlap.
    assume(all(offset_ms(zone, shifted + d) == off for d in (-14_400_000, 0, 14_400_000)))
    read = ch_read(digits(ms, UTC_ZONE), zone)
    assert read == shifted * 1000
    assert (read == ms * 1000) == (off == 0)


@PROFILE
@given(day=st.integers(min_value=0, max_value=30), second=st.integers(min_value=0, max_value=86399))
def test_old_chicago_skew_is_the_observed_one(day, second) -> None:
    """+21600 s in America/Chicago winter, +18000 s in summer."""
    chicago = ZoneInfo("America/Chicago")
    for start, skew_s in ((dt.datetime(2026, 1, 10, tzinfo=dt.timezone.utc), 21600),
                          (dt.datetime(2026, 7, 10, tzinfo=dt.timezone.utc), 18000)):
        ms = int((start - EPOCH).total_seconds()) * 1000 + day * 86_400_000 + second * 1000
        assert ch_read(digits(ms, UTC_ZONE), chicago) - ms * 1000 == skew_s * 1_000_000

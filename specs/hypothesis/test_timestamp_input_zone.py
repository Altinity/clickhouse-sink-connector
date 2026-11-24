"""Property tests for the loader's `input()` zone pinning (a faithful Python model).

spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::timestamp_input_datatype
spec-for: sink-connector/python/ch_sink_tools/db_load/clickhouse_loader.py::load_data_mysqlshell
request: Altinity/clickhouse-sink-connector pull request, "Pin the dump's TIMESTAMP zone in the loader's input() declaration"
spec: specs/13-python-toolset/04-clickhouse-loader-and-mysql-ddl-translation.md, section 3.9

Property Z1 in words. A MySQL Shell dump writes TIMESTAMP values as text in the
zone the dump records (`"tzUtc": true` in `@.json`, so UTC). The loader hands
that text to `clickhouse-client` inside `FROM input('<structure>')`, and the
declaration of the field inside that structure decides how the text is read: a
zone-less `DateTime64(p)` is read in the AMBIENT zone of the load (the client
zone under `--use_client_time_zone 1`, else the server's), so the instant it
stores moves with the machine that runs the load; a declaration naming a zone
is read in that zone, so the stored instant is the same everywhere.
`timestamp_input_datatype(datatype, timezone)` pins the resolved zone into it.

This is a MODEL, not the loader: the module imports `ch_sink_tools.db.mysql`,
`clickhouse_connect` and the ANTLR DDL translator, which are not installable
here. The model is pinned to the loader two ways:
`test_vectors_asserted_by_the_measured_runs` repeats the declaration text and
the instants measured on ClickHouse 24.8.14, and `timestamp_input_datatype` is
transcribed line for line from the real function, regex included.

Every instant is an integer count of microseconds after the epoch. Zone rules
come from the system tz database through `zoneinfo`: real transitions, not
invented ones.

Run: `pytest specs/hypothesis/test_timestamp_input_zone.py`
"""

from __future__ import annotations

import datetime as dt
import re
from zoneinfo import ZoneInfo

from hypothesis import assume, given, settings
from hypothesis import strategies as st

PROFILE = settings(derandomize=True, database=None, deadline=None, max_examples=300)

EPOCH = dt.datetime(1970, 1, 1)
UTC = dt.timezone.utc
MICRO = dt.timedelta(microseconds=1)

# The zone the dump records, and the ambient zones the measurement used.
DUMP_ZONE = "UTC"
AMBIENT_ZONES = [
    "UTC",
    "America/Chicago",
    "America/New_York",
    "America/St_Johns",
    "Europe/London",
    "Asia/Kolkata",
    "Australia/Lord_Howe",
    "Pacific/Chatham",
]

# ------------------------------------------------------- the loader, modelled

# The module the two definitions below are transcribed from, spelled as a module
# path (a dash in the directory name becomes an underscore). The model stands in
# for this module's code, so a property that drifts away from it says so here.
SUBJECT_MODULE = "sink_connector.python.ch_sink_tools.db_load.clickhouse_loader"

# clickhouse_loader.DATETIME_WITH_ZONE, transcribed.
DATETIME_WITH_ZONE = re.compile(
    r"DateTime64\s*\([^()]*,\s*'[^']*'\s*\)|DateTime\s*\(\s*'[^']*'\s*\)", re.IGNORECASE
)


def timestamp_input_datatype(datatype, timezone):
    """clickhouse_loader.timestamp_input_datatype, transcribed line for line."""
    text = (datatype or "").strip()
    if timezone is None or DATETIME_WITH_ZONE.search(text):
        return text
    if text.endswith(")"):
        return text[:-1] + f", '{timezone}')"
    # the translator emits DateTime64(p) for TIMESTAMP; anything else zone-less is
    # parsed at whole seconds
    return f"DateTime64(0, '{timezone}')"


def declared_zone(declaration):
    """The zone a declaration names, or None when it names none."""
    m = re.search(r",\s*'([^']*)'\s*\)", declaration)
    return m.group(1) if m else None


# ------------------------------------------------ ClickHouse, modelled (24.8.14)


def wall_micros(wall):
    """An offset-free wall reading as microseconds after the epoch, read as UTC."""
    return (wall - EPOCH) // MICRO


def offset_micros(wall, zone_name):
    """The UTC offset of `zone_name` at the wall reading `wall`, in microseconds."""
    return wall.replace(tzinfo=ZoneInfo(zone_name)).utcoffset() // MICRO


def is_unambiguous(wall, zone_name):
    """True when `wall` names exactly one instant in `zone_name`.

    A reading inside a fall-back overlap names two instants; one inside a
    spring-forward gap names none and comes back shifted. ClickHouse normalizes
    both, and neither is the property under test, so the strategies stay out.
    """
    zone = ZoneInfo(zone_name)
    first = wall.replace(tzinfo=zone)
    second = wall.replace(tzinfo=zone, fold=1)
    if first.utcoffset() != second.utcoffset():
        return False
    return first.astimezone(UTC).astimezone(zone).replace(tzinfo=None) == wall


def stored_instant(declaration, wall, ambient_zone):
    """The instant ClickHouse stores for the dump's wall text, in epoch micros.

    `wall` is the offset-free text the dump wrote. A declaration naming a zone
    reads the text in that zone; a declaration naming none reads it in the
    ambient zone of the load.
    """
    zone = declared_zone(declaration) or ambient_zone
    return wall_micros(wall) - offset_micros(wall, zone)


# ------------------------------------------------------------------ strategies

WALL = st.datetimes(
    min_value=dt.datetime(1970, 1, 1, 0, 0, 0),
    max_value=dt.datetime(2038, 1, 1, 0, 0, 0),
    timezones=st.none(),
)
ZONE_NAME = st.sampled_from(AMBIENT_ZONES)
DATATYPE = st.sampled_from(
    ["DateTime64(6)", "DateTime64(3)", "DateTime", "DateTime64(9)", "DateTime64(0)"]
)
# A wall reading and an ambient zone that both read it unambiguously.
PAIR = st.tuples(WALL, ZONE_NAME).filter(
    lambda p: is_unambiguous(p[0], p[1]) and is_unambiguous(p[0], DUMP_ZONE)
)

# ------------------------------------------------- the declared type (Z4)


@given(DATATYPE, ZONE_NAME)
def test_the_declaration_pins_the_resolved_zone(datatype, zone):
    """Z4a: a zone-less declaration gains the resolved zone, and only that."""
    out = timestamp_input_datatype(datatype, zone)
    assert declared_zone(out) == zone
    assert out.startswith(datatype.rstrip(")"))


@given(DATATYPE, ZONE_NAME, ZONE_NAME)
def test_an_already_named_zone_is_kept(datatype, declared, resolved):
    """Z4b: --clickhouse_datetime_timezone decides a declaration it already names."""
    pinned = timestamp_input_datatype(datatype, declared)
    assert timestamp_input_datatype(pinned, resolved) == pinned


@given(ZONE_NAME)
def test_no_resolved_zone_is_passed_through_untouched(zone):
    """A load that resolved no zone emits exactly what it was given."""
    assert timestamp_input_datatype("DateTime64(6)", None) == "DateTime64(6)"


@given(ZONE_NAME)
def test_a_second_pass_cannot_drift(zone):
    """Z4b: pinning twice keeps the first zone, so a re-run cannot change data."""
    once = timestamp_input_datatype("DateTime64(6)", DUMP_ZONE)
    assert timestamp_input_datatype(once, zone) == once


# --------------------------------------------- the stored instant (Z1, Z2, Z3)


@given(PAIR)
def test_pinned_ignores_the_ambient_zone(pair):
    """Z1: under the declaration the fix emits, the instant does not move."""
    wall, ambient = pair
    declaration = timestamp_input_datatype("DateTime64(6)", DUMP_ZONE)
    correct = wall_micros(wall)
    assert stored_instant(declaration, wall, ambient) == correct
    assert stored_instant(declaration, wall, "Europe/London") == correct
    assert stored_instant(declaration, wall, "America/Chicago") == correct
    assert stored_instant(declaration, wall, "Pacific/Chatham") == correct


@given(PAIR)
def test_zone_less_follows_the_ambient_zone(pair):
    """Z2: with no zone in the declaration the instant is the ambient zone's."""
    wall, ambient = pair
    declaration = timestamp_input_datatype("DateTime64(6)", None)
    assert declared_zone(declaration) is None
    stored = stored_instant(declaration, wall, ambient)
    correct = wall_micros(wall)
    # right only when the ambient zone is, at that reading, the dump's zone
    assert (stored == correct) == (offset_micros(wall, ambient) == 0)


@given(PAIR)
def test_the_fix_stores_the_dumps_instant(pair):
    """Z3: the text's instant survives the load, whatever machine runs it."""
    wall, ambient = pair
    declaration = timestamp_input_datatype("DateTime64(6)", DUMP_ZONE)
    assert stored_instant(declaration, wall, ambient) == wall_micros(wall)


@given(PAIR)
def test_a_dump_zone_other_than_utc_is_also_pinned(pair):
    """The resolved zone is used as resolved, not assumed to be UTC."""
    wall, ambient = pair
    for zone in AMBIENT_ZONES:
        declaration = timestamp_input_datatype("DateTime64(6)", zone)
        expected = wall_micros(wall) - offset_micros(wall, zone)
        assert stored_instant(declaration, wall, ambient) == expected
        assert stored_instant(declaration, wall, DUMP_ZONE) == expected
        assert stored_instant(declaration, wall, "Asia/Kolkata") == expected


# -------------------------------------------------------- the measured vectors


def test_vectors_asserted_by_the_measured_runs():
    """The declaration text and the instants measured on ClickHouse 24.8.14.

    The dump's UTC text `2018-04-05 20:44:35.772929` and
    `2018-12-01 00:18:03.545683` was loaded through `input()` under three
    ambient zones. The integers below are the microseconds `toInt64()` returned.
    """
    # the two functions modelled at the top of this file are transcribed from
    # this module, so a drift in either name breaks this vector test too
    assert SUBJECT_MODULE.endswith("db_load.clickhouse_loader")
    # the declaration text the loader emits
    assert timestamp_input_datatype("DateTime64(6)", "UTC") == "DateTime64(6, 'UTC')"
    assert (
        timestamp_input_datatype("DateTime64(6)", "America/Chicago")
        == "DateTime64(6, 'America/Chicago')"
    )
    # a datatype the translator does not produce takes the whole-second branch
    assert timestamp_input_datatype("DateTime", "UTC") == "DateTime64(0, 'UTC')"
    assert timestamp_input_datatype("DateTime", None) == "DateTime"

    april = dt.datetime(2018, 4, 5, 20, 44, 35, 772929)
    december = dt.datetime(2018, 12, 1, 0, 18, 3, 545683)
    assert wall_micros(april) == 1522961075772929
    assert wall_micros(december) == 1543623483545683

    # zone-less: the stored instant moves with the ambient zone
    zone_less = timestamp_input_datatype("DateTime64(6)", None)
    assert stored_instant(zone_less, april, "UTC") == 1522961075772929
    assert stored_instant(zone_less, december, "UTC") == 1543623483545683
    assert stored_instant(zone_less, april, "America/Chicago") == 1522979075772929
    assert stored_instant(zone_less, december, "America/Chicago") == 1543645083545683
    assert stored_instant(zone_less, april, "Europe/London") == 1522957475772929
    assert stored_instant(zone_less, december, "Europe/London") == 1543623483545683

    # pinned: one instant under every ambient zone, and it is the text's instant
    pinned = timestamp_input_datatype("DateTime64(6)", "UTC")
    for ambient in AMBIENT_ZONES:
        assert stored_instant(pinned, april, ambient) == 1522961075772929
        assert stored_instant(pinned, december, ambient) == 1543623483545683

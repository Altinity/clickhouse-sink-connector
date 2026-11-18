"""Time zone rules of the checksum tools (spec 11.02 section 3.2 step 1, section 3.4).

Offline: no connection is opened. The zone database is Python's ``zoneinfo``; the
ClickHouse expectations are recorded ClickHouse 25.8 results (see the constants).
"""
import datetime
import os
import sys
import unittest
import zoneinfo
from unittest.mock import patch

sys.path.insert(
    0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
)

import db_compare.clickhouse_table_checksum as ch  # noqa: E402
import db_compare.mysql_table_checksum as my  # noqa: E402
from db.checksum_common import (  # noqa: E402
    DATETIME_MAX, DATETIME_MIN, shift_datetime_bounds, validate_timezone,
)
import ch_sink_tools.db.checksum_common as checksum_common  # noqa: E402

# Region names the tools accept: the zone must be a valid IANA region for both
# Python and ClickHouse. Every name here is used by the test matrix (spec 11.02 3.4).
ACCEPTED_ZONES = (
    "UTC", "GMT", "Etc/UTC", "America/Chicago", "US/Central", "Asia/Kolkata", "Asia/Kathmandu",
    "Australia/Lord_Howe", "America/St_Johns", "Pacific/Apia", "Europe/London", "Asia/Tokyo",
    "America/Argentina/Buenos_Aires", "America/Port-au-Prince",
)

# Abbreviations, legacy fixed zones, Etc/ names other than Etc/UTC, SystemV/posix/right
# names, blanks, SYSTEM, offsets and path-like text: none of them is a region.
REFUSED_ZONES = (
    "", "   ", " UTC", "UTC ", "SYSTEM", "system", "CST", "EST", "MST", "HST", "CST6CDT", "EST5EDT",
    "Etc/GMT+5", "Etc/GMT", "Etc/GMT-14", "SystemV/EST5", "posix/America/Chicago", "right/UTC",
    "+05:00", "-05:00", "+0530", "Mars/Olympus", "../etc/passwd", "/usr/share/zoneinfo/UTC",
    "America//Chicago", "America/", "/Chicago", "America/Chicago\n", "UTC\n",
)

class TestZoneNameRules(unittest.TestCase):
    """Which names validate_timezone() accepts, and what the refusal says (spec 11.02 section 3.4)."""

    def test_region_names_are_accepted_and_resolve_to_themselves(self):
        for name in ACCEPTED_ZONES:
            with self.subTest(zone=name):
                zone = validate_timezone(name, "--source_timezone")
                self.assertIsInstance(zone, zoneinfo.ZoneInfo)
                self.assertEqual(zone.key, name)

    def test_non_region_names_are_refused_naming_the_option(self):
        for name in REFUSED_ZONES:
            with self.subTest(zone=name):
                with self.assertRaises(ValueError) as caught:
                    validate_timezone(name, "--source_timezone")
                self.assertIn("--source_timezone", str(caught.exception))

    def test_none_is_refused_not_treated_as_unset(self):
        with self.assertRaises(ValueError) as caught:
            validate_timezone(None, "--source_timezone")
        self.assertIn("--source_timezone", str(caught.exception))

    def test_unknown_region_with_tzdata_present_is_reported_as_unknown(self):
        with self.assertRaises(ValueError) as caught:
            validate_timezone("America/Chicagoo", "--source_timezone")
        message = str(caught.exception)
        self.assertIn("not a known IANA time zone name", message)
        self.assertNotIn("tzdata", message)

    def test_missing_tzdata_is_named_as_such_not_as_an_unknown_zone(self):
        # With no zone database at all, every lookup fails the same way, UTC included.
        with patch("zoneinfo.ZoneInfo", side_effect=zoneinfo.ZoneInfoNotFoundError("No time zone found")):
            with self.assertRaises(ValueError) as caught:
                validate_timezone("America/Chicago", "--source_timezone")
        message = str(caught.exception)
        self.assertIn("tzdata", message)
        self.assertIn("America/Chicago", message)
        self.assertIn("pip install tzdata", message)
        self.assertNotIn("not a known IANA time zone name", message)

    def test_missing_tzdata_is_not_masked_as_a_refused_name_either(self):
        # A region-shaped name must reach the lookup (not be refused by the rule) when tzdata is missing.
        with patch("zoneinfo.ZoneInfo", side_effect=zoneinfo.ZoneInfoNotFoundError("No time zone found")):
            with self.assertRaises(ValueError) as caught:
                validate_timezone("Europe/Berlin", "--source_timezone")
        self.assertIn("tzdata", str(caught.exception))

    def test_shift_bounds_still_refuse_abbreviations_with_value_error(self):
        # Existing contract (test_checksum_fidelity.TestInstantComparison) kept.
        with self.assertRaises(ValueError):
            shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "CST")

class TestSourceFlagRequired(unittest.TestCase):
    """F08: the standalone side tools have no UTC default; the flag is the only source (spec 11.02 section 3.2 step 1)."""

    def test_mysql_side_flag_has_no_default(self):
        parser = my.build_argument_parser()
        action = next(a for a in parser._actions if "--source_timezone" in a.option_strings)
        self.assertIsNone(action.default)

    def test_clickhouse_side_flag_has_no_default(self):
        parser = ch.build_argument_parser()
        action = next(a for a in parser._actions if "--source_timezone" in a.option_strings)
        self.assertIsNone(action.default)

    def test_explicit_flag_is_used_and_marked_flag(self):
        self.assertEqual(checksum_common.resolve_source_zone("Asia/Tokyo"), ("Asia/Tokyo", "flag"))

    def test_a_bad_flag_is_refused_loudly(self):
        with self.assertRaises(ValueError) as caught:
            checksum_common.resolve_source_zone("CST")
        self.assertIn("--source_timezone", str(caught.exception))

    def test_no_flag_is_refused_with_the_refusal_text(self):
        with self.assertRaises(ValueError) as caught:
            checksum_common.resolve_source_zone(None)
        message = str(caught.exception)
        self.assertEqual(message, checksum_common.source_timezone_refusal())
        self.assertIn("--source_timezone", message)
        self.assertIn("America/Chicago", message)
        self.assertIn("UTC", message)


class TestResolveSourceZone(unittest.TestCase):
    """F08: resolution is flag-only; there is no session or default input (spec 11.02 section 3.2 step 1)."""

    def test_signature_has_no_session_or_default_input(self):
        import inspect
        params = list(inspect.signature(checksum_common.resolve_source_zone).parameters)
        self.assertEqual(params, ["explicit", "option_name"])

    def test_empty_flag_is_refused_not_treated_as_unset(self):
        with self.assertRaises(ValueError) as caught:
            checksum_common.resolve_source_zone("")
        self.assertIn("--source_timezone", str(caught.exception))

    def test_nothing_given_is_an_error(self):
        with self.assertRaises(ValueError):
            checksum_common.resolve_source_zone(None)


def _minutes_of_day(zone, day):
    """Classify every minute of a local calendar day; return {'HH:MM': class} for the non-ok minutes."""
    base = datetime.datetime.fromisoformat(day)
    out = {}
    for minute in range(24 * 60):
        wall = base + datetime.timedelta(minutes=minute)
        text = wall.strftime(checksum_common.CANONICAL_DATETIME_FORMAT)
        klass = checksum_common.classify_wall_clock(text, zone)
        if klass != checksum_common.ZONE_CLASS_OK:
            out[wall.strftime("%H:%M")] = klass
    return out


class TestWallClockClassifier(unittest.TestCase):
    """Exhaustive per-minute checks of the UNREPRESENTABLE-IN-ZONE classes (spec 11.02 section 3.4).

    The transitions are the zoneinfo facts for the tzdata shipped with Python; each day is walked
    minute by minute, so the expected sets are complete, not samples."""

    def test_chicago_spring_forward_gap_is_the_whole_skipped_hour(self):
        expected = {f"02:{m:02d}": "gap" for m in range(60)}
        self.assertEqual(_minutes_of_day("America/Chicago", "2026-03-08"), expected)

    def test_chicago_fall_back_fold_is_the_whole_repeated_hour(self):
        expected = {f"01:{m:02d}": "fold" for m in range(60)}
        self.assertEqual(_minutes_of_day("America/Chicago", "2026-11-01"), expected)

    def test_lord_howe_spring_gap_is_thirty_minutes_at_two(self):
        expected = {f"02:{m:02d}": "gap" for m in range(30)}
        self.assertEqual(_minutes_of_day("Australia/Lord_Howe", "2026-10-04"), expected)

    def test_lord_howe_fall_back_fold_is_the_last_thirty_minutes_before_two(self):
        expected = {f"01:{m:02d}": "fold" for m in range(30, 60)}
        self.assertEqual(_minutes_of_day("Australia/Lord_Howe", "2027-04-04"), expected)

    def test_london_spring_gap_is_the_hour_after_one(self):
        expected = {f"01:{m:02d}": "gap" for m in range(60)}
        self.assertEqual(_minutes_of_day("Europe/London", "2026-03-29"), expected)

    def test_chatham_spring_gap_starts_at_quarter_to_three(self):
        start = datetime.datetime(2026, 9, 27, 2, 45)
        expected = {(start + datetime.timedelta(minutes=m)).strftime("%H:%M"): "gap" for m in range(60)}
        self.assertEqual(_minutes_of_day("Pacific/Chatham", "2026-09-27"), expected)

    def test_kolkata_has_no_gap_or_fold_on_a_normal_day(self):
        self.assertEqual(_minutes_of_day("Asia/Kolkata", "2026-06-01"), {})

    def test_point_probes_of_the_transitions(self):
        probe = checksum_common.classify_wall_clock
        self.assertEqual(probe("2026-03-08 02:30:00.000000", "America/Chicago"), "gap")
        self.assertEqual(probe("2026-03-08 01:59:59.000000", "America/Chicago"), "ok")
        self.assertEqual(probe("2026-03-08 03:00:00.000000", "America/Chicago"), "ok")
        self.assertEqual(probe("2026-11-01 01:30:00.000000", "America/Chicago"), "fold")
        self.assertEqual(probe("2026-10-04 02:15:00.000000", "Australia/Lord_Howe"), "gap")

    def test_range_edges_use_the_zone_shifted_bounds(self):
        probe = checksum_common.classify_wall_clock
        self.assertEqual(probe("1899-12-31 23:59:59.000000", "UTC"), "before-range")
        self.assertEqual(probe("1900-01-01 00:00:00.000000", "UTC"), "ok")
        self.assertEqual(probe("2299-12-31 23:59:59.000000", "UTC"), "ok")
        self.assertEqual(probe("2300-01-01 00:00:00.000000", "UTC"), "after-range")
        # Asia/Kolkata: the upper bound is 2300-01-01 05:29:59 as a wall clock, so 00:00 is still in range.
        self.assertEqual(probe("2300-01-01 00:00:00.000000", "Asia/Kolkata"), "ok")
        self.assertEqual(probe("2300-01-01 05:30:00.000000", "Asia/Kolkata"), "after-range")

    def test_unrepresentable_classes_are_exactly_gap_and_fold(self):
        self.assertEqual(checksum_common.ZONE_CLASSES_UNREPRESENTABLE, ("gap", "fold"))

    def test_a_refused_zone_is_refused_naming_the_option(self):
        with self.assertRaises(ValueError) as caught:
            checksum_common.classify_wall_clock("2026-03-08 02:30:00.000000", "CST")
        self.assertIn("--source_timezone", str(caught.exception))


# ClickHouse 25.8 probe constants (edge limits of the zone bounds). Recorded from clickhouse-client
# 25.8.16.20002 in `clickhouse local` mode. Text
# renderings are ClickHouse's toString(...); the parse rows use the epoch, because ClickHouse clamps
# the text of pre-1900 instants (see the clamp rows). MySQL is the truth: the tools follow zoneinfo.
CH_RENDER_UTC_TO_ZONE = (  # (zone, UTC instant, ClickHouse local text): agree with zoneinfo
    ("America/Chicago", "1900-01-01 12:00:00", "1900-01-01 06:00:00.000000"),
    ("America/St_Johns", "1900-01-01 04:00:00", "1900-01-01 00:29:08.000000"),
    ("America/St_Johns", "1900-01-01 06:00:00", "1900-01-01 02:29:08.000000"),
    ("America/St_Johns", "1900-01-01 12:00:00", "1900-01-01 08:29:08.000000"),
    ("Asia/Kolkata", "1900-01-01 00:00:00", "1900-01-01 05:21:10.000000"),
    ("Asia/Kolkata", "1900-01-01 12:00:00", "1900-01-01 17:21:10.000000"),
)
CH_CLAMP_PRE_1900_LOCAL = (  # (zone, UTC instant, zoneinfo local, ClickHouse text): text clamped to 1900
    ("America/Chicago", "1900-01-01 00:00:00", "1899-12-31 18:00:00", "1900-01-01 00:00:00.000000"),
    ("America/St_Johns", "1900-01-01 01:00:00", "1899-12-31 21:29:08", "1900-01-01 00:00:00.000000"),
)
CH_PARSE_EPOCH_MICRO = (  # (zone, wall clock, ClickHouse epoch micro): agree with zoneinfo
    ("Asia/Kolkata", "2299-12-31 23:59:59", 10413772199000000),
    ("Asia/Tokyo", "2299-12-31 23:59:59", 10413759599000000),
    ("America/St_Johns", "1900-01-01 00:00:00", -2208976148000000),
    ("Asia/Kolkata", "1900-01-01 00:00:00", -2209008070000000),
)
CH_KOLKATA_2300_WALL_EPOCH_MICRO = 10413685800000000  # ClickHouse: 2300-01-01 00:00 Kolkata
CH_WEST_ZONE_2299_WALL_TEXT = "1970-01-01 02:53:33.813599"  # ClickHouse: Chicago 2299-12-31 23:59:59 wall


class TestClickHouse258Probe(unittest.TestCase):
    """Test constants from the ClickHouse 25.8 probe (spec 11.02 section 3.4).

    Agreeing rows pin the zoneinfo values to ClickHouse. Diverging rows pin both values, so a change in
    either engine fails loudly. The tools' bounds for the east and west zones are checked here too."""

    def test_render_rows_agree_with_clickhouse(self):
        for zone, utc_text, ch_text in CH_RENDER_UTC_TO_ZONE:
            instant = datetime.datetime.strptime(utc_text, "%Y-%m-%d %H:%M:%S").replace(
                tzinfo=datetime.timezone.utc)
            local = instant.astimezone(zoneinfo.ZoneInfo(zone)).strftime(checksum_common.CANONICAL_DATETIME_FORMAT)
            self.assertEqual(local, ch_text, zone)

    def test_pre_1900_local_walls_are_clamped_in_clickhouse_text(self):
        for zone, utc_text, zoneinfo_local, ch_text in CH_CLAMP_PRE_1900_LOCAL:
            instant = datetime.datetime.strptime(utc_text, "%Y-%m-%d %H:%M:%S").replace(
                tzinfo=datetime.timezone.utc)
            local = instant.astimezone(zoneinfo.ZoneInfo(zone)).replace(tzinfo=None)
            self.assertEqual(local.strftime(checksum_common.CANONICAL_DATETIME_FORMAT),
                             zoneinfo_local + ".000000", zone)
            self.assertLess(local, datetime.datetime(1900, 1, 1), zone)
            self.assertEqual(ch_text, "1900-01-01 00:00:00.000000", zone)

    def test_parse_epochs_agree_with_clickhouse(self):
        for zone, wall, ch_micro in CH_PARSE_EPOCH_MICRO:
            naive = datetime.datetime.strptime(wall, "%Y-%m-%d %H:%M:%S")
            aware = naive.replace(tzinfo=zoneinfo.ZoneInfo(zone))
            py_micro = int(round(aware.timestamp())) * 1000000
            self.assertEqual(py_micro, ch_micro, (zone, wall))

    def test_kolkata_2300_wall_is_a_day_early_in_clickhouse(self):
        # zoneinfo: 2300-01-01 00:00 IST is 2299-12-31 18:30 UTC.
        naive = datetime.datetime(2300, 1, 1, 0, 0, 0)
        py_micro = int(round(naive.replace(tzinfo=zoneinfo.ZoneInfo("Asia/Kolkata")).timestamp())) * 1000000
        self.assertEqual(py_micro, 10413772200000000)
        self.assertEqual(py_micro - CH_KOLKATA_2300_WALL_EPOCH_MICRO, 86400 * 1000000)

    def test_west_zone_wall_beyond_2037_is_not_representable_in_clickhouse(self):
        # The zoneinfo instant is 2300-01-01 05:59:59 UTC (epoch seconds 10413813599); ClickHouse returns
        # that number without the micro scale and a text in 1970. Recorded, not fixed (spec 11.02 3.4).
        naive = datetime.datetime(2299, 12, 31, 23, 59, 59)
        py_seconds = int(round(naive.replace(tzinfo=zoneinfo.ZoneInfo("America/Chicago")).timestamp()))
        self.assertEqual(py_seconds, 10413813599)
        self.assertTrue(CH_WEST_ZONE_2299_WALL_TEXT.startswith("1970-01-01"))

    def test_connector_bounds_for_east_and_west_zones(self):
        self.assertEqual(shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "Asia/Kolkata"),
                         ("1900-01-01 05:21:10.000000", "2300-01-01 05:29:59.000000"))
        self.assertEqual(shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "America/Chicago"),
                         ("1899-12-31 18:00:00.000000", "2299-12-31 17:59:59.000000"))
        self.assertEqual(shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), "America/St_Johns"),
                         ("1899-12-31 20:29:08.000000", "2299-12-31 20:29:59.000000"))


if __name__ == "__main__":
    unittest.main()

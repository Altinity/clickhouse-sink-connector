"""
Pieces of the table checksum that MUST be identical on the MySQL side and on the
ClickHouse side (spec 11.02). Anything defined here is imported by both
db_compare/mysql_table_checksum.py and db_compare/clickhouse_table_checksum.py so
that the two sides cannot drift apart.
"""
import datetime
import hashlib
import logging
import re
import zoneinfo

# The ClickHouse DateTime64 range the connector clamps to when it writes
# (DataTypeRange.DATETIME64_MIN / DATETIME64_MAX: 1900-01-01 00:00:00 and
# 2299-12-31 23:59:59 UTC), in the canonical rendering below. Both sides clamp
# their rendered datetime text to these bounds (or to narrower user bounds), so a
# MySQL value the connector could only store clamped compares equal to what
# ClickHouse holds.
DATETIME_MIN = "1900-01-01 00:00:00.000000"
DATETIME_MAX = "2299-12-31 23:59:59.000000"

# The same range, in the second-precision / date-only renderings other tools
# need (Spec 13.06 section 3.6.1, review MAJOR-3): this module is the single
# definition of the ClickHouse DateTime64/Date32 storable range. db_load's
# postgres_type_mapper.py and clickhouse_loader.py and db_compare's
# top_level_table_checksum.py all derive their bounds from these public names
# instead of restating the literal, so the range cannot drift between them.
DATETIME64_MIN_UTC = DATETIME_MIN[:19]
DATETIME64_MAX_UTC = DATETIME_MAX[:19]
DATE32_MIN = DATETIME_MIN[:10]
DATE32_MAX = DATETIME_MAX[:10]

# Canonical text of a DATETIME / TIMESTAMP value on both sides: fixed width,
# six fraction digits, never trimmed (spec 11.02 section 3.4). Fixed width keeps
# the mapping injective (trimming trailing zeros turned '10:00:10' into '10:00:1'
# on one side and '10:00:10' on the other) and makes lexicographic comparison
# against the bounds a correct temporal comparison.
CANONICAL_DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S.%f"
_ACCEPTED_BOUND_FORMATS = ("%Y-%m-%d %H:%M:%S.%f", "%Y-%m-%d %H:%M:%S", "%Y-%m-%d")


# Checksum format version (spec 11.02 section 3.3). Version 2 escapes every value
# piece of the row text before the '#' join, so a value containing '#' or a
# backslash can no longer shift a column boundary. The tag is part of the printed
# checksum, so a version 1 checksum never equals a version 2 checksum.
CHECKSUM_FORMAT = "v2"
CHECKSUM_SEPARATOR = "#"


def escape_checksum_piece(text):
    """Escape one value piece of the row text (spec 11.02 section 3.3): every
    backslash is doubled first, then every '#' is prefixed with a backslash, so
    an unescaped '#' in the joined text can only be a separator."""
    return text.replace("\\", "\\\\").replace("#", "\\#")


def join_checksum_pieces(pieces):
    """The row text: the escaped value pieces joined by the separator."""
    return CHECKSUM_SEPARATOR.join(escape_checksum_piece(piece) for piece in pieces)


def mysql_escape_piece_sql(expression):
    """MySQL SQL that applies ``escape_checksum_piece`` to ``expression``. The
    backslash is CHAR(92) so the escape does not depend on the server's
    NO_BACKSLASH_ESCAPES mode."""
    backslash = "CHAR(92 USING utf8mb4)"
    return ("REPLACE(REPLACE(" + expression + "," + backslash + ",CONCAT(" + backslash + "," + backslash + "))"
            + ",'#',CONCAT(" + backslash + ",'#'))")


def clickhouse_escape_piece_sql(expression):
    """ClickHouse SQL that applies ``escape_checksum_piece`` to ``expression``."""
    backslash = "char(92)"
    return ("replaceAll(replaceAll(" + expression + "," + backslash + ",concat(" + backslash + "," + backslash + "))"
            + ",'#',concat(" + backslash + ",'#'))")


def checksum_from_aggregate(cnt, a, b, c, d):
    """The printed checksum: md5 of ``'v2#<cnt>#<a>#<b>#<c>#<d>#'``.

    ``cnt`` is the row count and ``a``..``d`` are the sums, over all rows, of the
    four 32-bit words of each row's MD5 (spec 11.02 section 3.5). Both sides print
    exactly this value, so an empty table hashes to ``md5('v2#0#0#0#0#0#')`` on both.
    """
    text = CHECKSUM_FORMAT + CHECKSUM_SEPARATOR + "".join(
        str(value) + CHECKSUM_SEPARATOR for value in (cnt, a, b, c, d))
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def canonical_datetime_bound(value, option_name):
    """Normalise a user supplied datetime bound to the canonical 26-character
    form and confine it to [DATETIME_MIN, DATETIME_MAX].

    A bound outside the ClickHouse range cannot be exceeded on the replica side,
    so it would clamp MySQL alone; it is pulled back to the range with a WARNING.
    A value that is not a datetime is refused loudly.
    """
    text = str(value).strip()
    parsed = None
    for fmt in _ACCEPTED_BOUND_FORMATS:
        try:
            parsed = datetime.datetime.strptime(text, fmt)
            break
        except ValueError:
            continue
    if parsed is None:
        raise ValueError(f"{option_name}: '{value}' is not a datetime; expected YYYY-MM-DD[ HH:MM:SS[.ffffff]]")
    rendered = parsed.strftime(CANONICAL_DATETIME_FORMAT)
    confined = min(max(rendered, DATETIME_MIN), DATETIME_MAX)
    if confined != rendered:
        logging.warning(f"{option_name} {rendered} is outside the ClickHouse DateTime64 range, using {confined}")
    return confined


def datetime_bounds(options):
    """(min, max) canonical bounds from the parsed --min/--max_datetime_value."""
    return (canonical_datetime_bound(options.min_datetime_value, "--min_datetime_value"),
            canonical_datetime_bound(options.max_datetime_value, "--max_datetime_value"))


def saturation_floor(utc_bounds, zone):
    """Start of the saturated last day of the range (spec 11.02 section 3.4):
    the first instant of the UTC calendar day that holds the upper bound
    (``2299-12-31 00:00:00`` UTC for the default range), rendered as a wall
    clock of ``zone`` like the bounds themselves (``shift_datetime_bounds()``)
    and never above the upper bound in that zone.

    Every value from this instant on renders as the upper bound on both sides,
    so all the representations of an out-of-range source value that ClickHouse
    can hold compare equal: the connector stores ``2299-12-31 23:59:59`` (or
    ``.999999``), a bulk load stores the source's own time of day on
    ``2299-12-31`` (``00:00:00``, ``05:59:00``, ...).
    """
    utc_floor = utc_bounds[1][:10] + " 00:00:00.000000"
    (floor,) = shift_datetime_bounds((utc_floor,), zone)
    (_, upper) = shift_datetime_bounds(utc_bounds, zone)
    return min(floor, upper)


def clamp_datetime_expression(rendered, min_value, max_value, dialect, saturate_from=None):
    """Clamp the canonical datetime text ``rendered`` to [min_value, max_value]:
    ``>= saturate_from`` renders as the max bound, ``< min`` as the min bound.
    ``saturate_from`` is the start of the saturated last day
    (``saturation_floor()``); without it only values ``>= max`` render as the
    max bound. The same rule in both dialects -- the two sides must never
    differ in the comparison operator or in the text they substitute."""
    upper = max_value if saturate_from is None else saturate_from
    if dialect == "mysql":
        return f"case when {rendered} >= '{upper}' then '{max_value}' when {rendered} < '{min_value}' then '{min_value}' else {rendered} end"
    if dialect == "clickhouse":
        return f"if({rendered} >= '{upper}', '{max_value}', if({rendered} < '{min_value}', '{min_value}', {rendered}))"
    raise ValueError(f"unknown SQL dialect {dialect!r}")


def clamped_datetime_flag(rendered, min_value, max_value, saturate_from=None):
    """1 when the clamp changed the value, 0 otherwise, NULL for NULL;
    identical text in both dialects. Without ``saturate_from`` that is strictly
    outside the bounds; with it, also a value on the saturated last day that is
    not the max bound itself. Summed per table and printed as the clamped-value
    count, never hashed."""
    if saturate_from is None:
        return f"({rendered} > '{max_value}' or {rendered} < '{min_value}')"
    return f"(({rendered} >= '{saturate_from}' and {rendered} != '{max_value}') or {rendered} < '{min_value}')"


def clamped_count_expression(flags):
    """Per-row sum of the clamped flags (``0`` when there is nothing to clamp)."""
    if not flags:
        return "0"
    return " + ".join(f"coalesce({flag}, 0)" for flag in flags)


def clamped_date_flag(rendered, min_value, max_value):
    """1 when the DATE clamp changed the value, 0 otherwise, NULL for NULL (spec
    11.02 section 3.4). Only the MySQL side clamps DATE: a ClickHouse Date32
    replica cannot hold a value outside the same range, so it has nothing to
    count. Summed with the datetime flags and printed, never hashed."""
    return clamped_datetime_flag(rendered, min_value, max_value)


def mysql_decimal_text(column_name):
    """MySQL text of a DECIMAL column with the trailing zeros after the point,
    and then a bare point, dropped (spec 11.02 section 3.3). The same value at
    any declared scale gives the same text on both sides. Text without a point
    (an integer-valued DECIMAL(p,0)) is unchanged."""
    return (f"if(locate('.', {column_name}) > 0, "
            f"trim(trailing '.' from trim(trailing '0' from {column_name})), {column_name})")


def clickhouse_decimal_text(text):
    """ClickHouse counterpart of mysql_decimal_text for ``text``, a
    toDecimalString() rendering: the same normalisation, applied only when the
    text has a point."""
    return (f"if(position({text}, '.') > 0, "
            f"replaceRegexpOne(replaceRegexpOne({text}, '0+$', ''), '[.]$', ''), {text})")


def clickhouse_pad_space_text(text):
    """MySQL CHAR semantics (PAD SPACE, spec 11.02 section 3.3): trailing spaces
    are not significant in a CHAR value, and MySQL does not return them. Applied
    on the ClickHouse side to the String columns whose MySQL type is CHAR. A
    VARCHAR or TEXT column keeps its trailing spaces."""
    return f"replaceRegexpOne({text}, ' +$', '')"


def canonical_decimal_text(text):
    """Python model of the normalisation both SQL renderings apply (mysql_decimal_text
    and clickhouse_decimal_text). Used by the property tests: a value rendered at
    any scale maps to one canonical text."""
    if "." not in text:
        return text
    return text.rstrip("0").rstrip(".")


# The zero-offset names accepted without a region (spec 11.02 section 3.4): the
# three the connector resolves too. Every other name without a region is refused.
ZERO_OFFSET_ZONE_NAMES = frozenset({"UTC", "GMT", "Etc/UTC"})
# An Area/Location identifier: letters, digits, '_', '+', '-' per segment.
_REGION_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_+\-]*(/[A-Za-z][A-Za-z0-9_+\-]*)+$")
# Region-shaped names that are not regions: Etc/ (Etc/GMT+5 has an inverted sign), and the
# legacy SystemV/, posix/ and right/ trees.
_NON_REGION_PREFIXES = ("Etc/", "SystemV/", "posix/", "right/")


def is_region_zone_name(text):
    """True for a name the tools accept: UTC, GMT, Etc/UTC, or an Area/Location
    region (America/Chicago, US/Central, America/Argentina/Buenos_Aires). The
    check is textual, before any zone lookup, because zoneinfo accepts some
    names (EST, Etc/GMT+5) that the connector cannot resolve."""
    if text in ZERO_OFFSET_ZONE_NAMES:
        return True
    if not _REGION_NAME_RE.fullmatch(text):
        return False
    return not text.startswith(_NON_REGION_PREFIXES)


def tzdata_available():
    """Whether Python can load the IANA zone database at all (the system
    tzdata or the ``tzdata`` package). UTC is loaded as the probe: if it fails,
    every region fails the same way, so the cause is the missing database."""
    try:
        zoneinfo.ZoneInfo("UTC")
    except (zoneinfo.ZoneInfoNotFoundError, ValueError):
        return False
    return True


def validate_timezone(name, option_name):
    """Return the zone for a region name (see is_region_zone_name), the only
    form both Python's zoneinfo and ClickHouse accept for a connector zone.

    A refused name (abbreviation, legacy or Etc/ name, SYSTEM, blank, offset)
    raises ValueError naming the option. A region name that zoneinfo cannot
    load is reported as tzdata missing when the database itself is not
    available, and as an unknown name otherwise (spec 11.02 section 3.4)."""
    text = name if isinstance(name, str) else None
    if text is None or text != text.strip() or not is_region_zone_name(text):
        raise ValueError(f"{option_name}: '{name}' is not an IANA region name (Area/Location such as America/Chicago, "
                         "or UTC); abbreviations (CST, EST), Etc/ names other than Etc/UTC, SYSTEM and offsets are refused")
    try:
        return zoneinfo.ZoneInfo(text)
    except zoneinfo.ZoneInfoNotFoundError:
        if not tzdata_available():
            raise ValueError(f"{option_name}: cannot check '{text}': the IANA time zone database (tzdata) is not "
                             "available to Python; install the system tzdata package or run 'pip install tzdata'") from None
        raise ValueError(f"{option_name}: '{text}' is not a known IANA time zone name (for example UTC or Europe/Berlin)") from None
    except ValueError as error:
        raise ValueError(f"{option_name}: '{text}' is not an IANA time zone name: {error}") from error


def source_timezone_refusal(option_name="--source_timezone"):
    """The refusal text when no zone was given (spec 11.02 section 3.2 step 1). It names the flag, suggests a
    region and says that UTC must be passed explicitly when the connector used UTC. There is no server-zone
    query and no default, so the text says so."""
    return (f"{option_name} is required: pass the IANA time zone the connector interprets MySQL DATETIME values in "
            f"(its database.connectionTimeZone), for example {option_name} America/Chicago. Pass {option_name} UTC "
            "only when the connector used UTC. The MySQL server zone is not read and no zone is assumed.")


def check_source_timezone_flag(parser, value, option_name="--source_timezone"):
    """Exit 2 through the argument parser unless the flag is given and names a zone validate_timezone accepts.
    This runs before any connection is opened (spec 11.02 section 3.2 step 1)."""
    if value is None:
        parser.error(source_timezone_refusal(option_name))
    try:
        validate_timezone(value, option_name)
    except ValueError as error:
        parser.error(str(error))


def resolve_source_zone(explicit, option_name="--source_timezone"):
    """Return ``(zone, "flag")`` for the explicit zone (spec 11.02 section 3.2 step 1). The flag is the only
    source: the MySQL server zone is never read and no default applies. A missing flag raises ValueError with
    source_timezone_refusal; an empty or refused flag raises ValueError naming the option, even when empty
    (an empty flag is refused, never read as unset)."""
    if explicit is None:
        raise ValueError(source_timezone_refusal(option_name))
    validate_timezone(explicit, option_name)
    return explicit, "flag"


def get_unix_timezone_from_mysql_timezone(timezone, source_timezone=None):
    """IANA zone name of a dump's TIMESTAMP text, decided without any implicit zone (Spec 13.04 section 3.9).

    A recorded MySQL time_zone value maps deterministically: '+00:00' (and '-00:00') is 'UTC'; another whole-hour
    offset is the fixed-offset zone 'Etc/GMT-N' / 'Etc/GMT+N' (POSIX sign: 'Etc/GMT-5' is UTC+05:00), never a regional
    zone that happens to have that offset today, whose DST would shift part of the year; a named zone known to zoneinfo
    is used as is. A valid offset no IANA name represents (e.g. '+05:30') raises, as does a value out of MySQL's range
    or naming no zone. A dump that records no zone (None, empty, SYSTEM) takes source_timezone (--source_timezone),
    which must pass validate_timezone; without it the load is refused. When both are given they must name the same zone.
    Nothing falls back to UTC.
    """
    if source_timezone is not None:
        validate_timezone(source_timezone, '--source_timezone')
    recorded = _recorded_zone(timezone)
    if recorded is None:
        if source_timezone is None:
            raise ValueError(
                f"The dump does not record the time zone of its TIMESTAMP values (got {timezone!r}), so they cannot be "
                f"loaded. Pass --source_timezone with the zone the source MySQL server stored them in (the connector's "
                f"database.connectionTimeZone), for example --source_timezone America/Chicago.")
        return source_timezone
    if source_timezone is not None and source_timezone != recorded:
        raise ValueError(
            f"--source_timezone {source_timezone} does not match the time zone the dump records ({recorded}). "
            f"The dump's TIMESTAMP values are in {recorded}: drop --source_timezone to load them in that zone.")
    return recorded


def _recorded_zone(timezone):
    """The IANA zone a recorded MySQL time_zone value names, or None when the dump records no zone."""
    value = (timezone or '').strip()
    if value == '' or value.upper() == 'SYSTEM':
        return None
    match = re.fullmatch(r'([+-])(\d{1,2}):(\d{2})', value)
    if match:
        sign, hours, minutes = match.group(1), int(match.group(2)), int(match.group(3))
        if minutes >= 60 or hours * 60 + minutes > 14 * 60:
            raise ValueError(f"The dump's TIMESTAMP values are in time zone {value}, which is outside the range "
                             f"MySQL accepts for a time zone offset.")
        if hours == 0 and minutes == 0:
            return "UTC"
        if minutes == 0:
            name = f"Etc/GMT{'-' if sign == '+' else '+'}{hours}"
            if name in zoneinfo.available_timezones():
                return name
        raise ValueError(
            f"The dump's TIMESTAMP values are in time zone {value}, which no fixed-offset IANA zone represents. "
            f"Re-dump in UTC (MySQL Shell tzUtc: true, the default; mydumper --tz-utc).")
    if value in zoneinfo.available_timezones():
        return value
    raise ValueError(f"The dump records the time zone {value!r}, which is neither a MySQL time zone offset nor an "
                     f"IANA zone name.")


def shift_datetime_bounds(bounds, zone):
    """Render the canonical UTC bounds as wall clocks of ``zone``.

    A MySQL DATETIME denotes the instant its wall clock reads in the source
    zone (spec 07.03), so its clamp bounds are the DateTime64 range instants
    written as source-zone wall clocks -- which is also the text the connector
    writes for an out-of-range value. Both sides call this with the same zone
    (spec 11.02 section 3.4).
    """
    tz = validate_timezone(zone, "--source_timezone")
    utc = zoneinfo.ZoneInfo("UTC")
    shifted = []
    for bound in bounds:
        instant = datetime.datetime.strptime(bound, CANONICAL_DATETIME_FORMAT).replace(tzinfo=utc)
        shifted.append(instant.astimezone(tz).strftime(CANONICAL_DATETIME_FORMAT))
    return tuple(shifted)


ZONE_CLASS_OK = "ok"
ZONE_CLASS_GAP = "gap"
ZONE_CLASS_FOLD = "fold"
ZONE_CLASS_BEFORE_RANGE = "before-range"
ZONE_CLASS_AFTER_RANGE = "after-range"
# UNREPRESENTABLE-IN-ZONE (spec 11.02 section 3.4): a wall clock that no single instant of the zone reads
# (gap) or that two instants read (fold) cannot be stored and compared as one text by both engines.
ZONE_CLASSES_UNREPRESENTABLE = (ZONE_CLASS_GAP, ZONE_CLASS_FOLD)


def classify_wall_clock(text, zone):
    """Classify one canonical wall clock ``text`` of ``zone``; pure, no database.

    Returns one of ZONE_CLASS_OK, ZONE_CLASS_GAP (does not exist: a DST
    spring-forward skips it), ZONE_CLASS_FOLD (exists twice: a DST fall-back
    repeats it), ZONE_CLASS_BEFORE_RANGE / ZONE_CLASS_AFTER_RANGE (outside the
    zone's shifted DATETIME bounds, the same bounds the clamp uses). The range
    is checked first; the gap and fold tests use the zone's own rules.
    Raises ValueError naming the option for a refused zone (validate_timezone).
    """
    tz = validate_timezone(zone, "--source_timezone")
    utc = zoneinfo.ZoneInfo("UTC")
    naive = datetime.datetime.strptime(text, CANONICAL_DATETIME_FORMAT)
    lower, upper = (datetime.datetime.strptime(bound, CANONICAL_DATETIME_FORMAT)
                    for bound in shift_datetime_bounds((DATETIME_MIN, DATETIME_MAX), zone))
    if naive < lower:
        return ZONE_CLASS_BEFORE_RANGE
    if naive > upper:
        return ZONE_CLASS_AFTER_RANGE
    early = naive.replace(tzinfo=tz, fold=0)
    late = naive.replace(tzinfo=tz, fold=1)
    if early.astimezone(utc).astimezone(tz).replace(tzinfo=None) != naive:
        return ZONE_CLASS_GAP
    if early.utcoffset() != late.utcoffset():
        return ZONE_CLASS_FOLD
    return ZONE_CLASS_OK


NOT_COMPARED_HINTS = {
    "floating point": "--include_floating_point_columns to compare their text renderings",
    "JSON": "--include_json_columns for a best-effort text comparison",
}


def json_columns_hint(names):
    """What a standalone MySQL-side run tells the operator to pass to the
    ClickHouse side, which cannot tell a String column that replicates a MySQL
    JSON column from any other String (spec 13.06 D-13.06-41). The checksum
    driver forwards --json_columns itself and drops this text from the side
    notes it relays (``JSON_COLUMNS_HINT_RE``)."""
    return f"pass --json_columns {','.join(names)} to clickhouse_table_checksum.py so both row strings skip them"


# The json_columns_hint text inside a relayed side line, with its leading "; ".
JSON_COLUMNS_HINT_RE = re.compile(r"; pass --json_columns .*? to clickhouse_table_checksum\.py so both row strings "
                                  r"skip them")


def warn_not_compared(database, table, skipped, warned, json_hint=False):
    """One WARNING per table and kind naming the number and the names of the
    columns the tool does not compare (spec 11.02 section 3.9). ``skipped`` maps
    a kind of NOT_COMPARED_HINTS to column names; ``warned`` is the caller's set
    of (database, table, kind) already reported, so chunked tables warn once.
    The driver relays every side WARNING line into its own log at INFO (spec
    13.06 FM-13.06-8) and names the columns again in its run summary (spec
    11.02 section 3.9). ``json_hint`` (the MySQL side) adds json_columns_hint
    to the JSON warning."""
    for kind, names in skipped.items():
        if names and (database, table, kind) not in warned:
            warned.add((database, table, kind))
            hint = NOT_COMPARED_HINTS[kind]
            if json_hint and kind == "JSON":
                hint += "; " + json_columns_hint(names)
            count = len(names)
            noun = "column" if count == 1 else "columns"
            logging.warning(f"Not compared in table {database}.{table}: {count} {kind} {noun} {names} (pass {hint})")


def parse_exclude_columns(tokens):
    """The column names given to ``--exclude_columns``, as space-separated
    words, comma-separated lists or both: ``['a', 'b']``, ``['a,b']`` and
    ``['a, b']`` all give ``['a', 'b']``. Both sides parse the option with this
    function, so both forms exclude the same columns (spec 13.06 D-13.06-17).
    Order is kept, duplicates are dropped."""
    names = []
    for token in tokens or []:
        for name in str(token).split(","):
            name = name.strip()
            if name and name not in names:
                names.append(name)
    return names


def parse_column_list(text):
    """``'a, b,'`` -> ``{'a', 'b'}``; ``None``/``''`` -> empty set."""
    return set(name.strip() for name in (text or "").split(",") if name.strip())

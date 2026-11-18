package com.altinity.clickhouse.sink.connector.common;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single resolver for the two timezone properties (spec 07.03 section 3.1.2).
 * <ul>
 *   <li>{@code clickhouse.datetime.timezone} ({@link Kind#CLICKHOUSE}): an IANA region id only.</li>
 *   <li>{@code database.connectionTimeZone} ({@link Kind#SOURCE}): an IANA region id or a
 *       {@code [+-]HH:MM} offset.</li>
 * </ul>
 * Both properties are mandatory. An unset value (null, empty or whitespace-only) is never resolved to a zone:
 * {@link #requireConfigured} and {@link #effective} refuse it with a message that names the key and a suggested
 * value. There is no default zone, no fallback to the JVM, host, ClickHouse server or MySQL session zone.
 */
public final class TimeZoneResolver {

    public static final String CLICKHOUSE_DATETIME_TIMEZONE = "clickhouse.datetime.timezone";
    public static final String SOURCE_CONNECTION_TIMEZONE = "database.connectionTimeZone";

    /** The value named in every refusal for an unset key. It is a suggestion; the operator chooses the zone. */
    public static final String SUGGESTED_VALUE = "UTC";

    /** The mandatory keys, in the order they are reported (the ConfigDef definition order). */
    private static final String[] REQUIRED = {CLICKHOUSE_DATETIME_TIMEZONE, SOURCE_CONNECTION_TIMEZONE};

    /** The zone a set value resolves to, for one property. */
    public record Effective(String property, ZoneId zone) {
    }

    /** Any tzdata of a working JVM contains this id; its absence means tzdata is missing. */
    static final String TZDATA_PROBE_ID = "America/Chicago";

    private static final Pattern OFFSET = Pattern.compile("[+-]\\d{2}:\\d{2}");

    /** Keywords that look like zones to a human but are not region ids. */
    private static final Set<String> KEYWORDS = Set.of("SYSTEM", "LOCAL", "Z", "SERVER");

    /**
     * Abbreviations that are not region ids, with the region ids an operator should use instead. The fixed-offset
     * and DST-aware choices differ, so both are named.
     */
    private static final Map<String, String> ABBREVIATIONS = Map.of(
            "EST", "America/New_York (DST-aware) or Etc/GMT+5 (fixed UTC-05:00, no DST)",
            "EDT", "America/New_York (DST-aware)",
            "CST", "America/Chicago (US Central, DST-aware), Etc/GMT+6 (fixed UTC-06:00, no DST) or Asia/Shanghai (fixed UTC+08:00)",
            "CDT", "America/Chicago (US Central, DST-aware)",
            "MST", "America/Denver (DST-aware) or Etc/GMT+7 (fixed UTC-07:00, no DST)",
            "MDT", "America/Denver (DST-aware)",
            "PST", "America/Los_Angeles (DST-aware)",
            "PDT", "America/Los_Angeles (DST-aware)",
            "HST", "Pacific/Honolulu (fixed UTC-10:00, no DST) or Etc/GMT+10");

    public enum Kind {
        CLICKHOUSE,
        SOURCE
    }

    private TimeZoneResolver() {
    }

    /** A zone property that cannot be resolved. Carries the property name and the raw value. */
    public static final class InvalidTimeZoneException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String property;
        private final String rawValue;
        private final String reason;

        public InvalidTimeZoneException(String property, String rawValue, String reason) {
            this(property, rawValue, reason,
                    "Invalid timezone for " + property + "='" + rawValue + "': " + reason);
        }

        private InvalidTimeZoneException(String property, String rawValue, String reason, String message) {
            super(message);
            this.property = property;
            this.rawValue = rawValue;
            this.reason = reason;
        }

        public String getProperty() {
            return property;
        }

        public String getRawValue() {
            return rawValue;
        }

        public String getReason() {
            return reason;
        }
    }

    /** True for null, empty or whitespace-only values. */
    public static boolean isUnset(String raw) {
        return raw == null || raw.trim().isEmpty();
    }

    /**
     * Refuses a map in which either mandatory zone key is unset (absent, empty or blank). The refusal names the key
     * and the suggested value.
     *
     * @throws InvalidTimeZoneException naming the first unset key
     */
    public static void requireConfigured(Map<?, ?> props) {
        for (String key : REQUIRED) {
            Object value = props == null ? null : props.get(key);
            if (value == null || isUnset(String.valueOf(value))) {
                throw unset(key);
            }
        }
    }

    /** The region ids this JVM knows. Empty or missing {@value #TZDATA_PROBE_ID} means no tzdata. */
    public static Set<String> availableZoneIds() {
        return ZoneId.getAvailableZoneIds();
    }

    /**
     * The zone of a set property.
     *
     * @throws InvalidTimeZoneException when the value is unset, or set but is not a zone for this property
     */
    public static Effective effective(String property, String raw, Kind kind) {
        return new Effective(property, resolve(property, raw, kind));
    }

    /** The ClickHouse-side zone (spec 07.03 section 3.1.2): the configured zone, refused when unset. */
    public static ZoneId clickHouseZone(String raw) {
        return resolve(CLICKHOUSE_DATETIME_TIMEZONE, raw, Kind.CLICKHOUSE);
    }

    /**
     * Resolves a raw property value.
     *
     * @return the zone; never null
     * @throws InvalidTimeZoneException when the value is unset, or set but is not a zone for this property
     */
    public static ZoneId resolve(String property, String raw, Kind kind) {
        if (isUnset(raw)) {
            throw unset(property);
        }
        if (!raw.equals(raw.trim())) {
            throw new InvalidTimeZoneException(property, raw,
                    "surrounding whitespace is not allowed; remove it");
        }
        Set<String> ids = availableZoneIds();
        if (ids.isEmpty() || !ids.contains(TZDATA_PROBE_ID)) {
            throw new InvalidTimeZoneException(property, raw,
                    "this JVM has no timezone data (tzdata missing); install tzdata for the JVM");
        }
        if (kind == Kind.SOURCE && OFFSET.matcher(raw).matches()) {
            try {
                return ZoneOffset.of(raw);
            } catch (DateTimeException e) {
                throw new InvalidTimeZoneException(property, raw, "not a valid offset");
            }
        }
        if (KEYWORDS.contains(raw)) {
            throw new InvalidTimeZoneException(property, raw, keywordReason(raw));
        }
        if (!ids.contains(raw)) {
            throw new InvalidTimeZoneException(property, raw, unknownReason(raw, kind));
        }
        return ZoneId.of(raw);
    }

    /** The refusal for a unset key: names the key, the suggested value, and why there is no default. */
    static InvalidTimeZoneException unset(String property) {
        String message = property + " is not set. Both zone settings are mandatory and have no default: set "
                + property + "=" + SUGGESTED_VALUE + " (or another IANA region id such as America/Chicago)"
                + " in the connector configuration.";
        return new InvalidTimeZoneException(property, "", "is not set", message);
    }

    private static String keywordReason(String raw) {
        if ("SERVER".equals(raw)) {
            return "SERVER makes the zone depend on the MySQL session, which is refused; set an explicit IANA region "
                    + "id such as " + SUGGESTED_VALUE;
        }
        return "is a keyword, not a zone id; set an explicit IANA region id such as " + SUGGESTED_VALUE;
    }

    private static String unknownReason(String raw, Kind kind) {
        if (OFFSET.matcher(raw).matches() || raw.startsWith("+") || raw.startsWith("-")) {
            return kind == Kind.SOURCE
                    ? "not a valid offset; use [+-]HH:MM or an IANA region id"
                    : "offsets are not accepted for this key; use an IANA region id such as America/Chicago";
        }
        String candidates = ABBREVIATIONS.get(raw.toUpperCase(Locale.ROOT));
        if (candidates != null) {
            return "'" + raw + "' is an abbreviation, not a zone id; use one of: " + candidates;
        }
        return "not an IANA region id known to this JVM (names are case-sensitive); use a region id such as "
                + "America/Chicago";
    }

    /**
     * Refuses a property whose name equals a zone key ignoring case but is not that key
     * (for example {@code database.connectionTimezone}, which Kafka would otherwise ignore silently).
     */
    public static void rejectMisspelledKeys(Map<?, ?> props) {
        if (props == null) {
            return;
        }
        for (String canonical : REQUIRED) {
            for (Map.Entry<?, ?> e : props.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (!key.equals(canonical) && key.equalsIgnoreCase(canonical)) {
                    throw new InvalidTimeZoneException(canonical, String.valueOf(e.getValue()),
                            "property '" + key + "' is misspelled and would be ignored; the key is '" + canonical + "'");
                }
            }
        }
    }

    /**
     * Refuses a set zone property with surrounding whitespace. Kafka trims STRING values before validation,
     * so this reads the raw map the caller was given.
     */
    public static void rejectUntrimmed(Map<?, ?> props) {
        if (props == null) {
            return;
        }
        for (String name : REQUIRED) {
            Object value = props.get(name);
            if (value == null) {
                continue;
            }
            String raw = String.valueOf(value);
            if (!isUnset(raw) && !raw.equals(raw.trim())) {
                throw new InvalidTimeZoneException(name, raw, "surrounding whitespace is not allowed; remove it");
            }
        }
    }
}

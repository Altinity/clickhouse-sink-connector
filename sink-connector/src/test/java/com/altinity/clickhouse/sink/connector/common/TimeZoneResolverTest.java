package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver.InvalidTimeZoneException;
import com.altinity.clickhouse.sink.connector.common.TimeZoneResolver.Kind;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.03 §3.1.6: one resolver decides what a configured zone is.
 */
public class TimeZoneResolverTest {

    private static final String CH = TimeZoneResolver.CLICKHOUSE_DATETIME_TIMEZONE;
    private static final String SRC = TimeZoneResolver.SOURCE_CONNECTION_TIMEZONE;

    @Test
    public void nullEmptyAndBlankAreUnset() {
        assertTrue(TimeZoneResolver.isUnset(null));
        assertTrue(TimeZoneResolver.isUnset(""));
        assertTrue(TimeZoneResolver.isUnset("   "));
        // An unset value is refused, never resolved to a zone or to null.
        assertTrue(refusal(CH, null).contains(CH + " is not set"));
        assertTrue(refusal(CH, "").contains(CH + " is not set"));
        assertTrue(refusal(SRC, "  \t ").contains(SRC + " is not set"));
    }

    @Test
    public void regionIdsResolveToTheSameIdForBothKinds() {
        for (String id : new String[]{"America/Chicago", "US/Central", "UTC", "GMT", "Etc/GMT+5", "Asia/Kolkata"}) {
            assertEquals(ZoneId.of(id), TimeZoneResolver.resolve(CH, id, Kind.CLICKHOUSE), id);
            assertEquals(ZoneId.of(id), TimeZoneResolver.resolve(SRC, id, Kind.SOURCE), id);
        }
    }

    @Test
    public void serverKeywordIsNotAZone() {
        // SERVER is the Connector/J "derive from the source" keyword, handled by the preflight (§3.1.5).
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(SRC, "SERVER", Kind.SOURCE));
    }

    @Test
    public void refusesAbbreviationsKeywordsAndZUnambiguously() {
        for (String bad : new String[]{"EST", "CST", "CDT", "PST", "SYSTEM", "LOCAL", "Z"}) {
            assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(CH, bad, Kind.CLICKHOUSE), bad);
            assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(SRC, bad, Kind.SOURCE), bad);
        }
    }

    @Test
    public void refusesPrefixFormsAndWrongCase() {
        for (String bad : new String[]{"UTC+5", "GMT-3", "america/chicago", "AMERICA/CHICAGO", "Chicago"}) {
            assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(CH, bad, Kind.CLICKHOUSE), bad);
            assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(SRC, bad, Kind.SOURCE), bad);
        }
    }

    @Test
    public void offsetsAreRefusedForClickHouseAndAcceptedForSource() {
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(CH, "+05:30", Kind.CLICKHOUSE));
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(CH, "-06:00", Kind.CLICKHOUSE));
        assertEquals(ZoneOffset.of("+05:30"), TimeZoneResolver.resolve(SRC, "+05:30", Kind.SOURCE));
        assertEquals(ZoneOffset.of("-06:00"), TimeZoneResolver.resolve(SRC, "-06:00", Kind.SOURCE));
        // Only the HH:MM form is an offset; the bare Java "Z" and "+5" are not.
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(SRC, "+5", Kind.SOURCE));
    }

    @Test
    public void surroundingWhitespaceOnTheRawValueIsRefused() {
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(CH, " America/Chicago", Kind.CLICKHOUSE));
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(CH, "America/Chicago ", Kind.CLICKHOUSE));
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.resolve(SRC, "\tUS/Central", Kind.SOURCE));
    }

    @Test
    public void exceptionNamesThePropertyAndTheRawValue() {
        InvalidTimeZoneException e = assertThrows(InvalidTimeZoneException.class,
                () -> TimeZoneResolver.resolve(CH, "CDT", Kind.CLICKHOUSE));
        assertEquals(CH, e.getProperty());
        assertEquals("CDT", e.getRawValue());
        assertTrue(e.getMessage().contains(CH), e.getMessage());
    }

    @Test
    public void misspelledZoneKeysAreRefused() {
        Map<String, String> props = new HashMap<>();
        props.put("database.connectionTimezone", "US/Central");   // the spelling doc/timezone.md used
        InvalidTimeZoneException e = assertThrows(InvalidTimeZoneException.class,
                () -> TimeZoneResolver.rejectMisspelledKeys(props));
        assertEquals(SRC, e.getProperty());

        Map<String, String> ch = new HashMap<>();
        ch.put("clickhouse.datetime.timeZone", "UTC");
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.rejectMisspelledKeys(ch));
    }

    @Test
    public void correctKeysPassTheMisspellingCheck() {
        Map<String, String> props = new HashMap<>();
        props.put(SRC, "US/Central");
        props.put(CH, "America/Chicago");
        props.put("database.hostname", "mysql");
        TimeZoneResolver.rejectMisspelledKeys(props);
    }

    @Test
    public void rawWhitespaceCheckReadsTheUntrimmedProperties() {
        Map<String, String> props = new HashMap<>();
        props.put(CH, " America/Chicago");
        assertThrows(InvalidTimeZoneException.class, () -> TimeZoneResolver.rejectUntrimmed(props));

        Map<String, String> blank = new HashMap<>();
        blank.put(CH, "   ");   // blank is unset, not an error
        TimeZoneResolver.rejectUntrimmed(blank);
    }

    @Test
    public void unsetZoneRefusesNamingTheKeyAndASuggestedValue() {
        // Both keys are mandatory (spec 07.03 section 3.1.2), and there is no default zone for either of them.
        for (String property : new String[]{CH, SRC}) {
            Kind kind = property.equals(CH) ? Kind.CLICKHOUSE : Kind.SOURCE;
            for (String raw : new String[]{null, "", "   "}) {
                InvalidTimeZoneException e = assertThrows(InvalidTimeZoneException.class,
                        () -> TimeZoneResolver.effective(property, raw, kind));
                assertTrue(e.getMessage().contains(property + " is not set"), e.getMessage());
                assertTrue(e.getMessage().contains(property + "=UTC"), e.getMessage());
            }
        }
    }

    @Test
    public void requireConfiguredRefusesEitherUnsetKey() {
        Map<String, String> onlyClickHouse = new HashMap<>();
        onlyClickHouse.put(CH, "UTC");
        InvalidTimeZoneException missingSource = assertThrows(InvalidTimeZoneException.class,
                () -> TimeZoneResolver.requireConfigured(onlyClickHouse));
        assertTrue(missingSource.getMessage().contains(SRC + " is not set"), missingSource.getMessage());

        Map<String, String> onlySource = new HashMap<>();
        onlySource.put(SRC, "UTC");
        InvalidTimeZoneException missingClickHouse = assertThrows(InvalidTimeZoneException.class,
                () -> TimeZoneResolver.requireConfigured(onlySource));
        assertTrue(missingClickHouse.getMessage().contains(CH + " is not set"), missingClickHouse.getMessage());

        Map<String, String> both = new HashMap<>();
        both.put(CH, "UTC");
        both.put(SRC, "UTC");
        TimeZoneResolver.requireConfigured(both);
    }

    @Test
    public void abbreviationRefusalNamesTheRegionIdsItCouldMean() {
        // EST, MST, HST and CST each name more than one zone; the refusal must say which ids the operator meant.
        assertTrue(refusal(CH, "EST").contains("America/New_York"), refusal(CH, "EST"));
        assertTrue(refusal(CH, "MST").contains("America/Denver"), refusal(CH, "MST"));
        assertTrue(refusal(CH, "HST").contains("Pacific/Honolulu"), refusal(CH, "HST"));
        assertTrue(refusal(CH, "CST").contains("America/Chicago"), refusal(CH, "CST"));
        assertTrue(refusal(CH, "CST").contains("Etc/GMT+6"), refusal(CH, "CST"));
    }

    private static String refusal(String property, String raw) {
        return assertThrows(InvalidTimeZoneException.class,
                () -> TimeZoneResolver.resolve(property, raw, Kind.CLICKHOUSE)).getMessage();
    }

    @Test
    public void tzdataIsPresentInThisJvm() {
        // A JVM without tzdata fails loudly at startup (§3.1.6); this JVM must have it for the rest of the suite.
        assertTrue(TimeZoneResolver.availableZoneIds().contains("America/Chicago"));
    }
}

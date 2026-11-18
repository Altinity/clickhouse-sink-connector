package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Start-time and runtime validation of {@code ignore.ddl.regex} (spec 01.03 section 7, FM-01.03-2).
 * An entry that does not compile, or a blank entry (which matches every DDL), must never reach the
 * DDL path silently.
 */
public class IgnoreDdlRegexPreflightTest {

    private static Properties withRegex(String value) {
        Properties props = new Properties();
        props.setProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX, value);
        return props;
    }

    @Test
    @DisplayName("An unset or empty ignore.ddl.regex is accepted and has no rules")
    public void unsetOrEmptyIsAccepted() {
        assertDoesNotThrow(() -> IgnoreDdlRegexPreflight.check(new Properties()));
        assertDoesNotThrow(() -> IgnoreDdlRegexPreflight.check(withRegex("")));
        assertEquals(0, IgnoreDdlRegexPreflight.entries(withRegex("")).length);
    }

    @Test
    @DisplayName("Valid rules separated by || are accepted in order")
    public void validRulesAreAccepted() {
        Properties props = withRegex("^ALTER TABLE .* ADD COLUMN .*||(?i)^CREATE\\s+VIEW\\s+");
        assertDoesNotThrow(() -> IgnoreDdlRegexPreflight.check(props));
        assertEquals(2, IgnoreDdlRegexPreflight.entries(props).length);
    }

    @Test
    @DisplayName("A rule that does not compile refuses the start, naming the entry")
    public void nonCompilingRuleRefusesTheStart() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> IgnoreDdlRegexPreflight.check(withRegex("^CREATE VIEW||(?i)^CREATE\\s+VIEW(")));
        assertTrue(refused.getMessage().contains("Refusing to start"), refused.getMessage());
        assertTrue(refused.getMessage().contains("(?i)^CREATE\\s+VIEW("), refused.getMessage());
        assertTrue(refused.getCause() != null, "the PatternSyntaxException must be kept as the cause");
    }

    @Test
    @DisplayName("A leading or doubled || (a blank rule) refuses the start instead of matching every DDL")
    public void blankRuleRefusesTheStart() {
        assertThrows(IllegalStateException.class, () -> IgnoreDdlRegexPreflight.check(withRegex("||^CREATE VIEW")));
        assertThrows(IllegalStateException.class,
                () -> IgnoreDdlRegexPreflight.check(withRegex("^CREATE VIEW||   ||^ALTER TABLE")));
        assertThrows(IllegalStateException.class, () -> IgnoreDdlRegexPreflight.check(withRegex("   ")));
    }

    @Test
    @DisplayName("A runtime rule that does not compile throws DDLReplicationException, never returns a pattern")
    public void runtimeNonCompilingRuleThrowsDdlReplicationException() {
        DDLReplicationException thrown = assertThrows(DDLReplicationException.class,
                () -> IgnoreDdlRegexPreflight.compile("(?i)^CREATE\\s+VIEW("));
        assertTrue(thrown.getMessage().contains("(?i)^CREATE\\s+VIEW("), thrown.getMessage());
        assertTrue(thrown.getCause() instanceof java.util.regex.PatternSyntaxException);
    }

    @Test
    @DisplayName("A trailing || is dropped by the split, as it always was, and is not a blank rule")
    public void trailingSeparatorIsNotABlankRule() {
        assertDoesNotThrow(() -> IgnoreDdlRegexPreflight.check(withRegex("^CREATE VIEW||")));
        assertEquals(1, IgnoreDdlRegexPreflight.entries(withRegex("^CREATE VIEW||")).length);
    }
}

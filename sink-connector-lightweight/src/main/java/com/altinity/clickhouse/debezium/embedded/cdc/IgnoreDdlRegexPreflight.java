package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.debezium.embedded.config.SinkConnectorLightWeightConfig;

import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * {@code ignore.ddl.regex}: the operator's DDL ignore rules.
 *
 * <p>The property is a list of regular expressions separated by {@code ||}. The runtime splits it
 * the same way {@link #entries(Properties)} does, so a rule is validated exactly as it will be used.</p>
 *
 * <ul>
 *   <li>Start ({@link #check(Properties)}): every entry must compile and must not be blank. An entry
 *       that does not compile refuses the start, naming the entry and the syntax error. A blank entry
 *       (for example a leading {@code ||}) compiles to a pattern that matches every DDL, so it is
 *       refused too.</li>
 *   <li>Runtime ({@link #compile(String)}): an entry that cannot be compiled throws
 *       {@link DDLReplicationException}. The DDL cannot be classified, so it must halt the engine
 *       rather than be logged and dropped (spec 01.03 section 7, FM-01.03-2).</li>
 * </ul>
 */
public final class IgnoreDdlRegexPreflight {

    /** The separator between rules: two pipes. */
    static final String SEPARATOR_REGEX = "\\|\\|";

    private IgnoreDdlRegexPreflight() {
    }

    /**
     * The rules in evaluation order, split exactly as the runtime splits them. Empty when the property
     * is unset or empty.
     */
    public static String[] entries(Properties props) {
        String raw = props == null ? null : props.getProperty(SinkConnectorLightWeightConfig.IGNORE_DDL_REGEX);
        if (raw == null || raw.isEmpty()) {
            return new String[0];
        }
        return raw.split(SEPARATOR_REGEX);
    }

    /**
     * Start-time validation of every rule.
     *
     * @throws IllegalStateException naming the offending entry when a rule is blank or does not compile
     */
    public static void check(Properties props) {
        for (String entry : entries(props)) {
            try {
                compile(entry);
            } catch (DDLReplicationException e) {
                throw new IllegalStateException("Refusing to start: " + e.getMessage(), e.getCause());
            }
        }
    }

    /**
     * Compiles one rule for evaluation against a DDL.
     *
     * @throws DDLReplicationException when the rule is blank or does not compile; the DDL it was to
     *                                 classify must not be dropped
     */
    static Pattern compile(String entry) {
        if (entry.trim().isEmpty()) {
            throw new DDLReplicationException(
                    "ignore.ddl.regex has a blank rule, which would match every DDL. Remove the stray "
                            + "'||' separator.", null);
        }
        try {
            return Pattern.compile(entry);
        } catch (PatternSyntaxException e) {
            throw new DDLReplicationException(
                    "ignore.ddl.regex rule [" + entry + "] does not compile: " + e.getDescription()
                            + " at index " + e.getIndex() + ".", e);
        }
    }
}

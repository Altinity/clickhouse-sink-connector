package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Debezium's {@code enable.time.adjuster} must be forced off unless the user
 * set it (spec 07.03 §3.4).
 *
 * <p><b>The defect.</b> Debezium's default is {@code true}: a two-digit year
 * (and, on the MySQL connector, any year below 100) is remapped into
 * 1970–2069, so a source value of {@code 0001-01-01} arrived as
 * {@code 2001-01-01} — a value-level divergence with row counts intact. Only
 * the Ansible template disabled it; the JAR, the Docker configs and every
 * hand-written configuration ran with the adjuster on.</p>
 */
public class TimeAdjusterDefaultTest {

    @Test
    @DisplayName("When the user has not set enable.time.adjuster it is forced to false")
    public void absentIsForcedToFalse() {
        Properties props = new Properties();

        DebeziumChangeEventCapture.ensureTimeAdjusterDisabled(props);

        assertEquals("false", props.getProperty(DebeziumChangeEventCapture.ENABLE_TIME_ADJUSTER),
                "the Debezium default (true) rewrites years below 100, e.g. 0001-01-01 -> 2001-01-01");
    }

    @Test
    @DisplayName("A blank value counts as unset")
    public void blankIsForcedToFalse() {
        Properties props = new Properties();
        props.setProperty(DebeziumChangeEventCapture.ENABLE_TIME_ADJUSTER, "  ");

        DebeziumChangeEventCapture.ensureTimeAdjusterDisabled(props);

        assertEquals("false", props.getProperty(DebeziumChangeEventCapture.ENABLE_TIME_ADJUSTER));
    }

    @Test
    @DisplayName("An explicit user value is left alone, even true")
    public void explicitValueWins() {
        Properties props = new Properties();
        props.setProperty(DebeziumChangeEventCapture.ENABLE_TIME_ADJUSTER, "true");

        DebeziumChangeEventCapture.ensureTimeAdjusterDisabled(props);

        assertEquals("true", props.getProperty(DebeziumChangeEventCapture.ENABLE_TIME_ADJUSTER),
                "an explicit setting is the operator's call");
    }

    @Test
    @DisplayName("A null Properties is tolerated")
    public void nullIsTolerated() {
        DebeziumChangeEventCapture.ensureTimeAdjusterDisabled(null);
        assertNull(null);
    }
}

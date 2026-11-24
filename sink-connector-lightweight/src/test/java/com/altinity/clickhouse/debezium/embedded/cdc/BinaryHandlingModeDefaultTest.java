package com.altinity.clickhouse.debezium.embedded.cdc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code binary.handling.mode} defaults to {@code base64} unless the user set
 * it (spec 07.05 §3.2).
 *
 * <p><b>Why.</b> Debezium's own default is {@code bytes}, which this connector
 * renders as lower-case hex text, while the Python loader and the checksum
 * tools used base64. A deployment with no {@code binary.handling.mode} in its
 * properties therefore stored one text and compared another. The connector now
 * applies the same base64 mode the other components default to, and leaves an
 * explicit value alone.</p>
 */
public class BinaryHandlingModeDefaultTest {

    @Test
    @DisplayName("When the user has not set binary.handling.mode it defaults to base64")
    public void absentDefaultsToBase64() {
        Properties props = new Properties();

        DebeziumChangeEventCapture.ensureBinaryHandlingMode(props);

        assertEquals("base64", props.getProperty(DebeziumChangeEventCapture.BINARY_HANDLING_MODE),
                "Debezium's own default (bytes) is hex text here, which the loader and the checksum tools do not default to");
    }

    @Test
    @DisplayName("A blank value counts as unset")
    public void blankDefaultsToBase64() {
        Properties props = new Properties();
        props.setProperty(DebeziumChangeEventCapture.BINARY_HANDLING_MODE, "  ");

        DebeziumChangeEventCapture.ensureBinaryHandlingMode(props);

        assertEquals("base64", props.getProperty(DebeziumChangeEventCapture.BINARY_HANDLING_MODE));
    }

    @Test
    @DisplayName("An explicit user value is left alone")
    public void explicitValueWins() {
        for (String configured : new String[]{"bytes", "hex", "decode"}) {
            Properties props = new Properties();
            props.setProperty(DebeziumChangeEventCapture.BINARY_HANDLING_MODE, configured);

            DebeziumChangeEventCapture.ensureBinaryHandlingMode(props);

            assertEquals(configured, props.getProperty(DebeziumChangeEventCapture.BINARY_HANDLING_MODE),
                    "an explicit setting is the operator's call");
        }
    }

    @Test
    @DisplayName("The default is the value the loader and the checksum tools default to")
    public void defaultIsTheSharedValue() {
        assertEquals("base64", DebeziumChangeEventCapture.DEFAULT_BINARY_HANDLING_MODE);
    }

    @Test
    @DisplayName("A null Properties is tolerated")
    public void nullIsTolerated() {
        DebeziumChangeEventCapture.ensureBinaryHandlingMode(null);
        assertNull(null);
    }
}

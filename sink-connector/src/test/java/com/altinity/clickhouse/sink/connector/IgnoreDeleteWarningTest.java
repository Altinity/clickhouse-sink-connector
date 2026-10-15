package com.altinity.clickhouse.sink.connector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 10.04 section 3.7: {@code ignore_delete=true} is loss by design and is
 * announced once at startup. This pins the predicate that decides whether the
 * warning applies.
 */
public class IgnoreDeleteWarningTest {

    private static Map<String, String> props(String value) {
        Map<String, String> props = new HashMap<>();
        if (value != null) {
            props.put(ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString(), value);
        }
        return props;
    }

    @Test
    @DisplayName("ignore_delete=true is detected however it is spelled")
    public void trueIsDetectedCaseAndSpaceInsensitively() {
        assertTrue(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(props("true")));
        assertTrue(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(props("TRUE")));
        assertTrue(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(props(" true ")));
        // The constructor path must not throw with the option set.
        Map<String, String> full = props("true");
        ClickHouseSinkConnectorConfig.setDefaultValues(full);
        assertTrue(new ClickHouseSinkConnectorConfig(full).getBoolean(
                ClickHouseSinkConnectorConfigVariables.IGNORE_DELETE.toString()));
    }

    @Test
    @DisplayName("unset, false or a null map does not trigger the warning")
    public void unsetFalseOrNullIsNot() {
        assertFalse(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(props(null)));
        assertFalse(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(props("false")));
        assertFalse(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(props("")));
        assertFalse(ClickHouseSinkConnectorConfig.isIgnoreDeleteEnabled(null));
    }
}

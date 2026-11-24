package com.altinity.clickhouse.debezium.embedded.config;

import java.util.Properties;

/** Preserves an explicit exclusion when the include is redundant (spec 10.08). */
public final class TableFilterConfig {
    private TableFilterConfig() {
    }

    /**
     * Removes only a universal include paired with a nonblank exclusion.
     * Property names come from the caller's Debezium fields, so the decision
     * has no dependency on an engine or driver and can be tested independently.
     *
     * @return true only when the redundant include was removed
     */
    public static boolean removeRedundantInclude(Properties props, String includeKey, String excludeKey) {
        String include = props.getProperty(includeKey);
        String exclude = props.getProperty(excludeKey);
        // HARDCODED-OK: this exact regex is the universal include being normalized.
        if (include != null && ".*".equals(include.trim()) && exclude != null && !exclude.isBlank()) {
            props.remove(includeKey);
            return true;
        }
        return false;
    }
}

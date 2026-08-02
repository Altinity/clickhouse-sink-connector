package com.altinity.clickhouse.sink.connector.db;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Class that maps overrides of column data types. This is done specifically
 * to work around a JDBC bug which enforces limits check on UTC timezone.
 */
public class ColumnOverrides {

    /**
     * Map of specific data type strings to their overridden forms.
     */
    static Map<String, String> columnOverridesMap;

    static {
        // Use TreeMap sorted by descending key length so that longer/more-specific
        // keys like "Nullable(DateTime" are checked before shorter keys like "DateTime".
        // This prevents DateTime64 columns from incorrectly matching the "DateTime" key.
        columnOverridesMap = new TreeMap<>(Comparator.comparingInt(String::length).reversed()
                .thenComparing(Comparator.naturalOrder()));
        columnOverridesMap.put("DateTime", "String");
        columnOverridesMap.put("Nullable(DateTime", "Nullable(String)");
        columnOverridesMap.put("DateTime", "String");
    }

    /**
     * Default constructor for {@link ColumnOverrides}.
     */
    public ColumnOverrides() {
        // No initialization needed here.
    }

    /**
     * Retrieves an override for a given data type if one exists.
     * <p>
     * For example, if the incoming data type contains "DateTime",
     * it might be mapped to "String" to circumvent certain JDBC
     * limitations.
     * </p>
     *
     * @param dataType The original data type string, e.g. "DateTime"
     *                 or "Nullable(DateTime(...))".
     * @return The overridden data type string if a matching key
     *         is found, or {@code null} if no override applies.
     */
    public static String getColumnOverride(String dataType) {
        if (dataType == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : columnOverridesMap.entrySet()) {
            String key = entry.getKey();
            if (dataType.contains(key)) {
                // Do not override DateTime64 — only plain DateTime needs the
                // String workaround.  DateTime64 is handled correctly by JDBC.
                if (key.equals("DateTime") && dataType.contains("DateTime64")) {
                    continue;
                }
                if (key.equals("Nullable(DateTime") && dataType.contains("DateTime64")) {
                    continue;
                }
                return entry.getValue();
            }
        }
        return null;
    }
}

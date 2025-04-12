package com.altinity.clickhouse.sink.connector.db;

import java.util.HashMap;
import java.util.Map;

/**
 * Class that maps overrides of column data types. This is done specifically
 * to work around a JDBC bug which enforces limits check on UTC timezone.
 */
public class ColumnOverrides {

    /**
     * Map of specific data type strings to their overridden forms.
     */
    static Map<String, String> columnOverridesMap = new HashMap<>();

    static {
        columnOverridesMap.put("DateTime", "String");
        columnOverridesMap.put("Nullable(DateTime", "Nullable(String)");
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
        for (String key : columnOverridesMap.keySet()) {
            if (dataType.contains(key)) {
                return columnOverridesMap.get(key);
            }
        }
        return null;
    }
}

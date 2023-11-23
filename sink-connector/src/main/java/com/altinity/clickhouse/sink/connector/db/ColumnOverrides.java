package com.altinity.clickhouse.sink.connector.db;

import java.util.HashMap;
import java.util.Map;

/**
 * Class that maps overrides of column data types.
 */
public class ColumnOverrides {

    static Map<String, String> columnOverridesMap = new HashMap<>();

    static {
        columnOverridesMap.put("DateTime", "String");
        columnOverridesMap.put("Nullable(DateTime", "Nullable(String)");
    }
    public ColumnOverrides() {

    }

    public static String getColumnOverride(String dataType) {
        for(String key: columnOverridesMap.keySet()){
            if(dataType.contains(key)) {
                return columnOverridesMap.get(key);
            }
        }

        return null;
    }
}

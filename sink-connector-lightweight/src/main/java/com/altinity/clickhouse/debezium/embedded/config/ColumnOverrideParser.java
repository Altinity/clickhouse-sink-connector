package com.altinity.clickhouse.debezium.embedded.config;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashMap;
import com.clickhouse.data.ClickHouseDataType;

public class ColumnOverrideParser {
    public static Map<String, String> parseColumnOverrides(String yamlFile) throws FileNotFoundException {

        Yaml yaml = new Yaml();
        FileInputStream inputStream = new FileInputStream(yamlFile);

        Map<String, Object> data = yaml.load(inputStream);

        Object result = data.get(ClickHouseSinkConnectorConfigVariables.DEFAULT_COLUMN_DATATYPE_MAPPING.toString());

                
        // if result is instance of LinkedHashMap , then cast it to LinkedHashMap
        if (result instanceof LinkedHashMap) {
            result = (LinkedHashMap<String, String>) result;
        }
        else {
            return new HashMap<>();
        }
        // Iterate through the map and convert values to ClickHouse data types
        Map<String, String> columnOverrides = new HashMap<>();
        for (Map.Entry<String, String> entry : ((Map<String, String>) result).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Match to ClickHouseDataType
            ClickHouseDataType clickHouseDataType = ClickHouseDataType.valueOf(value.toString());   

            columnOverrides.put(key, clickHouseDataType.toString());
        }
        
        return columnOverrides;
    }



}

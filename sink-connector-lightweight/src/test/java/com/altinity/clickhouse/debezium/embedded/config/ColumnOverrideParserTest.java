package com.altinity.clickhouse.debezium.embedded.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.Map;
import java.io.FileNotFoundException;   

public class ColumnOverrideParserTest {

    @Test
    public void testParseColumnOverrides() {
        String yamlFile = "src/test/resources/config.yml";
        try {
            Map<String, String> result = ColumnOverrideParser.parseColumnOverrides(yamlFile);
            Assertions.assertEquals(result.size(), 7);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }       
}

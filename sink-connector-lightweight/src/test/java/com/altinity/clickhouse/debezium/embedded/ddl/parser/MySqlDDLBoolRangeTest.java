package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import com.altinity.clickhouse.debezium.embedded.cdc.DebeziumChangeEventCapture;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Spec 07.01 FM-07.01-3: MySQL {@code BOOL} / {@code BOOLEAN} are synonyms
 * for {@code TINYINT(1)}; the column holds -128..127 and MySQL stores
 * {@code 2} or {@code -1} in it without complaint. The DDL translator
 * declares the ClickHouse column {@code Bool}, which stores every non-zero
 * integer as {@code true} (measured with {@code clickhouse local} 24.8.14:
 * {@code INSERT ... VALUES (5)} into {@code Bool} reads back {@code true},
 * {@code toUInt8} 1). The replica then holds 1 where MySQL holds 2, with row
 * counts intact. The column must be declared with the range of the source
 * type ({@code Int8}).
 */
public class MySqlDDLBoolRangeTest {

    private static MySQLDDLParserService parser;

    @BeforeAll
    static void init() {
        parser = new MySQLDDLParserService(new ClickHouseSinkConnectorConfig(new HashMap<>()), "employees");
        DebeziumChangeEventCapture.isNewReplacingMergeTreeEngine = true;
    }

    private static String translate(String ddl) {
        StringBuffer out = new StringBuffer();
        parser.parseSql(ddl, "employees", out);
        return out.toString();
    }

    /** TINYINT(1) itself keeps the signed 8-bit range (pins the correct half). */
    @Test
    @DisplayName("FM-07.01-3: TINYINT(1) is declared Int8")
    public void tinyintOneIsInt8() {
        String q = translate("ALTER TABLE t ADD COLUMN flag TINYINT(1)");
        assertEquals("ALTER TABLE `employees`.t ADD COLUMN IF NOT EXISTS flag Nullable(Int8)", q.trim());
    }

    @Test
    @Disabled("DEFECT FM-07.01-3: MySQL BOOL (TINYINT(1)) is declared Bool, which collapses 2..127 and -128..-1 "
            + "to true silently")
    @DisplayName("FM-07.01-3: BOOL is declared with the TINYINT(1) range, not Bool")
    public void boolIsDeclaredWithTheTinyintRange() {
        String q = translate("ALTER TABLE t ADD COLUMN flag BOOL");
        assertFalse(q.contains("Bool"), q);
        assertEquals("ALTER TABLE `employees`.t ADD COLUMN IF NOT EXISTS flag Nullable(Int8)", q.trim());
    }
}

package com.altinity.clickhouse.sink.connector.db;

import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec 04.02 failure modes: identifiers in the generated INSERT. MySQL allows
 * any character in a quoted identifier, including a space, a reserved word
 * and a backtick (written {@code ``} inside backticks). ClickHouse accepts
 * the same names in backticks when an embedded backtick is doubled or
 * backslash-escaped (measured with {@code clickhouse local} 24.8.14: a column
 * named {@code a`b} is created and inserted as {@code `a``b`}, while the
 * unescaped {@code `a`b`} fails with {@code Code: 62 SYNTAX_ERROR}).
 */
public class QueryFormatterIdentifierQuotingTest {

    private static MutablePair<String, Map<String, Integer>> insertFor(String table, String... columns) {
        Map<String, String> columnTypes = new LinkedHashMap<>();
        List<Field> fields = new ArrayList<>();
        int i = 0;
        for (String c : columns) {
            columnTypes.put(c, "Int32");
            fields.add(new Field(c, i++, Schema.INT32_SCHEMA));
        }
        return new QueryFormatter().getInsertQueryUsingInputFunction(table, fields, columnTypes,
                false, false, null, "db");
    }

    /** Names with a space and a reserved word are quoted and bound as parameters. */
    @Test
    @DisplayName("FM-04.02-1: a space or a reserved word in a column name is quoted")
    public void spaceAndReservedWordAreQuoted() {
        MutablePair<String, Map<String, Integer>> q = insertFor("orders", "Min Value", "select");
        assertEquals("INSERT INTO `orders`(`Min Value`,`select`) VALUES (?,?)", q.left);
        assertEquals(Integer.valueOf(1), q.right.get("Min Value"));
        assertEquals(Integer.valueOf(2), q.right.get("select"));
    }

    /**
     * FM-04.02-1: an embedded backtick is copied verbatim between the quoting
     * backticks, so the statement ClickHouse receives does not parse, and the
     * same statement is rebuilt on every retry.
     */
    @Test
    @Disabled("DEFECT FM-04.02-1: an embedded backtick in a table or column name is not escaped; ClickHouse "
            + "rejects the INSERT with Code 62 on every retry")
    @DisplayName("FM-04.02-1: a backtick inside a table or column name is escaped")
    public void backtickInIdentifierIsEscaped() {
        MutablePair<String, Map<String, Integer>> q = insertFor("or`ders", "a`b");
        assertEquals("INSERT INTO `or``ders`(`a``b`) VALUES (?)", q.left);
    }
}

package com.altinity.clickhouse.sink.connector.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.07 section 3.2.1: the ClickHouse session settings the connector
 * opens every connection with must disable {@code input_format_null_as_default}.
 *
 * <p>ClickHouse defaults that setting to {@code 1}, under which a NULL inserted
 * into a non-Nullable column is silently replaced by the column DEFAULT
 * ({@code 0}, {@code ''}, {@code 1970-01-01}). Binding the NULL correctly
 * (Spec 07.07 section 3.2) therefore protected nothing for such a column: the
 * server substituted the DEFAULT and reported success. Measured with
 * {@code clickhouse local} 24.8.14 on {@code v Int32 DEFAULT 7}: the row stores
 * {@code 7} under the server default and is rejected with
 * {@code Code: 53 TYPE_MISMATCH} under {@code input_format_null_as_default=0}.</p>
 */
public class JdbcCustomSettingsTest {

    private static List<String> settings(String csv) {
        return Arrays.asList(csv.split(","));
    }

    @Test
    @DisplayName("Default custom_settings disable input_format_null_as_default")
    public void defaultSettingsDisableNullAsDefault() {
        List<String> effective = settings(BaseDbWriter.customSettings(null));

        assertTrue(effective.contains("input_format_null_as_default=0"),
                "a bound NULL for a non-Nullable column must be rejected by the server, "
                        + "never replaced by the column DEFAULT; effective settings: " + effective);
        assertTrue(effective.contains("allow_experimental_object_type=1"), effective.toString());
        assertTrue(effective.contains("insert_allow_materialized_columns=1"), effective.toString());

        assertEquals(BaseDbWriter.customSettings(null), BaseDbWriter.customSettings(""),
                "an empty user list means the same as no user list");
    }

    @Test
    @DisplayName("User settings that do not mention the key get input_format_null_as_default=0 appended")
    public void userSettingsWithoutTheKeyGetItAppended() {
        assertEquals("async_insert=1,input_format_null_as_default=0",
                BaseDbWriter.customSettings("async_insert=1"));
    }

    @Test
    @DisplayName("A user list that sets the key explicitly is passed through unchanged")
    public void explicitUserSettingIsHonoured() {
        assertEquals("input_format_null_as_default=1,async_insert=1",
                BaseDbWriter.customSettings("input_format_null_as_default=1,async_insert=1"));
        assertEquals("async_insert=1, input_format_null_as_default=0",
                BaseDbWriter.customSettings("async_insert=1, input_format_null_as_default=0"));
    }
}

package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.common.Utils;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for schema-aware naming methods in {@link Utils}.
 * <p>
 * Covers: {@code extractSchemaFromTopic()}, {@code resolveSchemaTemplate()},
 * {@code getTableNameFromTopic(String, boolean, String)},
 * {@code isValidDatabasePrefix()}, {@code applyDatabasePrefix()},
 * {@code applyDatabaseSchemaSuffix()}, and {@code applyDatabaseNaming()}.
 */
public class UtilsSchemaAwareTest {

    // === extractSchemaFromTopic() ===

    @Test
    public void testExtractSchemaFromTopicThreeSegments() {
        assertEquals("public", Utils.extractSchemaFromTopic("prefix.public.orders"));
    }

    @Test
    public void testExtractSchemaFromTopicFourSegments() {
        // 4 segments: server1.mydb.public.orders → second-to-last = "public"
        assertEquals("public", Utils.extractSchemaFromTopic("server1.mydb.public.orders"));
    }

    @Test
    public void testExtractSchemaFromTopicTwoSegments() {
        // Only 2 segments — no schema available
        assertNull(Utils.extractSchemaFromTopic("prefix.orders"));
    }

    @Test
    public void testExtractSchemaFromTopicOneSegment() {
        assertNull(Utils.extractSchemaFromTopic("orders"));
    }

    @Test
    public void testExtractSchemaFromTopicNull() {
        assertNull(Utils.extractSchemaFromTopic(null));
    }

    // === resolveSchemaTemplate() ===

    @Test
    public void testResolveSchemaTemplateBasic() {
        assertEquals("__public__", Utils.resolveSchemaTemplate("__{{ schema }}__", "public"));
    }

    @Test
    public void testResolveSchemaTemplateCustom() {
        assertEquals("public.", Utils.resolveSchemaTemplate("{{ schema }}.", "public"));
    }

    @Test
    public void testResolveSchemaTemplateEmpty() {
        assertEquals("", Utils.resolveSchemaTemplate("", "public"));
    }

    @Test
    public void testResolveSchemaTemplateNull() {
        assertEquals("", Utils.resolveSchemaTemplate(null, "public"));
    }

    @Test
    public void testResolveSchemaTemplateNullSchema() {
        // When schema is null, the template is returned unchanged (placeholder not replaced)
        assertEquals("__{{ schema }}__", Utils.resolveSchemaTemplate("__{{ schema }}__", null));
    }

    // === getTableNameFromTopic(String, boolean, String) — 3-arg version ===

    @Test
    public void testTableNameWithSchemaPrefixEnabled() {
        // Boolean true, no template → hardcoded __schema__ format
        assertEquals("__public__orders",
            Utils.getTableNameFromTopic("prefix.public.orders", true, null));
    }

    @Test
    public void testTableNameWithSchemaPrefixEnabledEmptyTemplate() {
        // Boolean true, empty template → hardcoded __schema__ format
        assertEquals("__public__orders",
            Utils.getTableNameFromTopic("prefix.public.orders", true, ""));
    }

    @Test
    public void testTableNameWithTemplateOverridesHardcoded() {
        // Template takes precedence over hardcoded format when schemaPrefixEnabled is true
        assertEquals("public__orders",
            Utils.getTableNameFromTopic("prefix.public.orders", true, "{{ schema }}__"));
    }

    @Test
    public void testTableNameWithTemplatePrefixDisabledButTemplateSet() {
        // When schemaPrefixEnabled is false, template is NOT applied — just returns table name
        assertEquals("orders",
            Utils.getTableNameFromTopic("prefix.public.orders", false, "__{{ schema }}__"));
    }

    @Test
    public void testTableNameWithSchemaPrefixDisabled() {
        assertEquals("orders",
            Utils.getTableNameFromTopic("prefix.public.orders", false, null));
    }

    @Test
    public void testTableNameWithSchemaPrefixTwoSegments() {
        // Only 2 segments — no schema available, so no prefix even when enabled
        assertEquals("orders",
            Utils.getTableNameFromTopic("prefix.orders", true, null));
    }

    @Test
    public void testTableNameWithSchemaPrefixFourSegments() {
        // 4 segments: server1.mydb.public.orders → schema = "public", table = "orders"
        assertEquals("__public__orders",
            Utils.getTableNameFromTopic("server1.mydb.public.orders", true, null));
    }

    @Test
    public void testTableNameWithSchemaPrefixOneSegment() {
        // Only 1 segment — returns the topic name as-is
        assertEquals("orders",
            Utils.getTableNameFromTopic("orders", true, null));
    }

    @Test
    public void testTableNameNullTopic() {
        assertNull(Utils.getTableNameFromTopic(null, true, null));
    }

    // === isValidDatabasePrefix() ===

    @Test
    public void testValidDatabasePrefixAlphanumeric() {
        assertTrue(Utils.isValidDatabasePrefix("litellm_dev_"));
    }

    @Test
    public void testValidDatabasePrefixEmpty() {
        assertTrue(Utils.isValidDatabasePrefix(""));
    }

    @Test
    public void testValidDatabasePrefixNull() {
        assertTrue(Utils.isValidDatabasePrefix(null));
    }

    @Test
    public void testValidDatabasePrefixPureAlpha() {
        assertTrue(Utils.isValidDatabasePrefix("myPrefix"));
    }

    @Test
    public void testInvalidDatabasePrefixWithDash() {
        assertFalse(Utils.isValidDatabasePrefix("litellm-dev-"));
    }

    @Test
    public void testInvalidDatabasePrefixWithDot() {
        assertFalse(Utils.isValidDatabasePrefix("my.prefix"));
    }

    @Test
    public void testInvalidDatabasePrefixWithSpace() {
        assertFalse(Utils.isValidDatabasePrefix("my prefix"));
    }

    // === applyDatabasePrefix() ===

    @Test
    public void testApplyDatabasePrefix() {
        assertEquals("litellm_dev_app", Utils.applyDatabasePrefix("app", "litellm_dev_"));
    }

    @Test
    public void testApplyDatabasePrefixEmpty() {
        assertEquals("app", Utils.applyDatabasePrefix("app", ""));
    }

    @Test
    public void testApplyDatabasePrefixNull() {
        assertEquals("app", Utils.applyDatabasePrefix("app", null));
    }

    // === applyDatabaseSchemaSuffix() ===

    @Test
    public void testApplyDatabaseSchemaSuffix() {
        assertEquals("app__public__",
            Utils.applyDatabaseSchemaSuffix("app", "__{{ schema }}__", "public"));
    }

    @Test
    public void testApplyDatabaseSchemaSuffixEmpty() {
        assertEquals("app", Utils.applyDatabaseSchemaSuffix("app", "", "public"));
    }

    @Test
    public void testApplyDatabaseSchemaSuffixNull() {
        assertEquals("app", Utils.applyDatabaseSchemaSuffix("app", null, "public"));
    }

    // === applyDatabaseNaming() — combined prefix + suffix ===

    @Test
    public void testApplyDatabaseNamingBoth() {
        assertEquals("litellm_dev_app__public__",
            Utils.applyDatabaseNaming("app", "litellm_dev_", "__{{ schema }}__", "public"));
    }

    @Test
    public void testApplyDatabaseNamingPrefixOnly() {
        assertEquals("litellm_dev_app",
            Utils.applyDatabaseNaming("app", "litellm_dev_", "", "public"));
    }

    @Test
    public void testApplyDatabaseNamingSuffixOnly() {
        assertEquals("app__public__",
            Utils.applyDatabaseNaming("app", "", "__{{ schema }}__", "public"));
    }

    @Test
    public void testApplyDatabaseNamingNeither() {
        assertEquals("app", Utils.applyDatabaseNaming("app", "", "", "public"));
    }

    @Test
    public void testApplyDatabaseNamingNullPrefixAndSuffix() {
        assertEquals("app", Utils.applyDatabaseNaming("app", null, null, "public"));
    }
}

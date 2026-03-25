package com.altinity.clickhouse.sink.connector;

import com.altinity.clickhouse.sink.connector.common.Utils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

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

    static Stream<Arguments> extractSchemaFromTopicCases() {
        return Stream.of(
                Arguments.of("3 segments",  "prefix.public.orders",          "public"),
                Arguments.of("4 segments",  "server1.mydb.public.orders",    "public"),
                Arguments.of("2 segments",  "prefix.orders",                 null),
                Arguments.of("1 segment",   "orders",                        null),
                Arguments.of("null topic",  null,                            null)
        );
    }

    @ParameterizedTest(name = "extractSchemaFromTopic({1}) → {2} [{0}]")
    @MethodSource("extractSchemaFromTopicCases")
    @DisplayName("extractSchemaFromTopic()")
    public void testExtractSchemaFromTopic(String label, String topic, String expected) {
        assertEquals(expected, Utils.extractSchemaFromTopic(topic));
    }

    // === resolveSchemaTemplate() ===

    static Stream<Arguments> resolveSchemaTemplateCases() {
        return Stream.of(
                Arguments.of("basic template",       "__{{ schema }}__", "public", "__public__"),
                Arguments.of("custom template",      "{{ schema }}.",    "public", "public."),
                Arguments.of("empty template",       "",                 "public", ""),
                Arguments.of("null template",        null,               "public", ""),
                Arguments.of("null schema",          "__{{ schema }}__", null,     "__{{ schema }}__")
        );
    }

    @ParameterizedTest(name = "resolveSchemaTemplate({1}, {2}) → {3} [{0}]")
    @MethodSource("resolveSchemaTemplateCases")
    @DisplayName("resolveSchemaTemplate()")
    public void testResolveSchemaTemplate(String label, String template, String schema, String expected) {
        assertEquals(expected, Utils.resolveSchemaTemplate(template, schema));
    }

    // === getTableNameFromTopic(String, boolean, String) — 3-arg version ===

    static Stream<Arguments> getTableNameFromTopicCases() {
        return Stream.of(
                Arguments.of("prefix enabled, null template",
                        "prefix.public.orders", true, null, "__public__orders"),
                Arguments.of("prefix enabled, empty template",
                        "prefix.public.orders", true, "", "__public__orders"),
                Arguments.of("template overrides hardcoded",
                        "prefix.public.orders", true, "{{ schema }}__", "public__orders"),
                Arguments.of("prefix disabled, template set but ignored",
                        "prefix.public.orders", false, "__{{ schema }}__", "orders"),
                Arguments.of("prefix disabled, null template",
                        "prefix.public.orders", false, null, "orders"),
                Arguments.of("2 segments, no schema available",
                        "prefix.orders", true, null, "orders"),
                Arguments.of("4 segments",
                        "server1.mydb.public.orders", true, null, "__public__orders"),
                Arguments.of("1 segment",
                        "orders", true, null, "orders"),
                Arguments.of("null topic",
                        null, true, null, null)
        );
    }

    @ParameterizedTest(name = "getTableNameFromTopic({1}, {2}, {3}) → {4} [{0}]")
    @MethodSource("getTableNameFromTopicCases")
    @DisplayName("getTableNameFromTopic()")
    public void testGetTableNameFromTopic(String label, String topic, boolean schemaPrefixEnabled,
                                          String template, String expected) {
        assertEquals(expected, Utils.getTableNameFromTopic(topic, schemaPrefixEnabled, template));
    }

    // === isValidDatabasePrefix() ===

    static Stream<Arguments> isValidDatabasePrefixCases() {
        return Stream.of(
                Arguments.of("alphanumeric with underscore", "litellm_dev_", true),
                Arguments.of("empty string",                 "",             true),
                Arguments.of("null",                         null,           true),
                Arguments.of("pure alpha",                   "myPrefix",     true),
                Arguments.of("contains dash",                "litellm-dev-", false),
                Arguments.of("contains dot",                 "my.prefix",    false),
                Arguments.of("contains space",               "my prefix",    false)
        );
    }

    @ParameterizedTest(name = "isValidDatabasePrefix({1}) → {2} [{0}]")
    @MethodSource("isValidDatabasePrefixCases")
    @DisplayName("isValidDatabasePrefix()")
    public void testIsValidDatabasePrefix(String label, String prefix, boolean expected) {
        assertEquals(expected, Utils.isValidDatabasePrefix(prefix));
    }

    // === applyDatabasePrefix() ===

    static Stream<Arguments> applyDatabasePrefixCases() {
        return Stream.of(
                Arguments.of("with prefix",    "app", "litellm_dev_", "litellm_dev_app"),
                Arguments.of("empty prefix",   "app", "",             "app"),
                Arguments.of("null prefix",    "app", null,           "app")
        );
    }

    @ParameterizedTest(name = "applyDatabasePrefix({1}, {2}) → {3} [{0}]")
    @MethodSource("applyDatabasePrefixCases")
    @DisplayName("applyDatabasePrefix()")
    public void testApplyDatabasePrefix(String label, String database, String prefix, String expected) {
        assertEquals(expected, Utils.applyDatabasePrefix(database, prefix));
    }

    // === applyDatabaseSchemaSuffix() ===

    static Stream<Arguments> applyDatabaseSchemaSuffixCases() {
        return Stream.of(
                Arguments.of("with template",    "app", "__{{ schema }}__", "public", "app__public__"),
                Arguments.of("empty template",   "app", "",                "public", "app"),
                Arguments.of("null template",    "app", null,              "public", "app")
        );
    }

    @ParameterizedTest(name = "applyDatabaseSchemaSuffix({1}, {2}, {3}) → {4} [{0}]")
    @MethodSource("applyDatabaseSchemaSuffixCases")
    @DisplayName("applyDatabaseSchemaSuffix()")
    public void testApplyDatabaseSchemaSuffix(String label, String database, String template,
                                              String schema, String expected) {
        assertEquals(expected, Utils.applyDatabaseSchemaSuffix(database, template, schema));
    }

    // === applyDatabaseNaming() — combined prefix + suffix ===

    static Stream<Arguments> applyDatabaseNamingCases() {
        return Stream.of(
                Arguments.of("both prefix and suffix",
                        "app", "litellm_dev_", "__{{ schema }}__", "public", "litellm_dev_app__public__"),
                Arguments.of("prefix only",
                        "app", "litellm_dev_", "",                 "public", "litellm_dev_app"),
                Arguments.of("suffix only",
                        "app", "",             "__{{ schema }}__", "public", "app__public__"),
                Arguments.of("neither",
                        "app", "",             "",                 "public", "app"),
                Arguments.of("null prefix and suffix",
                        "app", null,           null,               "public", "app")
        );
    }

    @ParameterizedTest(name = "applyDatabaseNaming({1}, {2}, {3}, {4}) → {5} [{0}]")
    @MethodSource("applyDatabaseNamingCases")
    @DisplayName("applyDatabaseNaming()")
    public void testApplyDatabaseNaming(String label, String database, String prefix,
                                        String suffixTemplate, String schema, String expected) {
        assertEquals(expected, Utils.applyDatabaseNaming(database, prefix, suffixTemplate, schema));
    }
}

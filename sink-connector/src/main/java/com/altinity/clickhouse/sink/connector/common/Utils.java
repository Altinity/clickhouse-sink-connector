package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The Utils class provides utility methods for handling configuration,
 * parsing mappings, and validating table and database names. This includes
 * functions to parse mappings between topics and tables, as well as to validate
 * database names.
 */
public class Utils {

    /**
     * Logger instance for logging messages and errors.
     */
    private static final Logger LOGGER = LogManager.getLogger(Utils.class);

    /**
     * Task ID constant for task identification.
     */
    public static final String TASK_ID = "task_id";

    /**
     * Connector version, updated with each release.
     */
    public static final String VERSION = "1.0.0";

    /**
     * Parses the topic-to-destination database configuration and returns it as a map.
     * <p>
     * This method processes the input string in the format: <topic1>:<db1>,<topic2>:<db2>,...
     * It splits the input string by commas and colons to form key-value pairs, validating
     * each entry along the way. If any invalid format is found, an exception is thrown.
     * </p>
     *
     * @param input a comma-separated list of topic-to-destination database mappings.
     * @return a map where the keys are topics and the values are corresponding databases.
     * @throws Exception if the format of the input is invalid.
     */
    public static Map<String, String> parseSourceToDestinationDatabaseMap(String input) throws Exception {
        Map<String, String> srcToDestinationMap = new HashMap<>();
        boolean isInvalid = false;

        if (input == null || input.isEmpty()) {
            return srcToDestinationMap;
        }

        for (String str : input.split(",")) {
            String[] tt = str.split(":");

            if (tt.length != 2 || tt[0].trim().isEmpty() || tt[1].trim().isEmpty()) {
                LOGGER.error(
                        Logging.logMessage(
                                "Invalid {} config format: {}",
                                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE_OVERRIDE_MAP.toString(),
                                input));
                return null;
            }

            String srcDatabase = tt[0].trim();
            String dstDatabase = tt[1].trim();

            if (!isValidDatabaseName(dstDatabase)) {
                LOGGER.error(
                        Logging.logMessage(
                                "database name{} should have at least 2 "
                                        + "characters, start with _a-zA-Z, and only contains "
                                        + "_$a-zA-z0-9",
                                dstDatabase));
                isInvalid = true;
            }

            if (srcToDestinationMap.containsKey(srcDatabase)) {
                LOGGER.error(Logging.logMessage("source database name {} is duplicated", srcDatabase));
                isInvalid = true;
            }

            srcToDestinationMap.put(tt[0].trim(), tt[1].trim());
        }
        if (isInvalid) {
            throw new Exception("Invalid clickhouse table");
        }
        return srcToDestinationMap;
    }

    /**
     * Parses the topic-to-table configuration and returns it as a map.
     * <p>
     * This method processes the input string in the format: <topic1>:<table1>,<topic2>:<table2>,...
     * It splits the input string by commas and colons to form key-value pairs, validating
     * each entry along the way. If any invalid format is found, an exception is thrown.
     * </p>
     *
     * @param input a comma-separated list of topic-to-table mappings.
     * @return a map where the keys are topics and the values are corresponding tables.
     * @throws Exception if the format of the input is invalid.
     */
    public static Map<String, String> parseTopicToTableMap(String input) throws Exception {
        Map<String, String> topic2Table = new HashMap<>();
        boolean isInvalid = false;
        for (String str : input.split(",")) {
            String[] tt = str.split(":");

            if (tt.length != 2 || tt[0].trim().isEmpty() || tt[1].trim().isEmpty()) {
                LOGGER.error(
                        Logging.logMessage(
                                "Invalid {} config format: {}",
                                ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_TOPICS_TABLES_MAP.toString(),
                                input));
                return null;
            }

            String topic = tt[0].trim();
            String table = tt[1].trim();

            if (!isValidTable(table)) {
                LOGGER.error(
                        Logging.logMessage(
                                "table name {} should have at least 2 "
                                        + "characters, start with _a-zA-Z, and only contains "
                                        + "_$a-zA-z0-9",
                                table));
                isInvalid = true;
            }

            if (topic2Table.containsKey(topic)) {
                LOGGER.error(Logging.logMessage("topic name {} is duplicated", topic));
                isInvalid = true;
            }

            if (topic2Table.containsValue(table)) {
                LOGGER.error(Logging.logMessage("table name {} is duplicated", table));
                isInvalid = true;
            }
            topic2Table.put(tt[0].trim(), tt[1].trim());
        }
        if (isInvalid) {
            throw new Exception("Invalid clickhouse table");
        }
        return topic2Table;
    }

    /**
     * Extracts the table name from the given Kafka topic name.
     * <p>
     * The Kafka topic name is expected to be in the format:
     * hostname.dbName.tableName or hostname.dbName.schemaName.tableName.
     * The table name will be extracted from the last segment of the topic name.
     * </p>
     *
     * @param topicName the Kafka topic name.
     * @return the extracted table name.
     */
    public static String getTableNameFromTopic(String topicName) {
        return getTableNameFromTopic(topicName, false, null);
    }

    /**
     * Extracts the table name from the given Kafka topic name.
     * When schemaPrefix is true, returns {@code __<schema>__<table>} using
     * the second-to-last and last segments respectively.
     * <p>
     * This 2-arg overload delegates to the 3-arg version with a null template,
     * preserving backward compatibility.
     * </p>
     *
     * @param topicName    the Kafka topic name.
     * @param schemaPrefix when true, prepend the schema segment.
     * @return the extracted table name, or null if the topic has fewer than 3 segments.
     */
    public static String getTableNameFromTopic(String topicName,
                                                boolean schemaPrefix) {
        return getTableNameFromTopic(topicName, schemaPrefix, null);
    }

    /**
     * Extracts the table name from the given Kafka topic name with template support.
     * <p>
     * When {@code schemaPrefixEnabled} is true:
     * <ul>
     *   <li>If {@code schemaTemplate} is non-empty, it is resolved via
     *       {@link #resolveSchemaTemplate(String, String)} and prepended to the table name.</li>
     *   <li>Otherwise the hardcoded {@code __<schema>__} format is used.</li>
     * </ul>
     * Topic format: {topic.prefix}.{schema}.{table}
     * </p>
     *
     * @param topicName            the Kafka topic name.
     * @param schemaPrefixEnabled  the boolean config ({@code clickhouse.table.schema.prefix}).
     * @param schemaTemplate       the shared template ({@code clickhouse.common.schema.template}).
     * @return the resolved table name, or null if the topic has fewer than 2 segments.
     */
    public static String getTableNameFromTopic(String topicName,
                                                boolean schemaPrefixEnabled,
                                                String schemaTemplate) {
        if (topicName == null) return null;
        String[] splitName = topicName.split("\\.");
        if (splitName.length < 2) return topicName;

        String tableName = splitName[splitName.length - 1];
        String schema = (splitName.length >= 3) ? splitName[splitName.length - 2] : null;

        if (schemaPrefixEnabled && schema != null) {
            // Use template if available, otherwise fall back to hardcoded format
            if (schemaTemplate != null && !schemaTemplate.isEmpty()) {
                String resolvedPrefix = resolveSchemaTemplate(schemaTemplate, schema);
                return resolvedPrefix + tableName;
            }
            return "__" + schema + "__" + tableName;
        }

        return tableName;
    }

    /**
     * Extracts the schema name from a Debezium topic.
     * <p>
     * Topic format: {prefix}.{schema}.{table}
     * Returns the schema segment, or null if topic has fewer than 3 segments.
     * </p>
     *
     * @param topicName the Kafka topic name.
     * @return the schema segment, or null if the topic has fewer than 3 segments.
     */
    public static String extractSchemaFromTopic(String topicName) {
        if (topicName == null) return null;
        String[] parts = topicName.split("\\.");
        if (parts.length >= 3) {
            return parts[parts.length - 2];
        }
        return null;
    }

    /**
     * Resolves a template string by replacing {@code {{ schema }}} with the
     * actual schema name.
     *
     * @param template the template string containing {@code {{ schema }}} placeholder.
     * @param schema   the actual schema name (e.g., "public").
     * @return the template with placeholder replaced, or empty string if template is null/empty.
     */
    public static String resolveSchemaTemplate(String template, String schema) {
        if (template == null || template.isEmpty()) return "";
        if (schema == null) return template;
        return template.replace("{{ schema }}", schema);
    }

    /**
     * Applies a database schema suffix to a database name.
     *
     * @param databaseName   the original database name.
     * @param suffixTemplate the suffix template (e.g., "__{{ schema }}__").
     * @param schema         the actual schema name (e.g., "public").
     * @return the database name with suffix applied, or original if template is empty.
     */
    public static String applyDatabaseSchemaSuffix(String databaseName,
                                                    String suffixTemplate,
                                                    String schema) {
        if (suffixTemplate == null || suffixTemplate.isEmpty()) return databaseName;
        String resolvedSuffix = resolveSchemaTemplate(suffixTemplate, schema);
        return databaseName + resolvedSuffix;
    }

    /**
     * Validate that a database prefix contains only alphanumeric characters
     * and underscores.
     *
     * @param prefix the prefix to validate
     * @return true if valid (or empty/null), false if contains invalid characters
     */
    public static boolean isValidDatabasePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return true;
        return prefix.matches("[a-zA-Z0-9_]+");
    }

    /**
     * Apply database prefix to a database name.
     *
     * @param databaseName the original database name
     * @param prefix       the prefix string (must be alphanumeric + underscore only)
     * @return the prefixed database name, or original if prefix is empty/null
     */
    public static String applyDatabasePrefix(String databaseName, String prefix) {
        if (prefix == null || prefix.isEmpty()) return databaseName;
        return prefix + databaseName;
    }

    /**
     * Apply both database prefix and schema suffix to a database name.
     * Order: prefix + databaseName + suffix
     *
     * @param databaseName   the original database name
     * @param prefix         the static prefix (e.g., "litellm_dev_")
     * @param suffixTemplate the suffix template (e.g., "__{{ schema }}__")
     * @param schema         the actual schema name (e.g., "public")
     * @return the fully qualified database name
     */
    public static String applyDatabaseNaming(String databaseName,
                                              String prefix,
                                              String suffixTemplate,
                                              String schema) {
        String result = databaseName;
        if (prefix != null && !prefix.isEmpty()) {
            result = prefix + result;
        }
        if (suffixTemplate != null && !suffixTemplate.isEmpty()) {
            String resolvedSuffix = resolveSchemaTemplate(suffixTemplate, schema);
            result = result + resolvedSuffix;
        }
        return result;
    }

    /**
     * Validates if the provided table name meets the required conditions.
     * <p>
     * This method is a placeholder for the validation logic.
     * </p>
     *
     * @param tableName the table name to validate.
     * @return true if the table name is valid, false otherwise.
     */
    public static boolean isValidTable(String tableName) {
        return true;
    }

    /**
     * Validates if the provided database name meets the required conditions.
     * <p>
     * The database name should be non-empty, less than or equal to 63 characters,
     * and its first character should be a letter or an underscore. Subsequent characters
     * can be letters, digits, or underscores.
     * </p>
     *
     * @param dbName the database name to validate.
     * @return true if the database name is valid, false otherwise.
     */
    public static boolean isValidDatabaseName(String dbName) {
        // Check if the name is empty or longer than 63 characters
        if (dbName == null || dbName.isEmpty() || dbName.length() > 63) {
            return false;
        }

        // Check the first character: must be a letter or an underscore
        char firstChar = dbName.charAt(0);
        if (!(Character.isLetter(firstChar) || firstChar == '_')) {
            return false;
        }

        // Check the remaining characters
        for (int i = 1; i < dbName.length(); i++) {
            char ch = dbName.charAt(i);
            // If character is an underscore, continue
            if (ch == '_') {
                continue;
            }
            if (!(Character.isLetterOrDigit(ch) || ch == '.')) {
                return false;
            }
        }

        return true;
    }

    /**
     * Converts a Properties object into a HashMap.
     * <p>
     * This method iterates through the properties and converts them to a
     * HashMap where the key-value pairs are stored as Strings.
     * </p>
     *
     * @param properties the Properties object to be converted.
     * @return a HashMap containing the key-value pairs of the properties.
     */
    public static HashMap<String, String> propertiesToMap(Properties properties) {
        if (properties == null) {
            return new HashMap<>();
        }
        
        return properties.entrySet().stream().collect(
                Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue()),
                        (prev, next) -> next, HashMap::new
                ));
    }
}

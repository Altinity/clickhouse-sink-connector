/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

public class Utils {
    private static final Logger LOGGER = LogManager.getLogger(Utils.class);

    public static final String TASK_ID = "task_id";
    // Connector version, change every release
    public static final String VERSION = "1.0.0";

    /**
     * Function to parse the topic to table configuration parameter
     *
     * @param input Delimiter separated list.
     * @return key/value pair of configuration.
     */
    public static Map<String, String> parseSourceToDestinationDatabaseMap(String input) throws Exception {
        Map<String, String> srcToDestinationMap = new HashMap<>();
        boolean isInvalid = false;

        if(input == null || input.isEmpty()) {
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

            // Disable validation of source database.
//            if (!isValidDatabaseName(srcDatabase)) {
//                LOGGER.error(
//                        Logging.logMessage(
//                                "database name{} should have at least 2 "
//                                        + "characters, start with _a-zA-Z, and only contains "
//                                        + "_$a-zA-z0-9",
//                                srcDatabase));
//                isInvalid = true;
//            }

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
     * Function to parse the topic to table configuration parameter
     *
     * @param input Delimiter separated list.
     * @return key/value pair of configuration.
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
     * Function to get Table name from kafka connect topic
     * @param topicName
     * @return Table Name
     */
    public static String getTableNameFromTopic(String topicName) {
        String tableName = null;


            // topic names is of the following format.
            // hostname.dbName.tableName or hostname.dbName.schemaName.tableName
            String[] splitName = topicName.split("\\.");
            if(splitName.length >= 3) {
                tableName = splitName[splitName.length - 1];
            }

        return tableName;
    }
    /**
     * Function to valid table name passed in settings
     * //ToDO: Implement the function.
     *
     * @return Boolean.
     */
    public static boolean isValidTable(String tableName) {
        return true;
    }

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
            // If character is a underscore, continue
            if(ch == '_') {
                continue;
            }
            if (!(Character.isLetterOrDigit(ch) || ch == '.')) {
                return false;
            }
        }

        return true;
    }


}
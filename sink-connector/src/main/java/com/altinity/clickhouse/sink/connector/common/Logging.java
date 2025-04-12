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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;

/**
 * Base class for all classes that enable logging.
 * <p>
 * This class provides utility methods to log messages with a common
 * tag. It wraps log messages with a predefined tag so that users can
 * easily filter messages originating from this connector.
 * </p>
 * <p>
 * Note: This is a class instead of an interface due to Java 8
 * limitations. It can be converted to an interface when upgrading to
 * Java 9 or later.
 * </p>
 */
public class Logging {
    // todo: change to interface when upgrading to Java 9 or later
    /**
     * Logger instance used for logging messages in the class.
     * <p>
     * This logger is initialized using LogManager to provide logging
     * functionality. It logs events with the class name as the logger's
     * name.
     * </p>
     */
    private final Logger log =
            LogManager.getLogger(getClass().getName());

    // only message
    /**
     * Logs an info message.
     *
     * @param msg the message to log.
     */
    protected void logInfo(String msg) {
        if (log.isInfoEnabled()) {
            log.info(logMessage(msg));
        }
    }

    /**
     * Logs a trace message.
     *
     * @param msg the message to log.
     */
    protected void logTrace(String msg) {
        if (log.isTraceEnabled()) {
            log.trace(logMessage(msg));
        }
    }

    /**
     * Logs a debug message.
     *
     * @param msg the message to log.
     */
    protected void logDebug(String msg) {
        if (log.isDebugEnabled()) {
            log.debug(logMessage(msg));
        }
    }

    /**
     * Logs a warning message.
     *
     * @param msg the message to log.
     */
    protected void logWarn(String msg) {
        if (log.isWarnEnabled()) {
            log.warn(logMessage(msg));
        }
    }

    /**
     * Logs an error message.
     *
     * @param msg the message to log.
     */
    protected void logError(String msg) {
        if (log.isErrorEnabled()) {
            log.error(logMessage(msg));
        }
    }

    // format and variables
    /**
     * Logs an info message using a format string and variables.
     *
     * @param format the format string.
     * @param vars   the variables to be inserted into the format.
     */
    protected void logInfo(String format, Object... vars) {
        if (log.isInfoEnabled()) {
            log.info(logMessage(format, vars));
        }
    }

    /**
     * Logs a trace message using a format string and variables.
     *
     * @param format the format string.
     * @param vars   the variables to be inserted into the format.
     */
    protected void logTrace(String format, Object... vars) {
        if (log.isTraceEnabled()) {
            log.trace(logMessage(format, vars));
        }
    }

    /**
     * Logs a debug message using a format string and variables.
     *
     * @param format the format string.
     * @param vars   the variables to be inserted into the format.
     */
    protected void logDebug(String format, Object... vars) {
        if (log.isDebugEnabled()) {
            log.debug(logMessage(format, vars));
        }
    }

    /**
     * Logs a warning message using a format string and variables.
     *
     * @param format the format string.
     * @param vars   the variables to be inserted into the format.
     */
    protected void logWarn(String format, Object... vars) {
        if (log.isWarnEnabled()) {
            log.warn(format, vars);
        }
    }

    /**
     * Logs an error message using a format string and variables.
     *
     * @param format the format string.
     * @param vars   the variables to be inserted into the format.
     */
    protected void logError(String format, Object... vars) {
        if (log.isErrorEnabled()) {
            log.error(logMessage(format, vars));
        }
    }

    // static elements

    /**
     * Log message tag.
     */
    static final String SF_LOG_TAG = "[SF_KAFKA_CONNECTOR]";

    /*
     * the following methods wrap log message with ClickHouse tag. For example,
     *
     * [SF_KAFKA_CONNECTOR] this is a log message
     * [SF_KAFKA_CONNECTOR] this is the second line
     *
     * All log messages should be wrapped by Snowflake tag. Then user can
     * filter out log messages output from Snowflake Kafka connector by these
     * tags.
     */

    /**
     * Wraps a message without variables.
     *
     * @param msg the log message.
     * @return the log message wrapped by the Snowflake tag.
     */
    public static String logMessage(String msg) {
        return "\n".concat(msg)
                .replaceAll("\n", "\n" + SF_LOG_TAG + " ");
    }

    /**
     * Wraps a message containing multiple variables.
     *
     * @param format the log message format string.
     * @param vars   the variable list.
     * @return the log message wrapped by the Snowflake tag.
     */
    public static String logMessage(String format, Object... vars) {
        for (Object var : vars) {
            format = format.replaceFirst(
                    "\\{}",
                    Objects.toString(var).replaceAll("\\$", "\\\\\\$")
            );
        }
        return logMessage(format);
    }
}

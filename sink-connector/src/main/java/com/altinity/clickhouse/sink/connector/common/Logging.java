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
 * Base class for all classes enable logging
 */
public class Logging {
    // todo: change to interface when upgrading to Java 9 or later
    private final Logger log = LogManager.getLogger(getClass().getName());

    // only message
    protected void logInfo(String msg) {
        if (log.isInfoEnabled()) {
            log.info(logMessage(msg));
        }
    }

    protected void logTrace(String msg) {
        if (log.isTraceEnabled()) {
            log.trace(logMessage(msg));
        }
    }

    protected void logDebug(String msg) {
        if (log.isDebugEnabled()) {
            log.debug(logMessage(msg));
        }
    }

    protected void logWarn(String msg) {
        if (log.isWarnEnabled()) {
            log.warn(logMessage(msg));
        }
    }

    protected void logError(String msg) {
        if (log.isErrorEnabled()) {
            log.error(logMessage(msg));
        }
    }

    // format and variables
    protected void logInfo(String format, Object... vars) {
        if (log.isInfoEnabled()) {
            log.info(logMessage(format, vars));
        }
    }

    protected void logTrace(String format, Object... vars) {
        if (log.isTraceEnabled()) {
            log.trace(logMessage(format, vars));
        }
    }

    protected void logDebug(String format, Object... vars) {
        if (log.isDebugEnabled()) {
            log.debug(logMessage(format, vars));
        }
    }

    protected void logWarn(String format, Object... vars) {
        if (log.isWarnEnabled()) {
            log.warn(format, vars);
        }
    }

    protected void logError(String format, Object... vars) {
        if (log.isErrorEnabled()) {
            log.error(logMessage(format, vars));
        }
    }

    // static elements

    // log message tag
    static final String SF_LOG_TAG = "[SF_KAFKA_CONNECTOR]";

    /*
     * the following methods wrap log message with ClickHouse tag. For example,
     *
     * [SF_KAFKA_CONNECTOR] this is a log message
     * [SF_KAFKA_CONNECTOR] this is the second line
     *
     * All log messages should be wrapped by Snowflake tag. Then user can filter
     * out log messages output from Snowflake Kafka connector by these tags.
     */

    /**
     * wrap a message without variable
     *
     * @param msg log message
     * @return log message wrapped by snowflake tag
     */
    public static String logMessage(String msg) {
        return "\n".concat(msg).replaceAll("\n", "\n" + SF_LOG_TAG + " ");
    }

    /**
     * wrap a message contains multiple variables
     *
     * @param format log message format string
     * @param vars   variable list
     * @return log message wrapped by snowflake tag
     */
    public static String logMessage(String format, Object... vars) {
        for (Object var : vars) {
            format = format.replaceFirst("\\{}", Objects.toString(var).replaceAll("\\$", "\\\\\\$"));
        }
        return logMessage(format);
    }
}

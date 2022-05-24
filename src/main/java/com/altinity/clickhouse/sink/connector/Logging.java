package com.altinity.clickhouse.sink.connector;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Base class for all classes enable logging
 */
public class Logging {
    // todo: change to interface when upgrading to Java 9 or later
    private final Logger log = LoggerFactory.getLogger(getClass().getName());

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
        for (int i = 0; i < vars.length; i++) {
            format = format.replaceFirst("\\{}", Objects.toString(vars[i]).replaceAll("\\$", "\\\\\\$"));
        }
        return logMessage(format);
    }
}

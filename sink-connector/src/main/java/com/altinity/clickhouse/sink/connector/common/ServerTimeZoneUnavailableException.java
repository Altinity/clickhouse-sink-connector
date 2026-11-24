package com.altinity.clickhouse.sink.connector.common;

/**
 * The ClickHouse server zone (SELECT timezone()) could not be read or resolved (spec 07.03 §3.1.2).
 * Raised instead of falling back to UTC: a silent UTC would shift every DateTime value written with it.
 */
public class ServerTimeZoneUnavailableException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ServerTimeZoneUnavailableException(String message) {
        super(message);
    }

    public ServerTimeZoneUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

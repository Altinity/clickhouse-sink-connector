package com.altinity.clickhouse.sink.connector.common;

/**
 * Optional hook for reporting connector errors to an in-memory status store.
 * The lightweight connector registers a reporter that updates ReplicationStatusSingleton.
 */
public final class ConnectorErrorReporter {

    private static volatile ErrorReporter reporter;

    private ConnectorErrorReporter() {
    }

    public interface ErrorReporter {
        void reportError(String error, String sourceDatabase, String query);
    }

    public static void setReporter(ErrorReporter errorReporter) {
        reporter = errorReporter;
    }

    public static void reportError(String error, String sourceDatabase, String query) {
        ErrorReporter currentReporter = reporter;
        if (currentReporter != null) {
            currentReporter.reportError(error, sourceDatabase, query);
        }
    }
}

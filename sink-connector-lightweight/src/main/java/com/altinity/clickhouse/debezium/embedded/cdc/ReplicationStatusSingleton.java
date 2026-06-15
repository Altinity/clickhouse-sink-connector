package com.altinity.clickhouse.debezium.embedded.cdc;

/**
 * The ReplicationStatusSingleton class is a singleton that provides access
 * to a shared instance of the ReplicationStatus object. This class ensures
 * that only one instance of ReplicationStatus is used throughout the
 * application.
 * <p>
 * The singleton pattern is used here to avoid multiple instances of the
 * replication status and to provide global access to the current state of
 * the replication process.
 * </p>
 */
public class ReplicationStatusSingleton {

    /**
     * The singleton instance of the ReplicationStatusSingleton class.
     */
    private static ReplicationStatusSingleton instance;

    /**
     * The ReplicationStatus object that holds the replication state.
     */
    private ReplicationStatus status;

    /**
     * Private constructor to prevent instantiation from outside the class.
     * Initializes the ReplicationStatus object.
     */
    private ReplicationStatusSingleton() {
        this.status = new ReplicationStatus();
    }

    /**
     * Gets the singleton instance of the ReplicationStatusSingleton class.
     * <p>
     * If the instance is not already created, it creates and returns the
     * instance. If it is already created, it simply returns the existing
     * instance.
     * </p>
     *
     * @return the singleton instance of ReplicationStatusSingleton.
     */
    public static ReplicationStatusSingleton getInstance() {

        if (instance == null) {
            instance = new ReplicationStatusSingleton();
        }
        return instance;
    }

    /**
     * Sets the replication lag.
     *
     * @param replicationLag the replication lag in milliseconds.
     */
    public void setReplicationLag(long replicationLag) {
        this.status.setReplicationLag(replicationLag);
    }

    /**
     * Gets the replication lag.
     *
     * @return the replication lag in milliseconds.
     */
    public long getReplicationLag() {
        return this.status.getReplicationLag();
    }

    /**
     * Sets the replication status.
     *
     * @param status the ReplicationStatus object that holds the replication state.
     */
    public void setReplicationStatus(ReplicationStatus status) {
        this.status = status;
    }

    /**
     * Gets the replication status.
     *
     * @return the ReplicationStatus object containing the replication state.
     */
    public ReplicationStatus getReplicationStatus() {
        return this.status;
    }

    /**
     * Sets the timestamp of the last record processed by replication.
     *
     * @param timestamp the timestamp of the last record.
     */
    public void setLastRecordTimestamp(long timestamp) {
        this.status.setLastRecordTimestamp(timestamp);
    }

    /**
     * Gets the timestamp of the last record processed by replication.
     *
     * @return the timestamp of the last record.
     */
    public long getLastRecordTimestamp() {
        return this.status.getLastRecordTimestamp();
    }

    /**
     * Sets whether replication is currently running.
     *
     * @param isRunning true if replication is running, false otherwise.
     */
    public void setIsReplicationRunning(boolean isRunning) {
        this.status.setIsReplicationRunning(isRunning);
    }

    /**
     * Checks if replication is currently running.
     *
     * @return true if replication is running, false otherwise.
     */
    public boolean isReplicationRunning() {
        return this.status.isReplicationRunning();
    }

    /**
     * Sets the binlog file name.
     *
     * @param binLogFile the binlog file name.
     */
    public void setBinLogFile(String binLogFile) {
        this.status.setBinLogFile(binLogFile);
    }

    /**
     * Gets the binlog file name.
     *
     * @return the binlog file name.
     */
    public String getBinLogFile() {
        return this.status.getBinLogFile();
    }

    /**
     * Sets the binlog position.
     *
     * @param binLogPosition the binlog position.
     */
    public void setBinLogPosition(String binLogPosition) {
        this.status.setBinLogPosition(binLogPosition);
    }

    /**
     * Gets the binlog position.
     *
     * @return the binlog position.
     */
    public String getBinLogPosition() {
        return this.status.getBinLogPosition();
    }

    /**
     * Sets the GTID of the last replication event.
     *
     * @param gtid the GTID of the last event.
     */
    public void setGtid(String gtid) {
        this.status.setGtid(gtid);
    }

    /**
     * Gets the GTID of the last replication event.
     *
     * @return the GTID.
     */
    public String getGtid() {
        return this.status.getGtid();
    }

    /**
     * Sets the last error message.
     *
     * @param lastError the last error message.
     */
    public void setLastError(String lastError) {
        this.status.setLastError(lastError);
    }

    /**
     * Gets the last error message.
     *
     * @return the last error message.
     */
    public String getLastError() {
        return this.status.getLastError();
    }

    /**
     * Sets the timestamp of the last error.
     *
     * @param lastErrorTimestamp the last error timestamp in epoch milliseconds.
     */
    public void setLastErrorTimestamp(long lastErrorTimestamp) {
        this.status.setLastErrorTimestamp(lastErrorTimestamp);
    }

    /**
     * Gets the timestamp of the last error.
     *
     * @return the last error timestamp in epoch milliseconds.
     */
    public long getLastErrorTimestamp() {
        return this.status.getLastErrorTimestamp();
    }

    /**
     * Sets the source database associated with the last error.
     *
     * @param lastErrorSourceDatabase the source database name.
     */
    public void setLastErrorSourceDatabase(String lastErrorSourceDatabase) {
        this.status.setLastErrorSourceDatabase(lastErrorSourceDatabase);
    }

    /**
     * Gets the source database associated with the last error.
     *
     * @return the source database name.
     */
    public String getLastErrorSourceDatabase() {
        return this.status.getLastErrorSourceDatabase();
    }

    /**
     * Sets the query associated with the last error.
     *
     * @param lastErrorQuery the query string.
     */
    public void setLastErrorQuery(String lastErrorQuery) {
        this.status.setLastErrorQuery(lastErrorQuery);
    }

    /**
     * Gets the query associated with the last error.
     *
     * @return the query string.
     */
    public String getLastErrorQuery() {
        return this.status.getLastErrorQuery();
    }

    /**
     * Updates all last-error fields from a single error event.
     *
     * @param error the error message.
     * @param sourceDatabase the source database name.
     * @param query the query that caused the error.
     */
    public void setLastErrorDetails(String error, String sourceDatabase, String query) {
        this.status.setLastError(error != null ? error : "");
        this.status.setLastErrorTimestamp(System.currentTimeMillis());
        this.status.setLastErrorSourceDatabase(sourceDatabase != null ? sourceDatabase : "");
        this.status.setLastErrorQuery(query != null ? query : "");
    }
}

package com.altinity.clickhouse.debezium.embedded.cdc;

/**
 * The ReplicationStatus class represents the status of the replication process,
 * including replication lag, last record timestamp, whether replication is running,
 * and information about the binlog file and position.
 * <p>
 * This class encapsulates the data related to the replication status and provides
 * getter and setter methods to access and modify the status.
 * </p>
 */
public class ReplicationStatus {

    /**
     * The replication lag in milliseconds.
     */
    private long replicationLag = 0;

    /**
     * The timestamp of the last record processed by replication.
     */
    private long lastRecordTimestamp = -1;

    /**
     * Indicates whether replication is currently running.
     */
    private boolean isReplicationRunning = false;

    /**
     * The binlog file from which the replication is reading.
     */
    private String binLogFile = "";

    /**
     * The position in the binlog where replication is currently at.
     */
    private String binLogPosition = "";

    /**
     * The GTID of the last replication event.
     */
    private String gtid = "";

    /**
     * The last error message encountered during replication.
     */
    private String lastError = "";

    /**
     * The timestamp of the last error in epoch milliseconds.
     */
    private long lastErrorTimestamp = -1;

    /**
     * The source database associated with the last error.
     */
    private String lastErrorSourceDatabase = "";

    /**
     * The query associated with the last error.
     */
    private String lastErrorQuery = "";

    /**
     * Gets the replication lag in milliseconds.
     *
     * @return the replication lag in milliseconds.
     */
    public long getReplicationLag() {
        return this.replicationLag;
    }

    /**
     * Gets the replication lag in seconds.
     *
     * @return the replication lag in seconds.
     */
    public long getReplicationLagInSecs() {
        return this.replicationLag / 1000;
    }

    /**
     * Gets the timestamp of the last record processed by replication.
     *
     * @return the timestamp of the last record.
     */
    public long getLastRecordTimestamp() {
        return lastRecordTimestamp;
    }

    /**
     * Checks if replication is currently running.
     *
     * @return true if replication is running, false otherwise.
     */
    public boolean isReplicationRunning() {
        return isReplicationRunning;
    }

    /**
     * Gets the name of the binlog file.
     *
     * @return the binlog file name.
     */
    public String getBinLogFile() {
        return binLogFile;
    }

    /**
     * Gets the binlog position.
     *
     * @return the binlog position.
     */
    public String getBinLogPosition() {
        return binLogPosition;
    }

    /**
     * Gets the GTID of the last replication event.
     *
     * @return the GTID.
     */
    public String getGtid() {
        return gtid;
    }

    /**
     * Sets the replication lag.
     *
     * @param replicationLag the replication lag in milliseconds.
     */
    public void setReplicationLag(long replicationLag) {
        this.replicationLag = replicationLag;
    }

    /**
     * Sets the timestamp of the last record processed by replication.
     *
     * @param lastRecordTimestamp the timestamp of the last record.
     */
    public void setLastRecordTimestamp(long lastRecordTimestamp) {
        this.lastRecordTimestamp = lastRecordTimestamp;
    }

    /**
     * Sets whether replication is currently running.
     *
     * @param isReplicationRunning true if replication is running, false otherwise.
     */
    public void setIsReplicationRunning(boolean isReplicationRunning) {
        this.isReplicationRunning = isReplicationRunning;
    }

    /**
     * Sets the binlog file name.
     *
     * @param binLogFile the name of the binlog file.
     */
    public void setBinLogFile(String binLogFile) {
        this.binLogFile = binLogFile;
    }

    /**
     * Sets the binlog position.
     *
     * @param binLogPosition the binlog position.
     */
    public void setBinLogPosition(String binLogPosition) {
        this.binLogPosition = binLogPosition;
    }

    /**
     * Sets the GTID of the last replication event.
     *
     * @param gtid the GTID.
     */
    public void setGtid(String gtid) {
        this.gtid = gtid;
    }

    /**
     * Gets the last error message.
     *
     * @return the last error message.
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * Sets the last error message.
     *
     * @param lastError the last error message.
     */
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    /**
     * Gets the timestamp of the last error.
     *
     * @return the last error timestamp in epoch milliseconds.
     */
    public long getLastErrorTimestamp() {
        return lastErrorTimestamp;
    }

    /**
     * Sets the timestamp of the last error.
     *
     * @param lastErrorTimestamp the last error timestamp in epoch milliseconds.
     */
    public void setLastErrorTimestamp(long lastErrorTimestamp) {
        this.lastErrorTimestamp = lastErrorTimestamp;
    }

    /**
     * Gets the source database associated with the last error.
     *
     * @return the source database name.
     */
    public String getLastErrorSourceDatabase() {
        return lastErrorSourceDatabase;
    }

    /**
     * Sets the source database associated with the last error.
     *
     * @param lastErrorSourceDatabase the source database name.
     */
    public void setLastErrorSourceDatabase(String lastErrorSourceDatabase) {
        this.lastErrorSourceDatabase = lastErrorSourceDatabase;
    }

    /**
     * Gets the query associated with the last error.
     *
     * @return the query string.
     */
    public String getLastErrorQuery() {
        return lastErrorQuery;
    }

    /**
     * Sets the query associated with the last error.
     *
     * @param lastErrorQuery the query string.
     */
    public void setLastErrorQuery(String lastErrorQuery) {
        this.lastErrorQuery = lastErrorQuery;
    }
}

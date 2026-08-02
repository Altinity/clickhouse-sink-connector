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
     * Eagerly initialized for thread safety.
     */
    private static final ReplicationStatusSingleton instance = new ReplicationStatusSingleton();

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
}

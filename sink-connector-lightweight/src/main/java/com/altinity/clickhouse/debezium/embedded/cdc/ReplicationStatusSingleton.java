package com.altinity.clickhouse.debezium.embedded.cdc;

public class ReplicationStatusSingleton {

    private static ReplicationStatusSingleton instance;

    private ReplicationStatus status;

    private ReplicationStatusSingleton() {
        this.status = new ReplicationStatus();
    }

    public static ReplicationStatusSingleton getInstance() {

        if (instance == null) {
            instance = new ReplicationStatusSingleton();
        }
        return instance;
    }
    public void setReplicationLag(long replicationLag) {
        this.status.setReplicationLag(replicationLag);
    }

    public long getReplicationLag() {
        return this.status.getReplicationLag();
    }   

    public void setReplicationStatus(ReplicationStatus status) {
        this.status = status;
    }

    public ReplicationStatus getReplicationStatus() {
        return this.status;
    }

    public void setLastRecordTimestamp(long timestamp) {
        this.status.setLastRecordTimestamp(timestamp);
    }

    public long getLastRecordTimestamp() {
        return this.status.getLastRecordTimestamp();
    }

    public void setIsReplicationRunning(boolean isRunning) {
        this.status.setIsReplicationRunning(isRunning);
    }

    public boolean isReplicationRunning() {
        return this.status.isReplicationRunning();
    }

    public void setBinLogFile(String binLogFile) {
        this.status.setBinLogFile(binLogFile);
    }

    public String getBinLogFile() {
        return this.status.getBinLogFile();
    }

    public void setBinLogPosition(String binLogPosition) {
        this.status.setBinLogPosition(binLogPosition);
    }

    public String getBinLogPosition() {
        return this.status.getBinLogPosition();
    }

    public void setGtid(String gtid) {
        this.status.setGtid(gtid);
    }

    public String getGtid() {
        return this.status.getGtid();
    }
}

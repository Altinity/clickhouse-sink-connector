package com.altinity.clickhouse.debezium.embedded.cdc;

public class ReplicationStatus {

    private long replicationLag = 0;

    private long lastRecordTimestamp = -1;

    private boolean isReplicationRunning = false;

    private String binLogFile = "";

    private String binLogPosition = "";

    private String gtid = "";

    public long getReplicationLag() {
        return this.replicationLag;
    }

    public long getReplicationLagInSecs() {
        return this.replicationLag / 1000;
    }

    public long getLastRecordTimestamp() {
        return lastRecordTimestamp;
    }

    public boolean isReplicationRunning() {
        return isReplicationRunning;
    }

    public String getBinLogFile() {
        return binLogFile;
    }

    public String getBinLogPosition() {
        return binLogPosition;
    }

    public String getGtid() {
        return gtid;
    }   

    public void setReplicationLag(long replicationLag) {
        this.replicationLag = replicationLag;
    }

    public void setLastRecordTimestamp(long lastRecordTimestamp) {
        this.lastRecordTimestamp = lastRecordTimestamp;
    }

    public void setIsReplicationRunning(boolean isReplicationRunning) {
        this.isReplicationRunning = isReplicationRunning;
    }

    public void setBinLogFile(String binLogFile) {
        this.binLogFile = binLogFile;
    }

    public void setBinLogPosition(String binLogPosition) {
        this.binLogPosition = binLogPosition;
    }   

    public void setGtid(String gtid) {
        this.gtid = gtid;
    }   

}

package com.altinity.clickhouse.sink.connector.history;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Failure modes of the binlog audit table under redelivery (Spec 12.04 section 7,
 * FM-12.04-2). The audit table is a {@code ReplacingMergeTree(_version, is_deleted)}
 * ordered by {@code (server_id, logfile, position, sequence, _time)}; a redelivered
 * record collapses into its first copy under {@code FINAL} only if it binds the SAME
 * five sorting-key values and the same {@code _version}.
 */
public class BinLogHistoryRedeliveryTest {

    private static final List<String> SORTING_KEY =
            Arrays.asList(BinLogHistory.SERVER_ID_COLUMN, BinLogHistory.LOGFILE_COLUMN,
                    BinLogHistory.POSITION_COLUMN, BinLogHistory.SEQUENCE_COLUMN, BinLogHistory.TIME_COLUMN);

    /** One binlog row, delivered with the given lightweight sequence number. */
    private static ClickHouseStruct delivery(long sequenceNumber) {
        ClickHouseStruct row = new ClickHouseStruct();
        row.setTs_ms(1790431200123L);
        row.setTsSec(1790431200L);
        row.setServerId(266L);
        row.setFile("mysql-bin.000042");
        row.setPos(1156385L);
        row.setRow(2);
        row.setGtid(2442L);
        row.setDatabase("shop");
        row.setTopic("embeddedconnector.shop.orders");
        row.setCdcOperation(ClickHouseConverter.CDC_OPERATION.DELETE);
        row.setSequenceNumber(sequenceNumber);
        return row;
    }

    /** Binds every struct through the real insert path and returns the bound values by column name. */
    private static List<Map<String, Object>> bind(ClickHouseStruct... structs) throws Exception {
        List<Map<Integer, Object>> batches = new ArrayList<>();
        final Map<Integer, Object>[] current = new Map[] {new HashMap<Integer, Object>()};
        PreparedStatement ps = (PreparedStatement) Proxy.newProxyInstance(
                BinLogHistoryRedeliveryTest.class.getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.startsWith("set") && args != null && args.length == 2) {
                        current[0].put((Integer) args[0], args[1]);
                        return null;
                    }
                    if (name.equals("addBatch")) {
                        batches.add(current[0]);
                        current[0] = new HashMap<>();
                        return null;
                    }
                    if (name.equals("executeBatch")) {
                        return new int[batches.size()];
                    }
                    return null;
                });
        Connection conn = (Connection) Proxy.newProxyInstance(
                BinLogHistoryRedeliveryTest.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> method.getName().equals("prepareStatement") ? ps : null);
        Map<String, String> props = new HashMap<>();
        ClickHouseSinkConnectorConfig.setDefaultValues(props);
        new BinLogHistory().executeInsertWithStructs(new ClickHouseSinkConnectorConfig(props), conn,
                "INSERT INTO binlog_history.history", "", Arrays.asList(structs), "UTC", "UTC");
        List<String> columns = new ArrayList<>(BinLogHistory.HISTORY_COLUMNS.keySet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<Integer, Object> batch : batches) {
            Map<String, Object> row = new HashMap<>();
            for (Map.Entry<Integer, Object> e : batch.entrySet()) {
                row.put(columns.get(e.getKey() - 1), e.getValue());
            }
            rows.add(row);
        }
        return rows;
    }

    private static Map<String, Object> sortingKeyAndVersion(Map<String, Object> row) {
        Map<String, Object> key = new HashMap<>();
        for (String column : SORTING_KEY) {
            key.put(column, row.get(column));
        }
        key.put(BinLogHistory.VERSION_COLUMN, row.get(BinLogHistory.VERSION_COLUMN));
        return key;
    }

    /**
     * An in-run batch retry (ClickHouseBatchRunnable re-runs processBatch on the
     * same ClickHouseStruct objects) re-inserts the audit rows with identical
     * sorting key and version, so FINAL collapses them (Spec 12.04 section 3.6).
     * The audit row of a DELETE stays live: is_deleted is always 0.
     */
    @Test
    public void inRunRetryOfTheSameRecordBindsTheSameSortingKeyAndVersion() throws Exception {
        ClickHouseStruct record = delivery(1790431200500000007L);
        List<Map<String, Object>> rows = bind(record, record);
        assertEquals(2, rows.size());
        assertEquals(sortingKeyAndVersion(rows.get(0)), sortingKeyAndVersion(rows.get(1)),
                "an in-run retry must collapse into the first copy under FINAL");
        assertEquals(0, rows.get(0).get(BinLogHistory.IS_DELETED_COLUMN), "an audited DELETE is never a delete row");
        assertEquals("DELETE", rows.get(0).get(BinLogHistory.OPERATION_COLUMN));
    }

    /**
     * After a restart (or an engine recreation) Debezium re-publishes the
     * unacknowledged records and the lightweight dispatch loop assigns them NEW
     * sequence numbers above the previous run's high-water mark (Spec 02.04
     * section 3.2). The audit table uses that per-delivery number as the
     * {@code sequence} sorting-key column, so the redelivered copy lands on a
     * different sorting key and is NOT collapsed by FINAL: every restart leaves
     * duplicate audit rows for the redelivered window.
     */
    @Test
    @Disabled("DEFECT FM-12.04-2: the audit sorting key uses the per-delivery sequence number, so a record "
            + "redelivered after a restart gets a second, uncollapsible audit row (BinLogHistory.getValueFromStruct "
            + "SEQUENCE_COLUMN)")
    public void redeliveryAfterRestartBindsTheSameSortingKeyAndVersion() throws Exception {
        ClickHouseStruct firstRun = delivery(1790431200500000007L);
        ClickHouseStruct afterRestart = delivery(1790431260500000001L);
        List<Map<String, Object>> rows = bind(firstRun, afterRestart);
        assertEquals(sortingKeyAndVersion(rows.get(0)), sortingKeyAndVersion(rows.get(1)),
                "the same binlog row must collapse into one audit row under FINAL whichever run delivered it");
    }
}

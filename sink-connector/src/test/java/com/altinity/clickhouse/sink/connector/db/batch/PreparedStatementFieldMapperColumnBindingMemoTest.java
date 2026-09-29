package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.ZonedTimestamp;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Spec 04.06 section 3.2: a column's declared type, zone and range policy are
 * resolved once per column per statement, bind exactly what a per-row parse
 * binds, and are discarded when the column map instance changes.
 */
public class PreparedStatementFieldMapperColumnBindingMemoTest {

    private static final Schema ROW = SchemaBuilder.struct().name("srv.db.orders.Value")
            .field("id", Schema.INT32_SCHEMA)
            .field("dt6", SchemaBuilder.int64().name(MicroTimestamp.SCHEMA_NAME).optional().build())
            .field("name", Schema.OPTIONAL_STRING_SCHEMA)
            .field("ts", SchemaBuilder.string().name(ZonedTimestamp.SCHEMA_NAME).optional().build())
            .build();

    private static Map<String, Integer> indexMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("id", 1);
        m.put("dt6", 2);
        m.put("name", 3);
        m.put("ts", 4);
        return m;
    }

    /** {@code tsType} is the declared type of the TIMESTAMP (instant) column. */
    private static Map<String, String> columns(String tsType) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("dt6", "DateTime64(6, 'UTC')");
        m.put("name", "Nullable(String)");
        m.put("ts", tsType);
        return m;
    }

    private static ClickHouseSinkConnectorConfig config() {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    private static ClickHouseStruct record(int id) {
        Struct after = new Struct(ROW)
                .put("id", id)
                .put("dt6", 1_640_995_200_000_000L + id * 1_000_000L)
                .put("name", id % 2 == 0 ? null : "n" + id)
                .put("ts", "2022-01-01T16:00:0" + id + "Z");
        ClickHouseStruct r = new ClickHouseStruct(id, "srv.db.orders", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        r.setDatabase("db");
        return r;
    }

    private static PreparedStatementFieldMapper mapper() {
        return new PreparedStatementFieldMapper("is_deleted", true, null, "_version", "db", ZoneId.of("UTC"));
    }

    /** Binds one row and returns the parameters captured at addBatch(). */
    private static Map<Integer, Object> bind(PreparedStatementFieldMapper mapper, ClickHouseStruct r,
                                             Map<String, String> columns, ClickHouseSinkConnectorConfig config)
            throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Connection c = jdbc.connection();
        PreparedStatement ps = c.prepareStatement("INSERT INTO `orders`(`id`,`dt6`,`name`,`ts`) VALUES (?,?,?,?)");
        mapper.insertPreparedStatement(indexMap(), ps, r.getAfterModifiedFields(), r, r.getAfterStruct(), false,
                config, columns, DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "orders");
        ps.addBatch();
        return jdbc.ofKind(RecordingJdbc.ADD_BATCH).get(0).params;
    }

    @Test
    @DisplayName("Rows bound through one mapper bind exactly what fresh mappers bind")
    public void memoisedBindingsEqualFreshBindings() throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        Map<String, String> columns = columns("DateTime64(6, 'UTC')");
        PreparedStatementFieldMapper shared = mapper();
        List<Map<Integer, Object>> memoised = new ArrayList<>();
        List<Map<Integer, Object>> fresh = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            memoised.add(bind(shared, record(i), columns, config));
            fresh.add(bind(mapper(), record(i), columns, config));
        }
        assertEquals(fresh, memoised, "the memo must not change a single bound value");
        // Sanity: the rows are not all the same, so the equality above is meaningful.
        assertNotEquals(memoised.get(0), memoised.get(1));
        assertEquals("2022-01-01 00:00:01.00000000", memoised.get(0).get(2),
                "DATETIME(6) digits are stored as the source holds them (Spec 07.03 section 3.1.2)");
        assertEquals("2022-01-01 16:00:01.000000", memoised.get(0).get(4),
                "the TIMESTAMP instant renders in the column's declared UTC zone");
    }

    @Test
    @DisplayName("A second column-map instance re-parses the column: the instant follows its zone")
    public void bindingIsRebuiltForADifferentColumnMap() throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        PreparedStatementFieldMapper shared = mapper();
        Map<Integer, Object> utc = bind(shared, record(1), columns("DateTime64(6, 'UTC')"), config);
        Map<Integer, Object> chicago = bind(shared, record(1), columns("DateTime64(6, 'America/Chicago')"), config);
        assertEquals("2022-01-01 16:00:01.000000", utc.get(4));
        assertEquals("2022-01-01 10:00:01.000000", chicago.get(4),
                "a memo that survived the column-map change would still render the UTC zone");
        assertEquals(utc.get(1), chicago.get(1));
        assertEquals(utc.get(2), chicago.get(2));
        assertEquals(utc.get(3), chicago.get(3));
    }

    /**
     * Spec 04.06 section 3.5: the connector replaces the column map after a
     * DDL, but the memo must not depend on that. A declared type changed IN
     * PLACE under the same map instance is re-parsed on the next row.
     */
    @Test
    @DisplayName("A declared type changed in place under the same map instance is re-parsed")
    public void bindingIsRebuiltWhenTheDeclaredTypeChangesInPlace() throws Exception {
        ClickHouseSinkConnectorConfig config = config();
        PreparedStatementFieldMapper shared = mapper();
        Map<String, String> sameInstance = columns("DateTime64(6, 'UTC')");
        Map<Integer, Object> before = bind(shared, record(1), sameInstance, config);
        // A DDL applied to the table changed the column's zone; the cache was
        // refreshed into the SAME map object.
        sameInstance.put("ts", "DateTime64(6, 'America/Chicago')");
        Map<Integer, Object> after = bind(shared, record(1), sameInstance, config);
        assertEquals("2022-01-01 16:00:01.000000", before.get(4));
        assertEquals("2022-01-01 10:00:01.000000", after.get(4),
                "an identity-only memo would still render the pre-DDL zone");
    }
}

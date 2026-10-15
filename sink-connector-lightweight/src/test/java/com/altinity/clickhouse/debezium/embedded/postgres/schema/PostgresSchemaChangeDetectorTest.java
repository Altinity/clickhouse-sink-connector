package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PostgresSchemaChangeDetector}.
 *
 * <p>The detector depends on a live ClickHouse JDBC connection only inside
 * {@code fetchClickHouseSchema()} and {@link PostgresSchemaReconciler#addMissingColumns}.
 * Both require a real database, so these unit tests drive the detector with a
 * {@code null} writer and validate behaviour that does <em>not</em> reach the
 * JDBC path – namely:
 * <ul>
 *   <li>Null-safety guards (null record / table / database → no exception)</li>
 *   <li>CDC internal column filtering (_sign, _version, etc.)</li>
 *   <li>Cache key construction</li>
 *   <li>Cache invalidation via {@link PostgresSchemaChangeDetector#invalidateCache}</li>
 *   <li>Cooldown constant is positive</li>
 *   <li>{@link ColumnInfo} value-object contract</li>
 *   <li>Spec 10.04 section 3.9: a genuine schema-drift failure (an
 *       unreachable writer standing in for an unreachable ClickHouse) halts
 *       instead of being swallowed, a failure recorded during the cooldown
 *       window halts again rather than letting the next record through, and
 *       a ClickHouse-only column never triggers reconciliation or a halt
 *       (tests that need a specific cache/failure state prepopulate the
 *       detector's package-private cache fields directly via reflection,
 *       rather than standing up a JDBC mock for logic that is about cache
 *       state, not the JDBC fetch itself)</li>
 * </ul>
 *
 * <p>The end-to-end drift-detection flow (including DDL execution) is covered by
 * the {@code PostgresSchemaDriftIT} integration test.
 */
public class PostgresSchemaChangeDetectorTest {

    /** Detector under test – writer/config are null; JDBC paths are not exercised. */
    private PostgresSchemaChangeDetector detector;

    @BeforeEach
    public void setUp() {
        detector = new PostgresSchemaChangeDetector(null, null);
    }

    // ------------------------------------------------------------------
    // Null-safety: checkAndReconcile must never throw regardless of input
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checkAndReconcile: null record does not throw")
    public void testNullRecordNoThrow() {
        assertDoesNotThrow(() ->
                detector.checkAndReconcile(null, "some_table", "some_db"));
    }

    @Test
    @DisplayName("checkAndReconcile: null tableName does not throw")
    public void testNullTableNameNoThrow() {
        SourceRecord record = buildMinimalRecord();
        assertDoesNotThrow(() ->
                detector.checkAndReconcile(record, null, "some_db"));
    }

    @Test
    @DisplayName("checkAndReconcile: null databaseName does not throw")
    public void testNullDatabaseNameNoThrow() {
        SourceRecord record = buildMinimalRecord();
        assertDoesNotThrow(() ->
                detector.checkAndReconcile(record, "some_table", null));
    }

    @Test
    @DisplayName("checkAndReconcile: all-null args does not throw")
    public void testAllNullArgsNoThrow() {
        assertDoesNotThrow(() ->
                detector.checkAndReconcile(null, null, null));
    }

    // ------------------------------------------------------------------
    // checkAndReconcile: record with no value schema is handled gracefully
    // ------------------------------------------------------------------

    @Test
    @DisplayName("checkAndReconcile: record with null valueSchema does not throw")
    public void testNullValueSchemaNoThrow() {
        // Build a SourceRecord whose value schema is null
        SourceRecord record = new SourceRecord(
                Collections.emptyMap(),
                Collections.emptyMap(),
                "topic",
                null,   // keySchema
                null,   // key
                null,   // valueSchema  ← null
                null    // value
        );
        assertDoesNotThrow(() ->
                detector.checkAndReconcile(record, "t", "db"));
    }

    // ------------------------------------------------------------------
    // Cache invalidation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("invalidateCache: calling on unknown key does not throw")
    public void testInvalidateCacheUnknownKey() {
        assertDoesNotThrow(() -> detector.invalidateCache("nonexistent.key"));
    }

    @Test
    @DisplayName("invalidateCache: removes previously cached key and increments CacheInvalidationManager version")
    public void testInvalidateCacheRemovesKey() {
        String key = "mydb.mytable";
        long v0 = CacheInvalidationManager.getInstance().getVersion(key);
        detector.invalidateCache(key);
        long v1 = CacheInvalidationManager.getInstance().getVersion(key);
        assertTrue(v1 > v0, "invalidateCache must bump CacheInvalidationManager table version");

        detector.invalidateCache(key); // idempotent
        long v2 = CacheInvalidationManager.getInstance().getVersion(key);
        assertTrue(v2 > v1, "second invalidateCache must bump table version again");
    }

    // ------------------------------------------------------------------
    // Cooldown constant sanity
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RECONCILE_COOLDOWN_MS is positive")
    public void testCooldownIsPositive() {
        assertTrue(PostgresSchemaChangeDetector.RECONCILE_COOLDOWN_MS > 0,
                "Cooldown must be > 0 ms");
    }

    @Test
    @DisplayName("RECONCILE_COOLDOWN_MS is at least 1 second")
    public void testCooldownIsAtLeastOneSecond() {
        assertTrue(PostgresSchemaChangeDetector.RECONCILE_COOLDOWN_MS >= 1_000L,
                "Cooldown should be at least 1 s to avoid DDL flooding");
    }

    // ------------------------------------------------------------------
    // ColumnInfo value-object contract (used by detector's cache entries)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ColumnInfo: getters return values supplied at construction")
    public void testColumnInfoGetters() {
        ColumnInfo ci = new ColumnInfo("my_col", "Nullable(Int32)");
        assertEquals("my_col", ci.getName());
        assertEquals("Nullable(Int32)", ci.getType());
    }

    @Test
    @DisplayName("ColumnInfo: equals and hashCode are value-based")
    public void testColumnInfoEquality() {
        ColumnInfo a = new ColumnInfo("col", "Nullable(String)");
        ColumnInfo b = new ColumnInfo("col", "Nullable(String)");
        ColumnInfo c = new ColumnInfo("col", "Nullable(Int32)");

        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("ColumnInfo: toString includes name and type")
    public void testColumnInfoToString() {
        ColumnInfo ci = new ColumnInfo("ts", "Nullable(DateTime64(6))");
        String s = ci.toString();
        assertTrue(s.contains("ts"), "toString should include column name");
        assertTrue(s.contains("Nullable(DateTime64(6))"), "toString should include column type");
    }

    @Test
    @DisplayName("ColumnInfo: null name throws NullPointerException")
    public void testColumnInfoNullNameThrows() {
        assertThrows(NullPointerException.class, () -> new ColumnInfo(null, "Nullable(Int32)"));
    }

    @Test
    @DisplayName("ColumnInfo: null type throws NullPointerException")
    public void testColumnInfoNullTypeThrows() {
        assertThrows(NullPointerException.class, () -> new ColumnInfo("col", null));
    }

    // ------------------------------------------------------------------
    // Schema construction helper – verifies Debezium envelope structure
    // used internally by the detector's extractDebeziumSchema()
    // ------------------------------------------------------------------

    @Test
    @DisplayName("10.04 s3.9: a genuine row with an unreachable writer halts instead of being swallowed")
    public void genuineRowWithUnreachableWriterThrows() {
        // A populated "after" row – a real change, not a no-row control record –
        // against a detector whose writer is null (stands in for "ClickHouse
        // cannot be reached"). Before the fix, checkAndReconcile caught every
        // failure here and returned silently, letting the row through with no
        // confirmation that ClickHouse has the column it needs.
        SourceRecord record = buildRowRecord("drift_test", 42, "test");

        assertThrows(IllegalStateException.class,
                () -> detector.checkAndReconcile(record, "drift_test", "public"),
                "a real row whose ClickHouse schema cannot be fetched must halt, not be skipped");
    }

    @Test
    @DisplayName("10.04 s3.9: CDC-internal fields on a genuine row still halt on an unreachable writer")
    public void cdcInternalColumnsEnvelopeStillThrows() {
        // Simulate a schema that includes CDC-internal columns (_sign, _version, etc.)
        // alongside a real source column; the CDC columns are excluded from the
        // missing-column comparison, but the fetch failure still happens first.
        Schema rowSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("_sign", Schema.INT8_SCHEMA)
                .field("_version", Schema.INT64_SCHEMA)
                .field("_topic", Schema.OPTIONAL_STRING_SCHEMA)
                .field("_offset", Schema.OPTIONAL_INT64_SCHEMA)
                .field("_partition", Schema.OPTIONAL_INT32_SCHEMA)
                .build();

        Schema valueSchema = SchemaBuilder.struct()
                .field("after", rowSchema)
                .field("op", Schema.STRING_SCHEMA)
                .build();

        Struct rowValue = new Struct(rowSchema)
                .put("id", 1)
                .put("_sign", (byte) 1)
                .put("_version", 100L)
                .put("_topic", "my-topic")
                .put("_offset", 0L)
                .put("_partition", 0);

        Struct valueStruct = new Struct(valueSchema)
                .put("after", rowValue)
                .put("op", "c");

        SourceRecord record = new SourceRecord(
                Collections.emptyMap(),
                Collections.emptyMap(),
                "pg.public.cdc_test",
                null,
                null,
                valueSchema,
                valueStruct
        );

        assertThrows(IllegalStateException.class,
                () -> detector.checkAndReconcile(record, "cdc_test", "public"));
    }

    @Test
    @DisplayName("10.04 s3.9: a failure recorded during the cooldown window halts the next record too")
    public void cooldownAfterAFailureStillThrows() throws Exception {
        // Prepopulate the detector's cache state directly: a ClickHouse schema
        // missing the "name" column, a cooldown attempt timestamp that is still
        // active, and a remembered failure from that attempt. This exercises the
        // "cooldown active" branch of checkAndReconcile without needing a JDBC
        // round trip, since that branch's logic is about the failure map, not
        // about fetching the schema.
        String cacheKey = "public.drift_test";
        Map<String, String> chSchemaMissingName = new HashMap<>();
        chSchemaMissingName.put("id", "Int32");

        setPrivateField(detector, "clickHouseSchemaCache",
                withEntry(cacheKey, newCacheEntry(chSchemaMissingName, false)));

        ConcurrentHashMap<String, Long> lastAttempt = new ConcurrentHashMap<>();
        lastAttempt.put(cacheKey, System.currentTimeMillis());
        setPrivateField(detector, "lastReconcileAttempt", lastAttempt);

        RuntimeException priorFailure = new RuntimeException("simulated prior ALTER TABLE failure");
        ConcurrentHashMap<String, RuntimeException> lastFailure = new ConcurrentHashMap<>();
        lastFailure.put(cacheKey, priorFailure);
        setPrivateField(detector, "lastReconcileFailure", lastFailure);

        SourceRecord record = buildRowRecord("drift_test", 42, "test");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> detector.checkAndReconcile(record, "drift_test", "public"),
                "a record arriving inside the cooldown window after a failed reconciliation "
                        + "must halt too, not be let through silently");
        assertSame(priorFailure, thrown,
                "the exact remembered failure must be re-thrown so the halt reason is not lost");
    }

    @Test
    @DisplayName("10.04 s3.9 parity scope: a ClickHouse-only column never triggers reconciliation or a halt")
    public void extraClickHouseOnlyColumnNeverHalts() throws Exception {
        // ClickHouse has every Debezium column PLUS one extra column the source
        // does not have (e.g. a user-added column). Parity is only required for
        // source-matching columns (Spec 10.04 section 3.9 "Parity scope"), so
        // this must take the fast "nothing missing" path and must not throw,
        // even though the detector's writer is null and would fail any JDBC call.
        String cacheKey = "public.drift_test";
        Map<String, String> chSchemaWithExtraColumn = new HashMap<>();
        chSchemaWithExtraColumn.put("id", "Int32");
        chSchemaWithExtraColumn.put("name", "Nullable(String)");
        chSchemaWithExtraColumn.put("clickhouse_only_col", "Nullable(String)");

        setPrivateField(detector, "clickHouseSchemaCache",
                withEntry(cacheKey, newCacheEntry(chSchemaWithExtraColumn, false)));

        SourceRecord record = buildRowRecord("drift_test", 42, "test");

        assertDoesNotThrow(() -> detector.checkAndReconcile(record, "drift_test", "public"),
                "a column present only in ClickHouse must never cause drift detection to act or halt");
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    /** Builds a genuine row record: value = Struct{ after: Struct{ id INT32, name STRING } }. */
    private static SourceRecord buildRowRecord(String topicTable, int id, String name) {
        Schema rowSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.OPTIONAL_STRING_SCHEMA)
                .build();

        Schema valueSchema = SchemaBuilder.struct()
                .field("after", rowSchema)
                .field("op", Schema.STRING_SCHEMA)
                .build();

        Struct rowValue = new Struct(rowSchema)
                .put("id", id)
                .put("name", name);

        Struct valueStruct = new Struct(valueSchema)
                .put("after", rowValue)
                .put("op", "c");

        return new SourceRecord(
                Collections.emptyMap(),
                Collections.emptyMap(),
                "pg.public." + topicTable,
                null,
                null,
                valueSchema,
                valueStruct
        );
    }

    /** Builds a package-private {@code CacheEntry} via reflection (its constructor is package-private). */
    private static Object newCacheEntry(Map<String, String> schema, boolean wasAbsent) throws Exception {
        Class<?> cacheEntryClass = Class.forName(
                "com.altinity.clickhouse.debezium.embedded.postgres.schema.PostgresSchemaChangeDetector$CacheEntry");
        java.lang.reflect.Constructor<?> ctor = cacheEntryClass.getDeclaredConstructor(Map.class, boolean.class);
        ctor.setAccessible(true);
        return ctor.newInstance(schema, wasAbsent);
    }

    private static ConcurrentHashMap<String, Object> withEntry(String key, Object value) {
        ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<>();
        map.put(key, value);
        return map;
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        Field f = PostgresSchemaChangeDetector.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Builds a minimal {@link SourceRecord} with a value schema that has no
     * "after" or "before" field – so {@code extractDebeziumSchema} returns
     * {@code null} and the detector exits early (before any JDBC access).
     */
    private static SourceRecord buildMinimalRecord() {
        Schema valueSchema = SchemaBuilder.struct()
                .field("op", Schema.STRING_SCHEMA)
                .build();

        Struct value = new Struct(valueSchema).put("op", "c");

        return new SourceRecord(
                Collections.emptyMap(),
                Collections.emptyMap(),
                "topic",
                null,
                null,
                valueSchema,
                value
        );
    }
}

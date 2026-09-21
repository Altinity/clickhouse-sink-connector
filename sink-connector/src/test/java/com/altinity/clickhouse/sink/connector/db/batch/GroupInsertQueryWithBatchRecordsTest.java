package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.CacheInvalidationManager;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 08.04 sections 3.1 and 3.3: a source column the ClickHouse table simply
 * does not have is never dropped silently.
 *
 * <p>Before this change {@code refreshIfRecordHasUnknownColumn} treated such a
 * column exactly like an ALIAS: one WARN, {@code markColumnProvenAbsent}, and
 * from then on every record's value for it was omitted from the INSERT with
 * row counts intact. With {@code schema.evolution=false} (the default) nothing
 * ever added the column, so the divergence was permanent.</p>
 *
 * <p>The JDBC objects are JDK proxies over a tiny in-memory {@code system.columns};
 * this module has no mocking framework on its test classpath.</p>
 */
public class GroupInsertQueryWithBatchRecordsTest {

    private static final Schema ROW_SCHEMA = SchemaBuilder.struct()
            .field("id", Schema.INT32_SCHEMA)
            .field("note", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    /**
     * An in-memory stand-in for the parts of ClickHouse the refresh path
     * touches: {@code system.columns} for the table, the default_kind lookup,
     * and DDL execution (an {@code ALTER TABLE ... ADD COLUMN} adds the column).
     */
    private static final class FakeClickHouse {
        /** rows of (name, type, default_kind) */
        final List<String[]> columns = new ArrayList<>();
        /** what the default_kind lookup answers for a column not in {@link #columns}; null = no row */
        String kindForUnknownColumn = null;
        /** when true the declared-type lookup returns no row (enforcement cannot proceed) */
        boolean typeUnreadable = false;
        /** when true the column listing omits {@code note} even after a successful MODIFY */
        boolean hideNoteFromListing = false;
        final List<String> executed = new ArrayList<>();

        FakeClickHouse() {
            columns.add(new String[]{"id", "Int32", ""});
            columns.add(new String[]{"_version", "UInt64", ""});
            columns.add(new String[]{"is_deleted", "UInt8", ""});
        }

        private String[] find(String name) {
            for (String[] c : columns) {
                if (c[0].equalsIgnoreCase(name)) {
                    return c;
                }
            }
            return null;
        }

        private List<String[]> answer(String sql) {
            if (sql.contains("default_kind='ALIAS' or default_kind='MATERIALIZED'")) {
                List<String[]> rows = new ArrayList<>();
                for (String[] c : columns) {
                    if ("ALIAS".equals(c[2]) || "MATERIALIZED".equals(c[2])) {
                        rows.add(new String[]{c[0]});
                    }
                }
                return rows;
            }
            if (sql.startsWith("SELECT name, type, default_kind FROM system.columns")) {
                List<String[]> rows = new ArrayList<>();
                for (String[] c : columns) {
                    if (!(hideNoteFromListing && c[0].equals("note"))) {
                        rows.add(c);
                    }
                }
                return rows;
            }
            if (sql.startsWith("SELECT type FROM system.columns")) {
                if (typeUnreadable) {
                    return Collections.emptyList();
                }
                int start = sql.indexOf("lower('") + 7;
                String[] c = find(sql.substring(start, sql.indexOf("')", start)));
                return c == null ? Collections.emptyList()
                        : Collections.singletonList(new String[]{c[1]});
            }
            if (sql.startsWith("SELECT default_expression FROM system.columns")) {
                return Collections.singletonList(new String[]{"lower(name)"});
            }
            if (sql.startsWith("SELECT default_kind FROM system.columns")) {
                int start = sql.indexOf("lower('") + 7;
                String name = sql.substring(start, sql.indexOf("')", start));
                String[] c = find(name);
                if (c != null) {
                    return Collections.singletonList(new String[]{c[2]});
                }
                return kindForUnknownColumn == null
                        ? Collections.emptyList()
                        : Collections.singletonList(new String[]{kindForUnknownColumn});
            }
            throw new IllegalStateException("unexpected query: " + sql);
        }

        private void execute(String sql) {
            executed.add(sql);
            // The connector emits "add column" in lower case; match case-insensitively.
            String upper = sql.toUpperCase();
            if (upper.startsWith("ALTER TABLE") && upper.contains("ADD COLUMN")) {
                int at = upper.indexOf("ADD COLUMN `");
                while (at >= 0) {
                    int nameStart = at + "ADD COLUMN `".length();
                    String name = sql.substring(nameStart, sql.indexOf('`', nameStart));
                    if (find(name) == null) {
                        columns.add(new String[]{name, "Nullable(String)", ""});
                    }
                    at = upper.indexOf("ADD COLUMN `", nameStart);
                }
            }
            // MODIFY COLUMN `x` ... DEFAULT ...: the conversion took effect.
            if (upper.startsWith("ALTER TABLE") && upper.contains("MODIFY COLUMN `")
                    && upper.contains(" DEFAULT ")) {
                int nameStart = upper.indexOf("MODIFY COLUMN `") + "MODIFY COLUMN `".length();
                String[] c = find(sql.substring(nameStart, sql.indexOf('`', nameStart)));
                if (c != null) {
                    c[2] = "DEFAULT";
                }
            }
        }

        private static Object defaultFor(Class<?> type) {
            if (!type.isPrimitive() || type == void.class) {
                return null;
            }
            return type == boolean.class ? Boolean.FALSE : 0;
        }

        private ResultSet resultSet(List<String[]> rows, String[] header) {
            final int[] cursor = {-1};
            InvocationHandler h = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        cursor[0]++;
                        return cursor[0] < rows.size();
                    case "getString":
                        String[] row = rows.get(cursor[0]);
                        if (args[0] instanceof Integer) {
                            return row[(Integer) args[0] - 1];
                        }
                        for (int i = 0; i < header.length; i++) {
                            if (header[i].equals(args[0])) {
                                return row[i];
                            }
                        }
                        return null;
                    case "close":
                        return null;
                    default:
                        return defaultFor(method.getReturnType());
                }
            };
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, h);
        }

        Connection connection() {
            final String[] header = {"name", "type", "default_kind"};
            InvocationHandler statement = (proxy, method, args) -> {
                if ("executeQuery".equals(method.getName())) {
                    return resultSet(answer((String) args[0]), header);
                }
                return defaultFor(method.getReturnType());
            };
            final Statement stmt = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Statement.class}, statement);

            InvocationHandler connection = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createStatement":
                        return stmt;
                    case "prepareStatement":
                        final String sql = (String) args[0];
                        InvocationHandler ps = (p, m, a) -> {
                            if ("execute".equals(m.getName())) {
                                execute(sql);
                                return false;
                            }
                            if ("executeQuery".equals(m.getName())) {
                                return resultSet(answer(sql), header);
                            }
                            return defaultFor(m.getReturnType());
                        };
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{PreparedStatement.class}, ps);
                    case "toString":
                        return "FakeClickHouseConnection";
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    default:
                        return defaultFor(method.getReturnType());
                }
            };
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, connection);
        }
    }

    private static ClickHouseSinkConnectorConfig config(boolean schemaEvolution) {
        Map<String, String> props = new HashMap<>();
        props.put(ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString(),
                Boolean.toString(schemaEvolution));
        props.put(ClickHouseSinkConnectorConfigVariables.CONNECTION_POOL_DISABLE.toString(), "true");
        return new ClickHouseSinkConnectorConfig(props);
    }

    /** The cached map: it predates the source's ADD COLUMN note. */
    private static Map<String, String> cachedWithoutNote() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", "Int32");
        m.put("_version", "UInt64");
        m.put("is_deleted", "UInt8");
        return m;
    }

    private static ClickHouseStruct insertCarryingNote() {
        Struct after = new Struct(ROW_SCHEMA).put("id", 1).put("note", "hello");
        ClickHouseStruct record = new ClickHouseStruct(
                5L, "topic", null, 0, System.currentTimeMillis(),
                null, after, null, ClickHouseConverter.CDC_OPERATION.CREATE);
        record.setDatabase("db");
        return record;
    }

    /** Groups one INSERT and returns its (single) segment. */
    private static Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> group(
            FakeClickHouse ch, ClickHouseSinkConnectorConfig config) {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                new ArrayList<>();
        Map<TopicPartition, Long> offsets = new HashMap<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                Collections.singletonList(insertCarryingNote()), segments, offsets, config,
                "t", "db", ch.connection(), cachedWithoutNote());
        assertEquals(1, segments.size(), "one INSERT is one segment");
        return segments.get(0);
    }

    @BeforeEach
    public void resetProvenAbsent() {
        CacheInvalidationManager.getInstance().clearAll();
    }

    /**
     * The column does not exist in ClickHouse at all (no system.columns row,
     * so default_kind reads null). With schema evolution off the batch must
     * fail naming the column and the knob -- not omit the value.
     */
    @Test
    @DisplayName("A plain column the table lacks fails the batch instead of being dropped")
    public void missingPlainColumnFailsBatch() {
        FakeClickHouse ch = new FakeClickHouse();

        MissingTargetColumnException e = assertThrows(MissingTargetColumnException.class,
                () -> group(ch, config(false)),
                "the record carries 'note' and ClickHouse cannot store it; writing the row "
                        + "without it is silent divergence with matching row counts");

        assertTrue(e.getMessage().contains("note"), e.getMessage());
        assertTrue(e.getMessage().contains("db"), e.getMessage());
        assertTrue(e.getMessage().contains("t"), e.getMessage());
        assertTrue(e.getMessage().contains(
                ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION.toString()), e.getMessage());
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"),
                "a missing column must never be recorded as proven-absent: that is what "
                        + "made the value disappear from every later INSERT");
    }

    /** Same, when default_kind answers "" for the column (a stored column the writable map lacks). */
    @Test
    @DisplayName("A column whose default_kind reads empty is treated as missing, not as ALIAS")
    public void missingPlainColumnWithEmptyKindFailsBatch() {
        FakeClickHouse ch = new FakeClickHouse();
        ch.kindForUnknownColumn = "";

        assertThrows(MissingTargetColumnException.class, () -> group(ch, config(false)));
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"));
    }

    /**
     * With schema evolution on the connector corrects the replica: it issues
     * ALTER TABLE ... ADD COLUMN and THIS batch binds the value.
     */
    @Test
    @DisplayName("With schema.evolution=true the column is added and bound in the same batch")
    public void missingPlainColumnIsAddedWhenSchemaEvolutionEnabled() {
        FakeClickHouse ch = new FakeClickHouse();

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queries =
                group(ch, config(true));

        boolean added = false;
        for (String sql : ch.executed) {
            if (sql.toUpperCase().contains("ADD COLUMN") && sql.contains("`note`")) {
                added = true;
            }
        }
        assertTrue(added, "an ALTER TABLE ... ADD COLUMN `note` must be issued; executed: " + ch.executed);
        assertEquals(1, queries.size());
        MutablePair<String, Map<String, Integer>> key = queries.keySet().iterator().next();
        assertTrue(key.getRight().containsKey("note"),
                "the INSERT built for this batch must bind note: " + key.getLeft());
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"));
    }

    /**
     * A MATERIALIZED column whose conversion to DEFAULT cannot be performed
     * (here: the declared type is unreadable, so enforcement returns false)
     * must fail the batch, not be recorded as proven-absent. Continuing would
     * write the row with ClickHouse's computed value in place of the source's
     * and advance the offset past it -- the same silent-divergence class as a
     * missing column.
     */
    @Test
    @DisplayName("A MATERIALIZED column whose conversion fails fails the batch")
    public void materializedColumnWhoseConversionFailsFailsBatch() {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(new String[]{"note", "String", "MATERIALIZED"});
        ch.typeUnreadable = true;

        MissingTargetColumnException e = assertThrows(MissingTargetColumnException.class,
                () -> group(ch, config(false)));

        assertTrue(e.getMessage().contains("note"), e.getMessage());
        assertTrue(e.getMessage().contains("MATERIALIZED"), e.getMessage());
        assertTrue(e.getMessage().contains("MODIFY COLUMN"), e.getMessage());
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"),
                "a failed conversion must never be recorded as proven-absent");
        assertTrue(ch.executed.isEmpty(), "no DDL can be issued without the type: " + ch.executed);
    }

    /**
     * The conversion reads back as DEFAULT but the re-read column map still
     * lacks the column: the source value still cannot be bound, so the batch
     * fails rather than proceeding on the stale map.
     */
    @Test
    @DisplayName("A MATERIALIZED column still missing after conversion fails the batch")
    public void materializedColumnStillMissingAfterConversionFailsBatch() {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(new String[]{"note", "String", "MATERIALIZED"});
        ch.hideNoteFromListing = true;

        MissingTargetColumnException e = assertThrows(MissingTargetColumnException.class,
                () -> group(ch, config(false)));

        assertTrue(e.getMessage().contains("note"), e.getMessage());
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"));
        boolean modified = false;
        for (String sql : ch.executed) {
            if (sql.toUpperCase().contains("MODIFY COLUMN `NOTE`")) {
                modified = true;
            }
        }
        assertTrue(modified, "the conversion DDL must have been attempted: " + ch.executed);
    }

    /** The successful conversion binds the source value in the same batch. */
    @Test
    @DisplayName("A MATERIALIZED column converted to DEFAULT is bound in the same batch")
    public void materializedColumnConvertedIsBoundInSameBatch() {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(new String[]{"note", "String", "MATERIALIZED"});

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queries =
                group(ch, config(false));

        assertEquals(1, queries.size());
        MutablePair<String, Map<String, Integer>> key = queries.keySet().iterator().next();
        assertTrue(key.getRight().containsKey("note"), key.getLeft());
        assertFalse(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"));
        assertEquals("ALTER TABLE `db`.`t` MODIFY COLUMN `note` String DEFAULT lower(name)",
                ch.executed.get(0));
    }

    private static List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> groupOne(
            ClickHouseStruct record, Map<String, String> columns) {
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                new ArrayList<>();
        new GroupInsertQueryWithBatchRecords().groupQueryWithRecords(
                new ArrayList<>(Collections.singletonList(record)), segments, new HashMap<>(),
                config(false), "t", "db", null, columns);
        return segments;
    }

    /**
     * Spec 04.01 section 3.3: a record that cannot be grouped fails the batch.
     * Returning {@code false} for it dropped the row with no error while the
     * batch's offset advanced past it.
     */
    @Test
    @DisplayName("A DELETE without a before image fails the batch instead of being dropped")
    public void deleteWithoutBeforeImageFailsLoudly() {
        ClickHouseStruct delete = new ClickHouseStruct(
                9L, "topic", null, 0, System.currentTimeMillis(),
                null, null, null, ClickHouseConverter.CDC_OPERATION.DELETE);
        delete.setDatabase("db");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> groupOne(delete, cachedWithoutNote()),
                "a DELETE with no before image has no row to write; dropping it silently "
                        + "leaves the row alive in ClickHouse while MySQL deleted it");
        assertTrue(e.getMessage().contains("before"), e.getMessage());
        assertTrue(e.getMessage().contains("offset=9"), e.getMessage());
    }

    /**
     * Worse than a drop: an UPDATE lacking its after image used to be grouped
     * by its before image alone, i.e. written as a LIVE row of the pre-update
     * values.
     */
    @Test
    @DisplayName("An UPDATE without an after image fails the batch instead of writing its before image live")
    public void updateWithoutAfterImageFailsLoudly() {
        ClickHouseStruct update = new ClickHouseStruct(
                10L, "topic", null, 0, System.currentTimeMillis(),
                new Struct(ROW_SCHEMA).put("id", 1).put("note", "old"), null, null,
                ClickHouseConverter.CDC_OPERATION.UPDATE);
        update.setDatabase("db");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> groupOne(update, cachedWithoutNote()));
        assertTrue(e.getMessage().contains("after"), e.getMessage());
    }

    /** No column metadata: an explicit failure naming the table, not a NullPointerException. */
    @Test
    @DisplayName("Grouping without ClickHouse column metadata fails the batch instead of dropping the record")
    public void missingColumnMapFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> groupOne(insertCarryingNote(), null));
        assertTrue(e.getMessage().contains("column metadata"), e.getMessage());
        assertTrue(e.getMessage().contains("table t"), e.getMessage());
    }

    /**
     * Spec 04.02 section 3.1: the resolved engine columns handed to the
     * grouper become bind parameters of the INSERT, whatever they are called.
     */
    @Test
    @DisplayName("Resolved version / delete column names become INSERT parameters")
    public void resolvedEngineColumnsAreBoundParameters() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("id", "Int32");
        table.put("note", "Nullable(String)");
        table.put("ver", "UInt64");
        table.put("removed", "UInt8");
        List<Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>>> segments =
                new ArrayList<>();

        new GroupInsertQueryWithBatchRecords("ver", null, "removed").groupQueryWithRecords(
                new ArrayList<>(Collections.singletonList(insertCarryingNote())), segments,
                new HashMap<>(), config(false), "t", "db", null, table);

        MutablePair<String, Map<String, Integer>> key = segments.get(0).keySet().iterator().next();
        assertTrue(key.getRight().containsKey("ver"), "ver must be a parameter: " + key.getLeft());
        assertTrue(key.getRight().containsKey("removed"), "removed must be a parameter: " + key.getLeft());
    }

    /** Regression guard: an ALIAS column keeps the pre-existing behaviour. */
    @Test
    @DisplayName("An ALIAS column is still ignored and proven absent")
    public void aliasColumnIsStillIgnoredAndProvenAbsent() {
        FakeClickHouse ch = new FakeClickHouse();
        ch.columns.add(new String[]{"note", "String", "ALIAS"});

        Map<MutablePair<String, Map<String, Integer>>, List<ClickHouseStruct>> queries =
                group(ch, config(false));

        assertEquals(1, queries.size());
        MutablePair<String, Map<String, Integer>> key = queries.keySet().iterator().next();
        assertFalse(key.getRight().containsKey("note"), "an ALIAS is not writable: " + key.getLeft());
        assertTrue(CacheInvalidationManager.getInstance().isColumnProvenAbsent("db.t", "note"),
                "ALIAS is the one case that is legitimately proven absent");
        assertTrue(ch.executed.isEmpty(), "no DDL for an ALIAS: " + ch.executed);
    }
}

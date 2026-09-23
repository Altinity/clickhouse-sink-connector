package com.altinity.clickhouse.debezium.embedded.ddl.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rebuild a source {@code ALTER TABLE} that changes a table's row identity
 * requires on the replica (Spec 06.09 §3.1), as produced by
 * {@code MySqlDDLParserListenerImpl.enforcePrimaryKeyPolicy} and executed by
 * {@code PrimaryKeyRebuild} at the DDL barrier.
 *
 * <p>Immutable. Identifiers are <b>clean</b> (no backticks, no database
 * prefix). The old key is the replica's current sorting key with the
 * connector's own columns removed, lower-cased as {@code enforcePrimaryKeyPolicy}
 * compares it; the new key is the declared column list in declaration order,
 * or -- for {@code DROP PRIMARY KEY} without a replacement -- every stored
 * non-connector column of the table after the statement
 * ({@link #keylessFallback()}).</p>
 */
public final class PrimaryKeyRebuildPlan {

    /** Where the rebuild reads a new-key column's values from (Spec 06.09 §3.1). */
    public enum Provenance {
        /** The column exists in the ClickHouse table before this statement: read from the old table. */
        EXISTING,
        /** Added by this statement without {@code AUTO_INCREMENT}: ClickHouse back-fills the ADD COLUMN default. */
        ADDED_DEFAULTED,
        /** Added by this statement with {@code AUTO_INCREMENT}: read from the source, keyed by the old identity. */
        SOURCE_VALUED
    }

    private final String database;
    private final String table;
    private final List<String> oldKey;
    private final List<String> newKey;
    private final boolean keylessFallback;
    private final Map<String, Provenance> provenance;
    private final String sourceSql;
    private final List<String> deferredClauses;
    private final Map<String, String> renamedColumns;
    private final Map<String, String> retypedColumns;

    /**
     * A plan without deferred clauses (Spec 06.09 §3.1).
     *
     * @param database        destination database (clean).
     * @param table           table name (clean).
     * @param oldKey          the replica's current sorting key, connector columns removed, lower-cased.
     * @param newKey          the new key, clean names in declaration (or position) order.
     * @param keylessFallback whether {@code newKey} is the all-columns identity of a
     *                        keyless table (DROP PRIMARY KEY without a replacement).
     * @param provenance      per new-key column, where its values come from.
     * @param sourceSql       the source statement, for log messages.
     */
    public PrimaryKeyRebuildPlan(String database, String table, List<String> oldKey, List<String> newKey,
                                 boolean keylessFallback, Map<String, Provenance> provenance, String sourceSql) {
        this(database, table, oldKey, newKey, keylessFallback, provenance, sourceSql,
                Collections.emptyList(), Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * @param database        destination database (clean).
     * @param table           table name (clean).
     * @param oldKey          the replica's current sorting key, connector columns removed, lower-cased.
     * @param newKey          the new key, clean names in declaration (or position) order;
     *                        a renamed key column is named by its NEW name.
     * @param keylessFallback whether {@code newKey} is the all-columns identity of a
     *                        keyless table (DROP PRIMARY KEY without a replacement).
     * @param provenance      per new-key column, where its values come from.
     * @param sourceSql       the source statement, for log messages.
     * @param deferredClauses ClickHouse {@code ALTER TABLE} clause texts (without the
     *                        {@code ALTER TABLE x} prefix) the statement applies to an
     *                        old-key column, which the translator did not emit against
     *                        the current table and the rebuild applies to the rebuilt
     *                        one instead (Spec 06.09 §3.1.1): {@code DROP COLUMN IF
     *                        EXISTS k}, then {@code RENAME COLUMN k TO k2}, then
     *                        {@code MODIFY COLUMN k <type>}, in that order.
     * @param renamedColumns  old clean name -> new clean name of every old-key column
     *                        the statement renames; the copy reads {@code o.old AS new}.
     * @param retypedColumns  clean name (the NEW name when also renamed) -> the
     *                        translated ClickHouse type of every old-key column the
     *                        statement re-types (a widening or non-comparable change).
     */
    public PrimaryKeyRebuildPlan(String database, String table, List<String> oldKey, List<String> newKey,
                                 boolean keylessFallback, Map<String, Provenance> provenance, String sourceSql,
                                 List<String> deferredClauses, Map<String, String> renamedColumns,
                                 Map<String, String> retypedColumns) {
        this.database = database;
        this.table = table;
        this.oldKey = Collections.unmodifiableList(new ArrayList<>(oldKey));
        this.newKey = Collections.unmodifiableList(new ArrayList<>(newKey));
        this.keylessFallback = keylessFallback;
        this.provenance = Collections.unmodifiableMap(new LinkedHashMap<>(provenance));
        this.sourceSql = sourceSql;
        this.deferredClauses = Collections.unmodifiableList(new ArrayList<>(deferredClauses));
        this.renamedColumns = Collections.unmodifiableMap(new LinkedHashMap<>(renamedColumns));
        this.retypedColumns = Collections.unmodifiableMap(new LinkedHashMap<>(retypedColumns));
    }

    /** @return destination database (clean). */
    public String database() {
        return database;
    }

    /** @return table name (clean). */
    public String table() {
        return table;
    }

    /** @return the replica's current sorting key, connector columns removed, lower-cased. */
    public List<String> oldKey() {
        return oldKey;
    }

    /** @return the new key, clean names in declaration (or position) order. */
    public List<String> newKey() {
        return newKey;
    }

    /** @return whether the new key is the keyless all-columns fallback (Spec 06.05 §3.6). */
    public boolean keylessFallback() {
        return keylessFallback;
    }

    /** @return per new-key column, where the rebuild reads its values from. */
    public Map<String, Provenance> provenance() {
        return provenance;
    }

    /** @return the source statement, for log messages. */
    public String sourceSql() {
        return sourceSql;
    }

    /** @return the new-key columns whose values only the source knows, in new-key order. */
    public List<String> sourceValuedColumns() {
        List<String> out = new ArrayList<>();
        for (String column : newKey) {
            if (provenance.get(column) == Provenance.SOURCE_VALUED) {
                out.add(column);
            }
        }
        return out;
    }

    /** @return whether the rebuild must read new-key values from the source (Spec 06.09 §3.4). */
    public boolean requiresSourceKeyMap() {
        return !sourceValuedColumns().isEmpty();
    }

    /**
     * @return the ClickHouse {@code ALTER TABLE} clauses on old-key columns the
     *         translator deferred to the rebuilt table (Spec 06.09 §3.1.1), in
     *         application order: DROP, then RENAME, then MODIFY; empty when none.
     */
    public List<String> deferredClauses() {
        return deferredClauses;
    }

    /** @return old clean name -> new clean name of every renamed old-key column; empty when none. */
    public Map<String, String> renamedColumns() {
        return renamedColumns;
    }

    /**
     * @return clean name (the new name when also renamed) -> translated ClickHouse
     *         type of every re-typed old-key column; empty when none.
     */
    public Map<String, String> retypedColumns() {
        return retypedColumns;
    }

    @Override
    public String toString() {
        return "PrimaryKeyRebuildPlan{" + database + "." + table + ": oldKey=" + oldKey + ", newKey=" + newKey
                + (keylessFallback ? " (keyless all-columns fallback)" : "") + ", provenance=" + provenance
                + (deferredClauses.isEmpty() ? "" : ", deferredClauses=" + deferredClauses)
                + (renamedColumns.isEmpty() ? "" : ", renamedColumns=" + renamedColumns)
                + (retypedColumns.isEmpty() ? "" : ", retypedColumns=" + retypedColumns) + "}";
    }
}

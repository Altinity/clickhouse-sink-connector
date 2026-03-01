package com.altinity.clickhouse.debezium.embedded.postgres.schema;

import java.util.Objects;

/**
 * Immutable value object representing a single column's name and type.
 * Used as the element type for per-table schema caches in
 * {@link PostgresSchemaChangeDetector}.
 */
public final class ColumnInfo {

    private final String name;
    private final String type;

    /**
     * Constructs a ColumnInfo with the given name and ClickHouse type string.
     *
     * @param name the column name (case-sensitive)
     * @param type the ClickHouse column type string (e.g. {@code Nullable(Int32)})
     */
    public ColumnInfo(String name, String type) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    /**
     * Returns the column name.
     *
     * @return column name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the ClickHouse type string for this column.
     *
     * @return ClickHouse type string
     */
    public String getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnInfo)) return false;
        ColumnInfo that = (ColumnInfo) o;
        return Objects.equals(name, that.name) && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return "ColumnInfo{name='" + name + "', type='" + type + "'}";
    }
}

package com.altinity.clickhouse.sink.connector.metadata;

import org.apache.kafka.connect.data.Schema;

import java.util.Objects;

/**
 * The SchemaPair class represents a pair of schemas, one for the key and one for the value.
 * <p>
 * This class is used to store and compare key-value pairs of schemas. It provides
 * methods to check equality, generate hash codes, and print a string representation
 * of the SchemaPair.
 * </p>
 */
public class SchemaPair {

    /**
     * The schema for the key.
     */
    public final Schema keySchema;

    /**
     * The schema for the value.
     */
    public final Schema valueSchema;

    /**
     * Constructor to initialize the SchemaPair with the given key and value schemas.
     *
     * @param keySchema the schema for the key.
     * @param valueSchema the schema for the value.
     */
    public SchemaPair(Schema keySchema, Schema valueSchema) {
        this.keySchema = keySchema;
        this.valueSchema = valueSchema;
    }

    /**
     * Compares this SchemaPair to another object for equality.
     * <p>
     * Two SchemaPair objects are considered equal if their key and value schemas
     * are both equal.
     * </p>
     *
     * @param o the object to compare this SchemaPair to.
     * @return true if the given object is equal to this SchemaPair.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SchemaPair that = (SchemaPair) o;
        return Objects.equals(keySchema, that.keySchema)
                && Objects.equals(valueSchema, that.valueSchema);
    }

    /**
     * Returns a hash code for this SchemaPair.
     * <p>
     * The hash code is generated based on the key and value schemas.
     * </p>
     *
     * @return a hash code for this SchemaPair.
     */
    @Override
    public int hashCode() {
        return Objects.hash(keySchema, valueSchema);
    }

    /**
     * Returns a string representation of the SchemaPair.
     * <p>
     * The string representation includes both the key and value schemas.
     * </p>
     *
     * @return a string representation of this SchemaPair.
     */
    @Override
    public String toString() {
        return String.format("<SchemaPair: %s, %s>", keySchema, valueSchema);
    }
}

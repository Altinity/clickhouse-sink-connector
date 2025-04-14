package com.altinity.clickhouse.sink.connector.metadata;

import org.apache.kafka.connect.data.Schema;

import java.util.Map;

/**
 * The SinkRecordField class represents a field in a sink record, which includes
 * the schema, name, and whether the field is part of the primary key.
 * <p>
 * This class encapsulates the schema of the field, its name, and its primary key
 * status, allowing for easier handling and processing of sink record fields.
 * </p>
 */
public class SinkRecordField {

    /**
     * The schema of the field.
     */
    private final Schema schema;

    /**
     * The name of the field.
     */
    private final String name;

    /**
     * Flag indicating whether the field is part of the primary key.
     */
    private final boolean isPrimaryKey;

    /**
     * Constructs a new SinkRecordField with the given schema, name, and primary key status.
     *
     * @param schema the schema of the field.
     * @param name the name of the field.
     * @param isPrimaryKey true if the field is part of the primary key, false otherwise.
     */
    public SinkRecordField(Schema schema, String name, boolean isPrimaryKey) {
        this.schema = schema;
        this.name = name;
        this.isPrimaryKey = isPrimaryKey;
    }

    /**
     * Gets the schema of the field.
     *
     * @return the schema of the field.
     */
    public Schema schema() {
        return schema;
    }

    /**
     * Gets the name of the schema.
     *
     * @return the name of the schema.
     */
    public String schemaName() {
        return schema.name();
    }

    /**
     * Gets the parameters of the schema.
     *
     * @return a map of the schema parameters.
     */
    public Map<String, String> schemaParameters() {
        return schema.parameters();
    }

    /**
     * Gets the type of the schema.
     *
     * @return the type of the schema.
     */
    public Schema.Type schemaType() {
        return schema.type();
    }

    /**
     * Gets the name of the field.
     *
     * @return the name of the field.
     */
    public String name() {
        return name;
    }

    /**
     * Checks if the field is optional.
     * <p>
     * A field is considered optional if it is not part of the primary key and its
     * schema is optional.
     * </p>
     *
     * @return true if the field is optional, false otherwise.
     */
    public boolean isOptional() {
        return !isPrimaryKey && schema.isOptional();
    }

    /**
     * Gets the default value of the field.
     *
     * @return the default value of the field, or null if not defined.
     */
    public Object defaultValue() {
        return schema.defaultValue();
    }

    /**
     * Checks if the field is part of the primary key.
     *
     * @return true if the field is part of the primary key, false otherwise.
     */
    public boolean isPrimaryKey() {
        return isPrimaryKey;
    }

    /**
     * Returns a string representation of the SinkRecordField object.
     *
     * @return a string representation of the SinkRecordField object.
     */
    @Override
    public String toString() {
        return "SinkRecordField{"
                + "schema=" + schema
                + ", name='" + name + '\''
                + ", isPrimaryKey=" + isPrimaryKey
                + '}';
    }
}

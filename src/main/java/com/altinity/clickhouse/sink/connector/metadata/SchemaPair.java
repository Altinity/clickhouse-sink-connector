package com.altinity.clickhouse.sink.connector.metadata;


import org.apache.kafka.connect.data.Schema;

import java.util.Objects;

public class SchemaPair {
    public final Schema keySchema;
    public final Schema valueSchema;

    public SchemaPair(Schema keySchema, Schema valueSchema) {
        this.keySchema = keySchema;
        this.valueSchema = valueSchema;
    }

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

    @Override
    public int hashCode() {
        return Objects.hash(keySchema, valueSchema);
    }

    public String toString() {
        return String.format("<SchemaPair: %s, %s>", keySchema, valueSchema);
    }
}

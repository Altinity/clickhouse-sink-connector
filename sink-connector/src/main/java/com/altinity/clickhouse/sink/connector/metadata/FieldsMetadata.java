package com.altinity.clickhouse.sink.connector.metadata;


import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;

import java.util.*;


public class FieldsMetadata {

    public final Set<String> keyFieldNames;
    public final Set<String> nonKeyFieldNames;
    public final Map<String, SinkRecordField> allFields;

    // visible for testing
    public FieldsMetadata(
            Set<String> keyFieldNames,
            Set<String> nonKeyFieldNames,
            Map<String, SinkRecordField> allFields
    ) {
        boolean fieldCountsMatch = (keyFieldNames.size() + nonKeyFieldNames.size() == allFields.size());
        boolean allFieldsContained = (allFields.keySet().containsAll(keyFieldNames)
                && allFields.keySet().containsAll(nonKeyFieldNames));
        if (!fieldCountsMatch || !allFieldsContained) {
            throw new IllegalArgumentException(String.format(
                    "Validation fail -- keyFieldNames:%s nonKeyFieldNames:%s allFields:%s",
                    keyFieldNames, nonKeyFieldNames, allFields
            ));
        }
        this.keyFieldNames = keyFieldNames;
        this.nonKeyFieldNames = nonKeyFieldNames;
        this.allFields = allFields;
    }

    public static FieldsMetadata extract(
            final String tableName,
            final SinkConfig.PrimaryKeyMode pkMode,
            final List<String> configuredPkFields,
            final Set<String> fieldsWhitelist,
            final SchemaPair schemaPair
    ) {
        return extract(
                tableName,
                pkMode,
                configuredPkFields,
                fieldsWhitelist,
                schemaPair.keySchema,
                schemaPair.valueSchema
        );
    }

    public static FieldsMetadata extract(
            final String tableName,
            final SinkConfig.PrimaryKeyMode pkMode,
            final List<String> configuredPkFields,
            final Set<String> fieldsWhitelist,
            final Schema keySchema,
            final Schema valueSchema
    ) {
        if (valueSchema != null && valueSchema.type() != Schema.Type.STRUCT) {
            throw new ConnectException("Value schema must be of type Struct");
        }

        final Map<String, SinkRecordField> allFields = new HashMap<>();

        final Set<String> keyFieldNames = new LinkedHashSet<>();
        switch (pkMode) {
            case NONE:
                break;

            case KAFKA:
                extractKafkaPk(tableName, configuredPkFields, allFields, keyFieldNames);
                break;

            case RECORD_KEY:
                extractRecordKeyPk(tableName, configuredPkFields, keySchema, allFields, keyFieldNames);
                break;

            case RECORD_VALUE:
                extractRecordValuePk(tableName, configuredPkFields, valueSchema, allFields, keyFieldNames);
                break;

            default:
                throw new ConnectException("Unknown primary key mode: " + pkMode);
        }

        final Set<String> nonKeyFieldNames = new LinkedHashSet<>();
        if (valueSchema != null) {
            for (Field field : valueSchema.fields()) {
                if (keyFieldNames.contains(field.name())) {
                    continue;
                }
                if (!fieldsWhitelist.isEmpty() && !fieldsWhitelist.contains(field.name())) {
                    continue;
                }

                nonKeyFieldNames.add(field.name());

                final Schema fieldSchema = field.schema();
                allFields.put(field.name(), new SinkRecordField(fieldSchema, field.name(), false));
            }
        }

        if (allFields.isEmpty()) {
            throw new ConnectException(
                    "No fields found using key and value schemas for table: " + tableName
            );
        }

        final Map<String, SinkRecordField> allFieldsOrdered = new LinkedHashMap<>();
        for (String fieldName : SinkConfig.DEFAULT_KAFKA_PK_NAMES) {
            if (allFields.containsKey(fieldName)) {
                allFieldsOrdered.put(fieldName, allFields.get(fieldName));
            }
        }

        if (valueSchema != null) {
            for (Field field : valueSchema.fields()) {
                String fieldName = field.name();
                if (allFields.containsKey(fieldName)) {
                    allFieldsOrdered.put(fieldName, allFields.get(fieldName));
                }
            }
        }

        if (allFieldsOrdered.size() < allFields.size()) {
            ArrayList<String> fieldKeys = new ArrayList<>(allFields.keySet());
            Collections.sort(fieldKeys);
            for (String fieldName : fieldKeys) {
                if (!allFieldsOrdered.containsKey(fieldName)) {
                    allFieldsOrdered.put(fieldName, allFields.get(fieldName));
                }
            }
        }

        return new FieldsMetadata(keyFieldNames, nonKeyFieldNames, allFieldsOrdered);
    }

    private static void extractKafkaPk(
            final String tableName,
            final List<String> configuredPkFields,
            final Map<String, SinkRecordField> allFields,
            final Set<String> keyFieldNames
    ) {
        if (configuredPkFields.isEmpty()) {
            keyFieldNames.addAll(SinkConfig.DEFAULT_KAFKA_PK_NAMES);
        } else if (configuredPkFields.size() == 3) {
            keyFieldNames.addAll(configuredPkFields);
        } else {
            throw new ConnectException(String.format(
                    "PK mode for table '%s' is %s so there should either be no field names defined for "
                            + "defaults %s to be applicable, or exactly 3, defined fields are: %s",
                    tableName,
                    SinkConfig.PrimaryKeyMode.KAFKA,
                    SinkConfig.DEFAULT_KAFKA_PK_NAMES,
                    configuredPkFields
            ));
        }
        final Iterator<String> it = keyFieldNames.iterator();
        final String topicFieldName = it.next();
        allFields.put(
                topicFieldName,
                new SinkRecordField(Schema.STRING_SCHEMA, topicFieldName, true)
        );
        final String partitionFieldName = it.next();
        allFields.put(
                partitionFieldName,
                new SinkRecordField(Schema.INT32_SCHEMA, partitionFieldName, true)
        );
        final String offsetFieldName = it.next();
        allFields.put(
                offsetFieldName,
                new SinkRecordField(Schema.INT64_SCHEMA, offsetFieldName, true)
        );
    }

    private static void extractRecordKeyPk(
            final String tableName,
            final List<String> configuredPkFields,
            final Schema keySchema,
            final Map<String, SinkRecordField> allFields,
            final Set<String> keyFieldNames
    ) {
        {
            if (keySchema == null) {
                throw new ConnectException(String.format(
                        "PK mode for table '%s' is %s, but record key schema is missing",
                        tableName,
                        SinkConfig.PrimaryKeyMode.RECORD_KEY
                ));
            }
            final Schema.Type keySchemaType = keySchema.type();
            if (keySchemaType.isPrimitive()) {
                if (configuredPkFields.size() != 1) {
                    throw new ConnectException(String.format(
                            "Need exactly one PK column defined since the key schema for records is a "
                                    + "primitive type, defined columns are: %s",
                            configuredPkFields
                    ));
                }
                final String fieldName = configuredPkFields.get(0);
                keyFieldNames.add(fieldName);
                allFields.put(fieldName, new SinkRecordField(keySchema, fieldName, true));
            } else if (keySchemaType == Schema.Type.STRUCT) {
                if (configuredPkFields.isEmpty()) {
                    for (Field keyField : keySchema.fields()) {
                        keyFieldNames.add(keyField.name());
                    }
                } else {
                    for (String fieldName : configuredPkFields) {
                        final Field keyField = keySchema.field(fieldName);
                        if (keyField == null) {
                            throw new ConnectException(String.format(
                                    "PK mode for table '%s' is %s with configured PK fields %s, but record key "
                                            + "schema does not contain field: %s",
                                    tableName, SinkConfig.PrimaryKeyMode.RECORD_KEY, configuredPkFields, fieldName
                            ));
                        }
                    }
                    keyFieldNames.addAll(configuredPkFields);
                }
                for (String fieldName : keyFieldNames) {
                    final Schema fieldSchema = keySchema.field(fieldName).schema();
                    allFields.put(fieldName, new SinkRecordField(fieldSchema, fieldName, true));
                }
            } else {
                throw new ConnectException(
                        "Key schema must be primitive type or Struct, but is of type: " + keySchemaType
                );
            }
        }
    }

    private static void extractRecordValuePk(
            final String tableName,
            final List<String> configuredPkFields,
            final Schema valueSchema,
            final Map<String, SinkRecordField> allFields,
            final Set<String> keyFieldNames
    ) {
        if (valueSchema == null) {
            throw new ConnectException(String.format(
                    "PK mode for table '%s' is %s, but record value schema is missing",
                    tableName,
                    SinkConfig.PrimaryKeyMode.RECORD_VALUE)
            );
        }
        if (configuredPkFields.isEmpty()) {
            for (Field keyField : valueSchema.fields()) {
                keyFieldNames.add(keyField.name());
            }
        } else {
            for (String fieldName : configuredPkFields) {
                if (valueSchema.field(fieldName) == null) {
                    throw new ConnectException(String.format(
                            "PK mode for table '%s' is %s with configured PK fields %s, but record value "
                                    + "schema does not contain field: %s",
                            tableName, SinkConfig.PrimaryKeyMode.RECORD_VALUE, configuredPkFields, fieldName
                    ));
                }
            }
            keyFieldNames.addAll(configuredPkFields);
        }
        for (String fieldName : keyFieldNames) {
            final Schema fieldSchema = valueSchema.field(fieldName).schema();
            allFields.put(fieldName, new SinkRecordField(fieldSchema, fieldName, true));
        }
    }

    @Override
    public String toString() {
        return "FieldsMetadata{"
                + "keyFieldNames=" + keyFieldNames
                + ", nonKeyFieldNames=" + nonKeyFieldNames
                + ", allFields=" + allFields
                + '}';
    }
}

package com.altinity.clickhouse.debezium.embedded.parser;

import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.altinity.clickhouse.sink.connector.model.SinkRecordColumns;
import com.google.inject.Singleton;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.*;
import org.apache.kafka.connect.source.SourceRecord;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class to parse the Debezium records received from Debezium Engine.
 */
@Singleton
public class SourceRecordParserService implements DebeziumRecordParserService {

    private static final Logger log = LoggerFactory.getLogger(SourceRecordParserService.class);

    @Override
    public ClickHouseStruct parse(SourceRecord sr, DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer) {
        Struct struct = (Struct) sr.value();
        ClickHouseStruct chStruct = null;

        Field matchingDDLField = null;
        try {
            List<Field> schemaFields = struct.schema().fields();
            matchingDDLField = schemaFields.stream()
                    .filter(f -> "DDL".equalsIgnoreCase(f.name()))
                    .findAny()
                    .orElse(null);

        } catch (Exception e) {
            log.error("Error parsing schema");
        }
        if (matchingDDLField != null) {

        } else {
            Map<String, Object> sourceObjStruct = new ClickHouseConverter().convertValue(sr);

            if (sourceObjStruct == null) {
                return null;
            }
            if (sourceObjStruct.containsKey(SinkRecordColumns.OPERATION)) {
                // Operation (u, c)
                String operation = (String) sourceObjStruct.get(SinkRecordColumns.OPERATION);
                if (operation.equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.CREATE.getOperation()) ||
                        operation.equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.READ.getOperation())) {
                    // Inserts.
                    chStruct = readBeforeOrAfterSection(sourceObjStruct, sr, SinkRecordColumns.AFTER, ClickHouseConverter.CDC_OPERATION.CREATE);
                } else if (operation.equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.UPDATE.getOperation())) {
                    // Updates.
                    chStruct = readBeforeOrAfterSection(sourceObjStruct, sr, SinkRecordColumns.AFTER, ClickHouseConverter.CDC_OPERATION.UPDATE);
                } else if (operation.equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.DELETE.getOperation())) {
                    // Deletes.
                    chStruct = readBeforeOrAfterSection(sourceObjStruct, sr, SinkRecordColumns.BEFORE, ClickHouseConverter.CDC_OPERATION.DELETE);
                } else if (operation.equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
                    chStruct = readBeforeOrAfterSection(sourceObjStruct, sr, SinkRecordColumns.BEFORE, ClickHouseConverter.CDC_OPERATION.TRUNCATE);
                }
            }
        }

        return chStruct;
    }

    private ClickHouseStruct readBeforeOrAfterSection(Map<String, Object> convertedValue,
                                                      SourceRecord record, String sectionKey, ClickHouseConverter.CDC_OPERATION operation) {

        ClickHouseStruct chStruct = null;
        if (convertedValue.containsKey(sectionKey)) {
            Object beforeSection = convertedValue.get(SinkRecordColumns.BEFORE);
            Object afterSection = convertedValue.get(SinkRecordColumns.AFTER);

            Struct beforeStruct = null;
            Struct afterStruct = null;

            if(beforeSection != null && beforeSection instanceof  Struct) {
                beforeStruct = (Struct) beforeSection;
            }

            if(afterSection != null ) {
                if(afterSection instanceof Struct) {
                    afterStruct = (Struct) afterSection;
                } else if(afterSection instanceof String) {
                    JSONParser parser = new JSONParser();
                    Object obj = null;
                    try {
                        List<Field> fields = new ArrayList<Field>();
                        SchemaBuilder sb = SchemaBuilder.struct();
                        JSONObject jsonObject = (JSONObject) parser.parse((String) afterSection);
                        if(jsonObject != null) {
                            int index = 0;
                            for (Object key : jsonObject.keySet()) {
                                if(key instanceof  String) {

                                    ConnectSchema valueSchema = new ConnectSchema(ConnectSchema.schemaType(jsonObject.get(key).getClass()));
                                    if(valueSchema.type() == Schema.Type.MAP) {
                                        sb.field((String) key, Schema.STRING_SCHEMA);

                                    } else {
                                        sb.field((String) key, new ConnectSchema(ConnectSchema.schemaType(jsonObject.get(key).getClass())));
                                    }
                                }
                            }
                            Schema kafkaConnectSchema = sb.build();
                            afterStruct = new Struct(kafkaConnectSchema);

                            for (Object key : jsonObject.keySet()) {
                                if(key instanceof  String && jsonObject.containsKey(key)) {
                                    Object value = jsonObject.get(key);
                                    if(value instanceof Map) {
                                        afterStruct.put((String) key, value.toString());
                                    } else {
                                        afterStruct.put((String) key, jsonObject.get(key));
                                    }
                                }
                            }
                        }
                    } catch (ParseException e) {
                        throw new RuntimeException(e);
                    }


                }
            }
            chStruct = new ClickHouseStruct(0L,
                    record.topic(), (Struct) record.key(), 0,
                    record.timestamp(), beforeStruct, afterStruct,
                    convertedValue, operation);

        } else if (operation.getOperation().equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE.getOperation())) {
            // Truncate does not have before/after.
            chStruct = new ClickHouseStruct(0L, record.topic(), null, record.kafkaPartition(),
                    record.timestamp(), null, null, convertedValue, operation);
        }

        return chStruct;
    }
}


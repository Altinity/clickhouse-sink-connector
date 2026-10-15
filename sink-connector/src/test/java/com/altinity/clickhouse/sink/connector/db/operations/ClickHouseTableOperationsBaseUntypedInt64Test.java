package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseDataTypeMapper;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 07.01 section 3.2: in Kafka Connect mode nothing forces
 * {@code column.propagate.source.type}, so an {@code INT64} field with no
 * source type is declared {@code Int64} -- and a {@code BIGINT UNSIGNED}
 * value at or above 2^63 is then stored as the wrapped negative number. The
 * connector cannot tell the two apart; it must say so, once per table.
 */
public class ClickHouseTableOperationsBaseUntypedInt64Test {

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-untyped-int64", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }

        List<String> errors() {
            List<String> out = new ArrayList<>();
            for (LogEvent e : events) {
                if (e.getLevel() == Level.ERROR) {
                    out.add(e.getMessage().getFormattedMessage());
                }
            }
            return out;
        }
    }

    private static Field int64(String name, String sourceType) {
        SchemaBuilder b = SchemaBuilder.int64().optional();
        if (sourceType != null) {
            b.parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, sourceType);
        }
        return new Field(name, 0, b.build());
    }

    @Test
    @DisplayName("An INT64 field without a propagated source type is reported at ERROR once per table")
    public void untypedInt64LogsOneErrorPerTable() {
        Logger logger = (Logger) LogManager.getLogger(ClickHouseTableOperationsBase.class.getName());
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        try {
            ClickHouseTableOperationsBase base = new ClickHouseTableOperationsBase();
            ClickHouseSinkConnectorConfig config = new ClickHouseSinkConnectorConfig(new HashMap<>());
            Field[] untyped = new Field[]{
                    new Field("id", 0, Schema.INT32_SCHEMA),
                    int64("big_id", null),
            };

            Map<String, String> types = base.getColumnNameToCHDataTypeMapping(untyped, config, "db", "orders_untyped");
            assertEquals("Nullable(Int64)", types.get("big_id"), "without the source type Int64 is all that can be declared");
            base.getColumnNameToCHDataTypeMapping(untyped, config, "db", "orders_untyped");

            List<String> errors = appender.errors();
            assertEquals(1, errors.size(), "exactly one ERROR per table, not per call: " + errors);
            assertTrue(errors.get(0).contains("big_id"), errors.get(0));
            assertTrue(errors.get(0).contains("orders_untyped"), errors.get(0));
            assertTrue(errors.get(0).contains("column.propagate.source.type"), errors.get(0));

            // A field that carries the source type is not ambiguous: no report.
            Field[] typed = new Field[]{
                    new Field("big_id", 0, SchemaBuilder.int64()
                            .parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, "BIGINT UNSIGNED").build()),
                    new Field("signed_id", 1, SchemaBuilder.int64()
                            .parameter(ClickHouseDataTypeMapper.DEBEZIUM_SOURCE_COLUMN_TYPE_PARAM, "BIGINT").build()),
            };
            Map<String, String> typedTypes = base.getColumnNameToCHDataTypeMapping(typed, config, "db", "orders_typed");
            assertEquals("UInt64", typedTypes.get("big_id"));
            assertEquals("Int64", typedTypes.get("signed_id"));
            assertEquals(1, appender.errors().size(), "a typed table must not be reported: " + appender.errors());

            // A different untyped table is reported on its own.
            base.getColumnNameToCHDataTypeMapping(untyped, config, "db", "other_untyped");
            assertEquals(2, appender.errors().size(), appender.errors().toString());
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }
}

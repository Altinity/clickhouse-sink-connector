package com.altinity.clickhouse.sink.connector.converters;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Spec 10.04 section 3.9: when the schema of a record cannot be read, the
 * PostgreSQL schema-drift check skips the record, so the failure is logged at
 * WARN with its stack trace. At DEBUG (the previous level) drift detection
 * could stop for a table with nothing visible in a production log.
 */
public class ClickHouseConverterSchemaExtractionTest {

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-schema-extraction", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /** A record whose value schema cannot be read. */
    private static SourceRecord unreadableRecord() {
        return new SourceRecord(null, null, "pg.public.orders", null, null) {
            @Override
            public Schema valueSchema() {
                throw new IllegalStateException("corrupt envelope");
            }
        };
    }

    @Test
    @DisplayName("10.04 s3.9: an unreadable record schema returns null and is logged at WARN with its cause")
    public void unreadableSchemaIsLoggedAtWarn() {
        Logger coreLogger = (Logger) LogManager.getLogger(ClickHouseConverter.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        Object result;
        try {
            result = ClickHouseConverter.extractDebeziumSchema(unreadableRecord());
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }

        assertNull(result);
        List<LogEvent> warnings = new ArrayList<>();
        for (LogEvent e : appender.events) {
            if (e.getLevel().isMoreSpecificThan(Level.WARN)) {
                warnings.add(e);
            }
        }
        assertEquals(1, warnings.size(), "one WARN, carrying the cause: " + appender.events);
        assertNotNull(warnings.get(0).getThrown(), "the stack trace must be logged");
        assertEquals("corrupt envelope", warnings.get(0).getThrown().getMessage());
    }
}

package com.altinity.clickhouse.debezium.embedded.ddl.parser;

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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec 06.09 section 3.7: a PostgreSQL table-constraint clause has no ClickHouse
 * translation, but it is never dropped without a trace. A clause that can change
 * the table's identity (ADD ... PRIMARY KEY, DROP CONSTRAINT) is reported at WARN
 * with the manual remedy. Before the fix both fell through the ADD/DROP COLUMN
 * branches with no output and no log line at any level.
 */
class PostgreSQLConstraintClauseReportTest {

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture-pg-constraint", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /** Translates {@code sql} and returns what it produced plus everything the listener logged. */
    private static List<LogEvent> translate(String sql, StringBuffer out) {
        Logger coreLogger = (Logger) LogManager.getLogger(PostgreSQLDDLParserListenerImpl.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        coreLogger.addAppender(appender);
        try {
            new PostgreSQLDDLParserService(null, null, "db").parseSql(sql, null, out);
        } finally {
            coreLogger.removeAppender(appender);
            appender.stop();
        }
        return new ArrayList<>(appender.events);
    }

    private static List<String> warnings(List<LogEvent> events) {
        return events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN))
                .map(e -> e.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("06.09 s3.7: ADD CONSTRAINT ... PRIMARY KEY is reported at WARN with the manual remedy")
    void addPrimaryKeyIsReportedNotSwallowed() {
        StringBuffer out = new StringBuffer();
        List<String> warnings = warnings(translate(
                "ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (order_id)", out));

        assertEquals("", out.toString(), "nothing is translated for a key change");
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("PRIMARY KEY (order_id)"), warnings.get(0));
        assertTrue(warnings.get(0).contains("re-snapshot"), warnings.get(0));
    }

    // DESTRUCTIVE: statement text is only parsed and asserted on here; nothing is executed against any database.
    @Test
    @DisplayName("06.09 s3.7: DROP CONSTRAINT is reported at WARN and is not read as DROP COLUMN")
    void dropConstraintIsReportedAndNotTranslatedAsDropColumn() {
        StringBuffer out = new StringBuffer();
        List<String> warnings = warnings(translate("ALTER TABLE orders DROP CONSTRAINT orders_pkey", out));
        // DESTRUCTIVE: assertion text only; the translator must emit nothing for this clause.
        assertEquals("", out.toString(), "a constraint name must never become DROP COLUMN");
        assertEquals(1, warnings.size(), String.valueOf(warnings));
        assertTrue(warnings.get(0).contains("DROP CONSTRAINT orders_pkey"), warnings.get(0));
    }

    @Test
    @DisplayName("06.09 s3.7: a non-key constraint is logged at INFO, not WARN; ADD COLUMN is unchanged")
    void nonKeyConstraintIsInfoAndAddColumnStillTranslates() {
        StringBuffer out = new StringBuffer();
        List<LogEvent> events = translate("ALTER TABLE orders ADD CONSTRAINT qty_positive CHECK (qty > 0)", out);
        assertEquals("", out.toString());
        assertTrue(warnings(events).isEmpty(), String.valueOf(warnings(events)));
        assertTrue(events.stream().anyMatch(e -> e.getLevel() == Level.INFO
                && e.getMessage().getFormattedMessage().contains("qty_positive")), "the clause is logged");

        StringBuffer added = new StringBuffer();
        translate("ALTER TABLE orders ADD COLUMN note text", added);
        assertTrue(added.toString().contains("ADD COLUMN IF NOT EXISTS `note`"), added.toString());
    }
}

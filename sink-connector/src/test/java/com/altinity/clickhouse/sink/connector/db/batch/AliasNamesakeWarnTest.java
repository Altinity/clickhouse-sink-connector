package com.altinity.clickhouse.sink.connector.db.batch;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A source column that ClickHouse defines as ALIAS is never stored, so the source's value for it is
 * not replicated. warnAliasNamesake WARNs once per table and column per process, names the ALIAS expression, and
 * says the value is not replicated (spec 08.04 section 3.1). A helper that writes on every call fails the
 * once-per-key assertions.
 */
class AliasNamesakeWarnTest {

    private static final String LOGGER_NAME = GroupInsertQueryWithBatchRecords.class.getName();

    private final CapturingAppender appender = new CapturingAppender();
    private Level previousLevel;

    @BeforeEach
    void attach() {
        Logger core = (Logger) LogManager.getLogger(GroupInsertQueryWithBatchRecords.class);
        previousLevel = core.getLevel();
        Configurator.setLevel(LOGGER_NAME, Level.INFO);
        appender.start();
        core.addAppender(appender);
    }

    @AfterEach
    void detach() {
        Logger core = (Logger) LogManager.getLogger(GroupInsertQueryWithBatchRecords.class);
        core.removeAppender(appender);
        appender.stop();
        Configurator.setLevel(LOGGER_NAME, previousLevel);
    }

    @Test
    void firstNamesakeWritesOneWarnThatNamesTheExpressionAndTheNonReplication() {
        String table = freshTable();
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", "c1 + 1000"));
        List<String> warns = warnsFor(table, "c2");
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("c1 + 1000"), warns.get(0));
        assertTrue(warns.get(0).contains("NOT replicated"), warns.get(0));
    }

    @Test
    void repeatedNamesakeOfTheSameColumnWritesNoSecondWarn() {
        String table = freshTable();
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", "c1 + 1000"));
        assertFalse(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", "c1 + 1000"));
        assertFalse(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", "c1 + 1000"));
        assertEquals(1, warnsFor(table, "c2").size());
    }

    @Test
    void secondAliasColumnOfTheSameTableIsReportedSeparately() {
        String table = freshTable();
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", "c1 + 1000"));
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c3", "c1 + 2000"));
        assertEquals(1, warnsFor(table, "c2").size());
        assertEquals(1, warnsFor(table, "c3").size());
    }

    @Test
    void sameColumnNameOfAnotherTableIsReportedSeparately() {
        String table = freshTable();
        String other = freshTable();
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", "c1 + 1000"));
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(other, "c2", "c1 + 1000"));
        assertEquals(1, warnsFor(other, "c2").size());
    }

    @Test
    void unknownExpressionIsSaidSoAndStillWarned() {
        String table = freshTable();
        assertTrue(GroupInsertQueryWithBatchRecords.warnAliasNamesake(table, "c2", null));
        List<String> warns = warnsFor(table, "c2");
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("expression unknown"), warns.get(0));
    }

    private List<String> warnsFor(String fullyQualifiedTable, String column) {
        List<String> out = new ArrayList<>();
        for (LogEvent event : appender.events) {
            String text = event.getMessage().getFormattedMessage();
            if (event.getLevel() == Level.WARN && text.contains(fullyQualifiedTable)
                    && text.contains("'" + column + "'")) {
                out.add(text);
            }
        }
        return out;
    }

    private static String freshTable() {
        return "alias_namesake_" + UUID.randomUUID().toString().replace("-", "") + ".t";
    }

    private static final class CapturingAppender extends AbstractAppender {
        final List<LogEvent> events = new CopyOnWriteArrayList<>();

        CapturingAppender() {
            super("alias-namesake-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}

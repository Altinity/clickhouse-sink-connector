package com.altinity.clickhouse.debezium.embedded.ddl;

import io.debezium.relational.RelationalDatabaseConnectorConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Decides whether a DDL's table is inside the connector's capture filters
 * ({@code database.include.list} / {@code database.exclude.list} /
 * {@code table.include.list} / {@code table.exclude.list}), with Debezium's
 * matching semantics (spec 06.08 §3.3).
 *
 * <p><b>Why the sink filters at all.</b> Debezium emits a schema-change event
 * for every table of the captured databases unless
 * {@code schema.history.internal.store.only.captured.tables.ddl=true}, and the
 * sink applied every one of them: a {@code CREATE TABLE} for a table outside
 * {@code table.include.list} created a spurious table in ClickHouse, and an
 * {@code ALTER TABLE} on such a table failed on the missing target
 * ({@code Code: 60}) — terminal (spec 06.08 §3.1), so the whole pipeline
 * halted for a table nobody asked to replicate. Rows of such a table never
 * reach the sink, so its DDL must not either.</p>
 *
 * <p><b>Semantics (Debezium's).</b> Entries are comma-separated regular
 * expressions matched in full and case-insensitively against {@code db.table}
 * (table lists) or {@code db} (database lists); when an include list is set
 * the corresponding exclude list is ignored. Decisions are conservative in the
 * direction of KEEPING a DDL: an unknown database is never filtered, a
 * table-less DDL is judged by the database lists only, and an uncompilable
 * exclude pattern is ignored rather than treated as a match.</p>
 *
 * <p><b>Property names come from Debezium.</b> The four list keys are read
 * through {@link RelationalDatabaseConnectorConfig}'s field definitions, never
 * restated as literals, so a rename on the Debezium side is picked up by the
 * dependency bump instead of silently turning the filter into a no-op.</p>
 */
public final class DdlCaptureFilter {

    private static final Logger log = LogManager.getLogger(DdlCaptureFilter.class);

    /** {@code database.include.list}, as Debezium defines it. */
    public static final String DATABASE_INCLUDE_LIST =
            RelationalDatabaseConnectorConfig.DATABASE_INCLUDE_LIST.name();
    /** {@code database.exclude.list}, as Debezium defines it. */
    public static final String DATABASE_EXCLUDE_LIST =
            RelationalDatabaseConnectorConfig.DATABASE_EXCLUDE_LIST.name();
    /** {@code table.include.list}, as Debezium defines it. */
    public static final String TABLE_INCLUDE_LIST =
            RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST.name();
    /** {@code table.exclude.list}, as Debezium defines it. */
    public static final String TABLE_EXCLUDE_LIST =
            RelationalDatabaseConnectorConfig.TABLE_EXCLUDE_LIST.name();

    private DdlCaptureFilter() {
    }

    /**
     * @param database the SOURCE database of the DDL; {@code null} when unknown.
     * @param table    the SOURCE table of the DDL; {@code null} for a table-less
     *                 statement (e.g. {@code CREATE DATABASE}).
     * @param props    the connector properties.
     * @return true when the DDL belongs to a captured table (or, for a
     *         table-less DDL, a captured database).
     */
    public static boolean isCaptured(String database, String table, Properties props) {
        if (database == null || database.isEmpty()) {
            return true;
        }
        if (!passes(database, props.getProperty(DATABASE_INCLUDE_LIST),
                props.getProperty(DATABASE_EXCLUDE_LIST))) {
            return false;
        }
        if (table == null || table.isEmpty()) {
            return true;
        }
        return passes(database + "." + table, props.getProperty(TABLE_INCLUDE_LIST),
                props.getProperty(TABLE_EXCLUDE_LIST));
    }

    private static boolean passes(String identifier, String includeCsv, String excludeCsv) {
        List<Pattern> includes = compile(includeCsv);
        if (!includes.isEmpty() || (includeCsv != null && !includeCsv.trim().isEmpty())) {
            // Debezium: an include list, when set, is the whole rule. A list
            // whose every entry failed to compile matches nothing.
            return matchesAny(includes, identifier);
        }
        return !matchesAny(compile(excludeCsv), identifier);
    }

    private static List<Pattern> compile(String csv) {
        List<Pattern> patterns = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return patterns;
        }
        for (String raw : csv.split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                patterns.add(Pattern.compile(pattern, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.warn("Ignoring unparseable capture-list pattern '{}' when deciding whether a "
                        + "DDL is replicated: {}", pattern, e.getMessage());
            }
        }
        return patterns;
    }

    private static boolean matchesAny(List<Pattern> patterns, String identifier) {
        for (Pattern p : patterns) {
            if (p.matcher(identifier).matches()) {
                return true;
            }
        }
        return false;
    }
}

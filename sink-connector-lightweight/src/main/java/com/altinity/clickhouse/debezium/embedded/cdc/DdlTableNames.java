package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the tables a DDL/schema-change event affects (Spec 08.02 section 3.4):
 * the names in the event's {@code tableChanges} (falling back to
 * {@code source.table}) plus BOTH sides of every rename in the statement text.
 * The caller discards the cached writer of every returned name.
 *
 * <p>Stateless and side-effect free; extracted from
 * {@link DebeziumChangeEventCapture} so it is owned and tested on its own.</p>
 */
final class DdlTableNames {

    /** A possibly-quoted, possibly database-qualified table identifier. */
    private static final String QUALIFIED_NAME =
            "[`\"]?[A-Za-z0-9_$]+[`\"]?(?:\\.[`\"]?[A-Za-z0-9_$]+[`\"]?)?";

    /**
     * Matches {@code RENAME TABLE a TO b, c TO d}, capturing one source and
     * destination pair per match.
     * <p>
     * The source is anchored on a word boundary and must not be a bare SQL
     * keyword, so this pattern cannot mis-fire on the
     * {@code ALTER TABLE ... RENAME TO} form -- where the token preceding
     * {@code TO} is the keyword rather than a table name -- nor match a
     * suffix of one. That form is handled by {@link #ALTER_RENAME} instead.
     */
    private static final Pattern RENAME_PAIR = Pattern.compile(
            "\\b(?!(?:RENAME|TABLE|ALTER)\\b)(" + QUALIFIED_NAME + ")\\s+TO\\s+("
                    + QUALIFIED_NAME + ")",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches {@code ALTER TABLE a RENAME [TO|AS] b}, capturing the source table
     * (which precedes the {@code RENAME} keyword) and the destination.
     */
    private static final Pattern ALTER_RENAME = Pattern.compile(
            "ALTER\\s+TABLE\\s+(" + QUALIFIED_NAME + ")\\s+RENAME\\s+(?:TO\\s+|AS\\s+)?("
                    + QUALIFIED_NAME + ")",
            Pattern.CASE_INSENSITIVE);

    private DdlTableNames() {
    }

    /**
     * The distinct table names affected by a DDL: those the record names, then
     * every rename participant in {@code ddl} not already listed.
     * <p>
     * For a {@code RENAME TABLE a TO b} the {@code tableChanges} entry identifies
     * the table by its NEW name, so resolving from {@code tableChanges} alone
     * would leave the OLD name's cached writer inserting into a table that no
     * longer exists.
     *
     * @param sr  the source record (may be null)
     * @param ddl the raw DDL statement, used to recover rename sources
     * @return the affected table names, or an empty list
     */
    static List<String> affected(SourceRecord sr, String ddl) {
        List<String> tables = fromRecord(sr);
        for (String renamed : renamed(ddl)) {
            if (!tables.contains(renamed)) {
                tables.add(renamed);
            }
        }
        return tables;
    }

    /**
     * Every table name participating in a rename, from either
     * {@code RENAME TABLE a TO b, c TO d} or {@code ALTER TABLE a RENAME TO b}.
     * Both sides are returned: the source's cached writer points at a table that
     * is gone, and a writer may already be cached for a previous table carrying
     * the destination name.
     *
     * @param ddl the raw DDL statement; may be null
     * @return the bare table names involved in a rename, or an empty list
     */
    static List<String> renamed(String ddl) {
        List<String> names = new ArrayList<>();
        if (ddl == null || ddl.isEmpty()) {
            return names;
        }
        // ALTER TABLE a RENAME TO b -- the source precedes the RENAME keyword.
        Matcher alterMatcher = ALTER_RENAME.matcher(ddl);
        while (alterMatcher.find()) {
            collectNames(alterMatcher, names);
        }
        // RENAME TABLE a TO b, c TO d -- one match per pair.
        Matcher renameMatcher = RENAME_PAIR.matcher(ddl);
        while (renameMatcher.find()) {
            collectNames(renameMatcher, names);
        }
        return names;
    }

    /** Adds the bare names from both capture groups of a rename match, skipping blanks and duplicates. */
    private static void collectNames(Matcher matcher, List<String> names) {
        for (int group = 1; group <= 2; group++) {
            String name = bareName(matcher.group(group));
            if (name != null && !name.isEmpty() && !names.contains(name)) {
                names.add(name);
            }
        }
    }

    /**
     * The tables the event value names: each {@code tableChanges[].id} (a fully
     * qualified name such as {@code "employees"."race_test"}), else
     * {@code source.table}. The record key carries only {@code databaseName}.
     */
    static List<String> fromRecord(SourceRecord sr) {
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        if (sr != null && sr.value() instanceof Struct) {
            Struct value = (Struct) sr.value();
            try {
                List<Object> tableChanges = value.getArray("tableChanges");
                if (tableChanges != null) {
                    for (Object change : tableChanges) {
                        if (change instanceof Struct) {
                            String t = bareName((String) ((Struct) change).get("id"));
                            if (t != null) {
                                tables.add(t);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // tableChanges field may not exist; fall back to source.table below
            }
            if (tables.isEmpty()) {
                try {
                    Struct source = (Struct) value.get("source");
                    String t = source != null ? (String) source.get("table") : null;
                    if (t != null && !t.isEmpty()) {
                        tables.add(t);
                    }
                } catch (Exception ignored) {
                    // source.table may not exist
                }
            }
        }
        return new ArrayList<>(tables);
    }

    /**
     * The bare table name of a fully qualified id such as
     * {@code "employees"."race_test"} or {@code `employees`.`race_test`}.
     *
     * @return the bare name, or null if the id is empty
     */
    static String bareName(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String cleaned = id.replace("\"", "").replace("`", "");
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }
}

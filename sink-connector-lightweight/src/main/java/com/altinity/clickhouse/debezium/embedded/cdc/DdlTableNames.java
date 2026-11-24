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
     * A table named by a DDL: its table name and, when the event or the
     * statement qualified it, its database. Capture filtering matches
     * {@code database.table} against {@code table.include.list} /
     * {@code table.exclude.list}, so the qualifier must survive until then;
     * {@link #bareName} discards it.
     */
    static final class QualifiedName {
        /** The explicit database qualifier, or null when none was given. */
        final String database;
        /** The table name, unquoted. */
        final String table;

        QualifiedName(String database, String table) {
            this.database = database;
            this.table = table;
        }

        /** The database to match this table in: its own qualifier, else {@code fallbackDatabase}. */
        String databaseOr(String fallbackDatabase) {
            return database != null && !database.isEmpty() ? database : fallbackDatabase;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof QualifiedName)) {
                return false;
            }
            QualifiedName other = (QualifiedName) o;
            return java.util.Objects.equals(database, other.database) && table.equals(other.table);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(database, table);
        }

        @Override
        public String toString() {
            return database == null ? table : database + "." + table;
        }
    }

    /** One {@code source TO target} pair of a {@code RENAME TABLE} statement, as written and as parsed. */
    static final class RenamePair {
        /** The pair's text exactly as written, e.g. {@code `a` TO db2.b}. */
        final String text;
        final QualifiedName source;
        final QualifiedName target;

        RenamePair(String text, QualifiedName source, QualifiedName target) {
            this.text = text;
            this.source = source;
            this.target = target;
        }
    }

    /** One identifier part: backticked, double-quoted, or a bare word. */
    private static final String ID_PART = "(?:`(?:[^`]|``)+`|\"(?:[^\"]|\"\")+\"|[^\\s`\".,;]+)";

    private static final Pattern RENAME_STATEMENT = Pattern.compile(
            "^\\s*RENAME\\s+TABLE\\s+(.*?)\\s*;?\\s*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern RENAME_PAIR_TEXT = Pattern.compile(
            "^\\s*(" + ID_PART + "(?:\\." + ID_PART + ")?)\\s+TO\\s+(" + ID_PART + "(?:\\." + ID_PART + ")?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * Splits a table reference on the dots outside quotes and removes the
     * quotes of each part: {@code `my.db`.`t.1`} is database {@code my.db},
     * table {@code t.1}; {@code t} has no database.
     */
    static QualifiedName qualifiedName(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (quote != 0) {
                if (c == quote && i + 1 < id.length() && id.charAt(i + 1) == quote) {
                    current.append(c);
                    i++;
                } else if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '`' || c == '"') {
                quote = c;
            } else if (c == '.') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        String table = parts.get(parts.size() - 1);
        if (table.isEmpty()) {
            return null;
        }
        String database = parts.size() >= 2 ? parts.get(parts.size() - 2) : null;
        return new QualifiedName(database == null || database.isEmpty() ? null : database, table);
    }

    /**
     * The pairs of a {@code RENAME TABLE a TO b, c TO d} statement.
     *
     * @return the pairs in statement order, or null when the text is not a
     *         RENAME TABLE whose every part parses as {@code ref TO ref} (the
     *         caller then leaves the statement as it is)
     */
    static List<RenamePair> renamePairs(String ddl) {
        if (ddl == null) {
            return null;
        }
        Matcher statement = RENAME_STATEMENT.matcher(ddl);
        if (!statement.matches()) {
            return null;
        }
        String list = statement.group(1);
        List<RenamePair> pairs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i <= list.length(); i++) {
            char c = i < list.length() ? list.charAt(i) : ',';
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '`' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                Matcher pair = RENAME_PAIR_TEXT.matcher(current);
                if (!pair.matches()) {
                    return null;
                }
                QualifiedName source = qualifiedName(pair.group(1));
                QualifiedName target = qualifiedName(pair.group(2));
                if (source == null || target == null) {
                    return null;
                }
                pairs.add(new RenamePair(current.toString().trim(), source, target));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return pairs;
    }

    /**
     * Like {@link #affected} but keeping each table's database qualifier: the
     * database of a {@code tableChanges} id, and of a rename side written as
     * {@code db.table}.
     *
     * @param keepQualifiers false for a source whose two-part names are not
     *                       {@code database.table} (PostgreSQL's
     *                       {@code schema.table}): every database is then null
     */
    static List<QualifiedName> affectedQualified(SourceRecord sr, String ddl, boolean keepQualifiers) {
        LinkedHashSet<QualifiedName> tables = new LinkedHashSet<>();
        for (String id : idsFromRecord(sr)) {
            QualifiedName name = qualifiedName(id);
            if (name != null) {
                tables.add(keepQualifiers ? name : new QualifiedName(null, name.table));
            }
        }
        if (tables.isEmpty()) {
            for (String table : fromRecord(sr)) {
                tables.add(new QualifiedName(null, table));
            }
        }
        for (String side : renamedRaw(ddl)) {
            QualifiedName name = qualifiedName(side);
            if (name != null) {
                tables.add(keepQualifiers ? name : new QualifiedName(null, name.table));
            }
        }
        return new ArrayList<>(tables);
    }

    /** The raw {@code tableChanges[].id} values of the event, in order. */
    private static List<String> idsFromRecord(SourceRecord sr) {
        List<String> ids = new ArrayList<>();
        if (sr != null && sr.value() instanceof Struct) {
            try {
                List<Object> tableChanges = ((Struct) sr.value()).getArray("tableChanges");
                if (tableChanges != null) {
                    for (Object change : tableChanges) {
                        if (change instanceof Struct) {
                            Object id = ((Struct) change).get("id");
                            if (id instanceof String && !((String) id).isEmpty()) {
                                ids.add((String) id);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // tableChanges field may not exist
            }
        }
        return ids;
    }

    /** Every rename side of the statement as written (qualified or not). */
    private static List<String> renamedRaw(String ddl) {
        List<String> sides = new ArrayList<>();
        if (ddl == null || ddl.isEmpty()) {
            return sides;
        }
        Matcher alterMatcher = ALTER_RENAME.matcher(ddl);
        while (alterMatcher.find()) {
            sides.add(alterMatcher.group(1));
            sides.add(alterMatcher.group(2));
        }
        Matcher renameMatcher = RENAME_PAIR.matcher(ddl);
        while (renameMatcher.find()) {
            sides.add(renameMatcher.group(1));
            sides.add(renameMatcher.group(2));
        }
        return sides;
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

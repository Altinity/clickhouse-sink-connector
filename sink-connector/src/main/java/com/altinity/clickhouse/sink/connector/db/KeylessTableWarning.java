package com.altinity.clickhouse.sink.connector.db;

import java.util.List;

/**
 * The banner printed for a source table with no PRIMARY KEY and no non-null
 * UNIQUE key.
 *
 * <p>Such a table is bad practice and a correctness hazard, not a style
 * preference, so the warning is deliberately loud: a boxed multi-line block
 * that cannot be mistaken for routine log noise, naming the offending table
 * and carrying the exact {@code ALTER TABLE} that fixes it. It is emitted at
 * ERROR level and repeated -- at startup, at table creation, and periodically
 * while replication runs -- because a single line at INFO scrolls past and the
 * table stays broken for months.</p>
 *
 * <p>What is actually wrong: the table has no row identity in the binary log.
 * InnoDB does give it an internal 6-byte {@code DB_ROW_ID} inside
 * {@code GEN_CLUST_INDEX}, but that is not a column, is never binlogged, and
 * is assigned from a server-local counter, so it differs between source and
 * replica. Verified on MySQL 8.0.36: {@code SELECT DB_ROW_ID} fails with
 * {@code ERROR 1054 Unknown column}, and {@code innodb_columns} lists only the
 * declared columns. Under row-based replication there is nothing to match a
 * row on, so ReplacingMergeTree collapses rows that MySQL considers distinct
 * and the ClickHouse copy silently disagrees with its source.</p>
 *
 * <p>The fix is MySQL's generated invisible primary key (8.0.30+), which is a
 * real column and is therefore carried by the binlogged DDL and by every row
 * image.</p>
 */
public final class KeylessTableWarning {

    /** Width of the banner rule, wide enough to stand out in a log. */
    private static final String RULE =
            "========================================================================";

    private KeylessTableWarning() {
    }

    /**
     * The banner for one offending table.
     *
     * @param database the source database.
     * @param table the source table.
     * @return a multi-line block suitable for logging at ERROR level.
     */
    public static String banner(String database, String table) {
        return "\n" + RULE + "\n"
                + "  !!  KEYLESS TABLE -- BAD PRACTICE, FIX THIS AS SOON AS POSSIBLE  !!\n"
                + RULE + "\n"
                + "  Table : " + database + "." + table + "\n"
                + "  Problem:\n"
                + "    It declares NO PRIMARY KEY and NO non-null UNIQUE key, so it has\n"
                + "    NO ROW IDENTITY IN THE BINLOG. InnoDB's internal DB_ROW_ID is not a\n"
                + "    column, is never binlogged, and is assigned per server -- nothing\n"
                + "    downstream can recover it.\n"
                + "  Consequence:\n"
                + "    Rows that MySQL considers distinct collapse into one in ClickHouse,\n"
                + "    and a row modification cannot be matched to one row. The copy will\n"
                + "    silently disagree with its source and checksum jobs will report a\n"
                + "    mismatch long after the data is already wrong.\n"
                + "  FIX (run at the source, in a maintenance window -- MySQL rewrites the\n"
                + "  table):\n"
                + "    ALTER TABLE " + database + "." + table + "\n"
                + "      ADD COLUMN my_row_id BIGINT UNSIGNED NOT NULL\n"
                + "      AUTO_INCREMENT INVISIBLE PRIMARY KEY;\n"
                + "  And so future tables get one automatically (MySQL 8.0.30+):\n"
                + "    SET GLOBAL sql_generate_invisible_primary_key = ON;\n"
                + "    # plus sql_generate_invisible_primary_key=ON in my.cnf\n"
                + RULE;
    }

    /**
     * The banner for a set of offending tables, used at startup where the whole
     * source has just been scanned.
     *
     * @param tables fully-qualified table names.
     * @param gipkEnabled whether GIPK is now on, which changes the advice: when
     *                    it is on, these tables predate it and only they need
     *                    fixing; when it is off, new tables are still at risk.
     * @return a multi-line block suitable for logging at ERROR level.
     */
    public static String banner(List<String> tables, boolean gipkEnabled) {
        StringBuilder sb = new StringBuilder("\n").append(RULE).append("\n")
                .append("  !!  ").append(tables.size())
                .append(" KEYLESS TABLE(S) -- BAD PRACTICE, FIX AS SOON AS POSSIBLE  !!\n")
                .append(RULE).append("\n")
                .append("  These tables declare NO PRIMARY KEY and NO non-null UNIQUE key:\n");
        for (String t : tables) {
            sb.append("    - ").append(t).append("\n");
        }
        sb.append("  Problem:\n")
                .append("    They have NO ROW IDENTITY IN THE BINLOG. InnoDB's internal DB_ROW_ID\n")
                .append("    is not a column, is never binlogged, and is assigned per server, so\n")
                .append("    nothing downstream can recover it.\n")
                .append("  Consequence:\n")
                .append("    Rows MySQL considers distinct collapse into one in ClickHouse, and\n")
                .append("    a row modification cannot be matched to one row. Replication of\n")
                .append("    these tables cannot be made correct.\n")
                .append("  FIX each one (MySQL rewrites the table -- use a maintenance window):\n")
                .append("    ALTER TABLE <table>\n")
                .append("      ADD COLUMN my_row_id BIGINT UNSIGNED NOT NULL\n")
                .append("      AUTO_INCREMENT INVISIBLE PRIMARY KEY;\n");
        if (gipkEnabled) {
            sb.append("  sql_generate_invisible_primary_key is ON, so tables created from now\n")
                    .append("  on get a key automatically. It is NOT retroactive: the tables above\n")
                    .append("  predate it and must each be altered by hand.\n");
        } else {
            sb.append("  Also turn on generated invisible primary keys so this stops recurring\n")
                    .append("  (MySQL 8.0.30+):\n")
                    .append("    SET GLOBAL sql_generate_invisible_primary_key = ON;\n")
                    .append("    # plus sql_generate_invisible_primary_key=ON in my.cnf\n");
        }
        return sb.append(RULE).toString();
    }
}

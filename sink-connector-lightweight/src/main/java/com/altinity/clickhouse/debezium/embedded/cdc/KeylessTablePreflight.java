package com.altinity.clickhouse.debezium.embedded.cdc;

import com.altinity.clickhouse.sink.connector.db.KeylessTableWarning;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Refuses to start when the source holds a table whose rows cannot be
 * identified, reading the source and never writing to it.
 *
 * <p>A table with no PRIMARY KEY and no non-null UNIQUE key has no row
 * identity in the logical schema. InnoDB does give such a table an internal
 * 6-byte {@code DB_ROW_ID} inside its {@code GEN_CLUST_INDEX}, but that value
 * is NOT a column, is NOT written to the binlog, and is assigned from a
 * server-local counter, so it differs between source and replica. Verified on
 * MySQL 8.0.36: {@code SELECT DB_ROW_ID} fails with
 * {@code ERROR 1054 Unknown column}, and {@code information_schema.innodb_columns}
 * lists only the declared columns for such a table while
 * {@code innodb_indexes} shows the {@code GEN_CLUST_INDEX}. Nothing downstream
 * can recover it.</p>
 *
 * <p>That leaves only one honest source of identity: MySQL's <b>generated
 * invisible primary key</b> (GIPK, MySQL 8.0.30+). With
 * {@code sql_generate_invisible_primary_key=ON} a keyless InnoDB table is
 * created with a real
 * {@code my_row_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT INVISIBLE PRIMARY KEY}
 * column. Being part of the table definition, it is carried by the binlogged
 * DDL and by every row image, so the connector sees an ordinary keyed table and
 * needs no invented identity at all. Verified on 8.0.36: the binlog DDL carries
 * {@code `my_row_id` bigint unsigned NOT NULL AUTO_INCREMENT /*!80023 INVISIBLE *&#47;},
 * {@code information_schema} reports it {@code PRI}, and it is selectable.</p>
 *
 * <p>Deriving an identity downstream instead -- hashing the row's values into a
 * generated ClickHouse column -- was tried and rejected. It cannot survive the
 * schema changing: a column the identity depends on cannot be dropped, and a
 * column added later is absent from it, so two rows differing only in the new
 * column collapse into one. An identity has to come from the source of truth.</p>
 *
 * <p>So this check, run once at startup:</p>
 * <ol>
 *   <li>lists the keyless tables that ALREADY exist and refuses to start,
 *       naming them and the single {@code ALTER TABLE} that fixes each one;</li>
 *   <li>refuses outright below MySQL 8.0.30, where GIPK does not exist and no
 *       correct replication of a keyless table is possible;</li>
 *   <li>reports whether {@code sql_generate_invisible_primary_key} is ON, so
 *       the operator knows whether a keyless table created tomorrow will get
 *       an identity -- and prints the statements to turn it on if not.</li>
 * </ol>
 *
 * <p><b>It does not turn GIPK on itself.</b> The connector is a replication
 * CONSUMER and must not change the server it reads from. That is not a style
 * preference here: an earlier version of this class ran
 * {@code SET GLOBAL sql_generate_invisible_primary_key = ON}, after which
 * MySQL rejected every keyless {@code CREATE TABLE ... PARTITION BY} on the
 * whole server (ERROR 1235) -- an outage on the source, caused by its own
 * consumer, outliving the connector that caused it. Enabling GIPK affects
 * every application on that server, so it is the operator's call, made with
 * knowledge of what else uses it.</p>
 *
 * <p>Read-only is enforced, not merely intended: the connection is opened
 * {@link java.sql.Connection#setReadOnly(boolean) read-only} so the SERVER
 * rejects a write, every statement passes {@link #assertReadOnlySql}, and a
 * test fails the build if a mutating keyword appears in this file.</p>
 *
 * <p>Refusing is the point. Replicating a keyless table silently produces a
 * ClickHouse table that disagrees with its source, and a checksum job reports
 * it as a mismatch long after the data is wrong.</p>
 */
public class KeylessTablePreflight {

    private static final Logger log = LogManager.getLogger(KeylessTablePreflight.class);

    /** MySQL release that introduced the generated invisible primary key. */
    static final int GIPK_MAJOR = 8;
    static final int GIPK_MINOR = 0;
    static final int GIPK_PATCH = 30;

    /** Escape hatch, for a source the operator knows is safe. */
    static final String SKIP_PROPERTY = "keyless.table.check.skip";

    /** Matches the rule used by {@link KeylessTableWarning} so banners align. */
    private static final String BANNER_RULE =
            "========================================================================";

    /**
     * A literal MySQL schema name: anything else in {@code database.include.list}
     * is a Debezium regex and cannot be compared with SQL {@code IN}.
     */
    private static final java.util.regex.Pattern LITERAL_SCHEMA_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_$]+");

    private KeylessTablePreflight() {
    }

    /** A source the connector refuses to replicate, with the reason. */
    public static class UnsupportedSourceException extends RuntimeException {
        public UnsupportedSourceException(String message) {
            super(message);
        }
    }

    /**
     * Runs the check against the configured MySQL source.
     *
     * <p>Only MySQL is checked; other connectors pass through untouched. A
     * connection or permission failure is logged and allowed through -- this
     * check must never be the reason a healthy pipeline cannot start -- but a
     * source that is genuinely unsafe throws.</p>
     *
     * @param props the connector properties.
     * @throws UnsupportedSourceException when the source cannot be replicated correctly.
     */
    public static void check(Properties props) {
        if (Boolean.parseBoolean(props.getProperty(SKIP_PROPERTY, "false"))) {
            // Loud on purpose: the override silences a correctness check, so it
            // must not itself be quiet.
            log.error("\n{}\n  !!  {}=true -- KEYLESS-TABLE CHECK DISABLED  !!\n{}\n"
                            + "  A source table with no PRIMARY KEY and no non-null UNIQUE key has NO\n"
                            + "  ROW IDENTITY IN THE BINLOG. Any such table WILL silently diverge from\n"
                            + "  MySQL: rows collapse, and UPDATE/DELETE cannot be matched to one row.\n"
                            + "  This override accepts that risk. Remove it once every table has a key.\n{}",
                    BANNER_RULE, SKIP_PROPERTY, BANNER_RULE, BANNER_RULE);
            return;
        }
        String connector = props.getProperty("connector.class", "");
        if (!connector.toLowerCase().contains("mysql")) {
            return;
        }

        String host = props.getProperty("database.hostname");
        String port = props.getProperty("database.port", "3306");
        String user = props.getProperty("database.user");
        String password = props.getProperty("database.password");
        if (host == null || user == null) {
            log.warn("Keyless-table check skipped: no MySQL host/user in the configuration.");
            return;
        }

        String url = jdbcUrl(host, port, props);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            // Before anything is executed: the connector must not change the
            // server it replicates from.
            makeReadOnly(conn);
            String version = scalar(conn, "SELECT VERSION()");
            boolean gipkSupported = supportsGipk(version);

            // A table the connector is not replicating cannot be replicated
            // incorrectly, so it must not block startup. Without this, the only
            // way past a keyless table was to add a key to it or disable the
            // check entirely -- even when the operator had already excluded it.
            List<String> keyless = withoutExcluded(
                    keylessTables(conn, databaseFilter(props)), props);

            if (!gipkSupported) {
                if (keyless.isEmpty()) {
                    log.info("MySQL {} predates the generated invisible primary key ({}.{}.{}), but the "
                                    + "source declares no keyless tables. Every table has a usable row identity.",
                            version, GIPK_MAJOR, GIPK_MINOR, GIPK_PATCH);
                    return;
                }
                throw new UnsupportedSourceException(refusalTooOld(version, keyless));
            }

            if (!keyless.isEmpty()) {
                throw new UnsupportedSourceException(refusalPreExisting(keyless));
            }
            if (gipkEnabledGlobally(conn)) {
                log.info("Keyless-table check passed: no table without a PRIMARY KEY or UNIQUE key, and "
                        + "sql_generate_invisible_primary_key is ON so any created from now on will "
                        + "receive one from MySQL.");
            } else {
                log.warn("Keyless-table check passed: every table currently has a row identity. But "
                        + "sql_generate_invisible_primary_key is OFF on this server, so a table created "
                        + "from now on WITHOUT a PRIMARY KEY will have no row identity in the binlog and "
                        + "will be refused at the next restart. To have MySQL supply one automatically:\n"
                        + "    SET GLOBAL sql_generate_invisible_primary_key = ON;\n"
                        + "    # and in my.cnf so it survives a restart:\n"
                        + "    sql_generate_invisible_primary_key = ON\n"
                        + "Note this makes MySQL REJECT keyless CREATE TABLE ... PARTITION BY, so it is "
                        + "the server owner's call -- the connector does not set it.");
            }
        } catch (UnsupportedSourceException e) {
            throw e;
        } catch (Exception e) {
            // Never block a healthy pipeline on this check itself.
            log.warn("Keyless-table check could not run ({}). Continuing. If the source holds a table "
                    + "with no PRIMARY KEY and no UNIQUE key, its ClickHouse copy may silently "
                    + "disagree with MySQL.", e.toString());
        }
    }

    /**
     * The preflight's JDBC URL, honouring the connector's own TLS setting.
     *
     * <p>Hard-coding {@code useSSL=false} would make this check fail on any
     * server enforcing TLS ({@code require_secure_transport=ON}, the default on
     * RDS and most managed MySQL). That failure is caught and the pipeline
     * continues, so the check would be SILENTLY BYPASSED for exactly the
     * deployments most likely to matter -- and Debezium, using its own
     * configured SSL properties, would then happily replicate the keyless
     * tables this exists to refuse. It also needlessly downgrades the
     * authentication exchange where TLS is available.</p>
     *
     * <p>So {@code database.ssl.mode} drives it, with Debezium's own default of
     * {@code preferred} when unset: TLS when the server offers it, plaintext
     * when it does not, and no silent bypass either way.
     * {@code allowPublicKeyRetrieval} is only needed for
     * {@code caching_sha2_password} over an unencrypted link, so it is set only
     * in that case.</p>
     *
     * @param host the MySQL host.
     * @param port the MySQL port.
     * @param props the connector properties, read for {@code database.ssl.mode}.
     * @return a JDBC URL whose transport security matches the connector's.
     */
    static String jdbcUrl(String host, String port, Properties props) {
        String sslMode = props.getProperty("database.ssl.mode", "preferred").trim().toLowerCase();
        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(":").append(port)
                .append("/?connectTimeout=10000&socketTimeout=30000");
        switch (sslMode) {
            case "disabled":
                // Explicitly plaintext: public key retrieval is then required
                // for caching_sha2_password to authenticate at all.
                url.append("&useSSL=false&allowPublicKeyRetrieval=true");
                break;
            case "required":
                url.append("&useSSL=true&requireSSL=true&verifyServerCertificate=false");
                break;
            case "verify_ca":
            case "verify_identity":
                url.append("&useSSL=true&requireSSL=true&verifyServerCertificate=true");
                break;
            case "preferred":
            default:
                // Debezium's default: TLS if the server offers it, plaintext if
                // not. Public key retrieval covers the plaintext fallback.
                url.append("&useSSL=true&requireSSL=false&verifyServerCertificate=false")
                        .append("&allowPublicKeyRetrieval=true");
                break;
        }
        return url.toString();
    }

    /**
     * Opens the preflight's connection to the source in read-only mode.
     *
     * <p>The connector is a replication CONSUMER: it must never change the
     * server it reads from. That is enforced here rather than left to review,
     * on three levels, because an earlier version of this class did exactly
     * what this now prevents -- it ran
     * {@code SET GLOBAL sql_generate_invisible_primary_key = ON}, after which
     * MySQL rejected every keyless {@code CREATE TABLE ... PARTITION BY} on
     * the whole server (ERROR 1235). A consumer caused an outage on its own
     * source, and it outlived the connector.</p>
     *
     * <ol>
     *   <li>{@link Connection#setReadOnly(boolean)} -- the MySQL driver sends
     *       {@code SET SESSION TRANSACTION READ ONLY}, so the SERVER rejects a
     *       write on this connection even if new code asks for one. This is
     *       the only layer that does not depend on the connector behaving.</li>
     *   <li>{@link #assertReadOnlySql} on every statement this class runs, so
     *       a write is refused before it is sent, with a message naming the
     *       rule rather than a driver error.</li>
     *   <li>{@code testPreflightIssuesNoWritesToTheSource}, which fails the
     *       build if a mutating keyword is ever added to this file.</li>
     * </ol>
     *
     * <p>Read-only is requested and then VERIFIED: a driver that silently
     * ignored the request would leave the guarantee resting on the other two
     * layers without saying so, and a failure to obtain it is worth a warning.
     * Note this is a session property of the preflight's own short-lived
     * connection -- it changes nothing for any other client, which is exactly
     * the distinction the old {@code SET GLOBAL} got wrong.</p>
     *
     * @param conn the freshly opened connection to the source.
     */
    private static void makeReadOnly(Connection conn) {
        try {
            conn.setReadOnly(true);
            if (!conn.isReadOnly()) {
                log.warn("The MySQL driver did not honour the read-only request on the "
                        + "keyless-table check's connection. The check still issues only SELECTs "
                        + "(enforced by assertReadOnlySql), but the server-side guarantee is absent.");
            }
        } catch (Exception e) {
            log.warn("Could not set the keyless-table check's connection read-only ({}). The check "
                    + "still issues only SELECTs, enforced client-side.", e.toString());
        }
    }

    /**
     * Refuses any statement that is not a plain read, before it is sent.
     *
     * <p>Belt to {@link #makeReadOnly}'s braces, and the layer that produces a
     * comprehensible message: a future edit adding a write to this class fails
     * here naming the rule, rather than surfacing as a driver error from
     * whatever the server said.</p>
     *
     * <p>Deliberately an ALLOWLIST. A denylist of mutating keywords is the
     * wrong shape for a safety check: every statement MySQL gains in a future
     * version defaults to permitted, and the one that matters here --
     * {@code SET} -- is easy to forget precisely because it does not read like
     * a write. Only {@code SELECT} is allowed, which is all this class needs.
     * Leading comments are stripped first so a commented statement cannot slip
     * past the prefix test.</p>
     *
     * @param sql the statement about to be executed against the source.
     * @throws IllegalStateException when it is anything but a SELECT.
     */
    static void assertReadOnlySql(String sql) {
        String bare = sql == null ? "" : sql.trim();
        // Strip leading /* */ and -- comments so the check sees the real verb.
        while (true) {
            if (bare.startsWith("/*")) {
                int end = bare.indexOf("*/");
                if (end < 0) {
                    break;
                }
                bare = bare.substring(end + 2).trim();
            } else if (bare.startsWith("--") || bare.startsWith("#")) {
                int end = bare.indexOf('\n');
                if (end < 0) {
                    bare = "";
                    break;
                }
                bare = bare.substring(end + 1).trim();
            } else {
                break;
            }
        }
        if (!bare.regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
            throw new IllegalStateException(
                    "the connector is read-only against its MySQL source: the keyless-table check "
                            + "may only issue SELECT, refusing to execute: " + sql);
        }
    }

    /**
     * Whether the server will give newly created keyless tables a key.
     *
     * @param conn an open connection to the source.
     * @return true when the global {@code sql_generate_invisible_primary_key}
     *         is ON. Unreadable is reported as false: the advice that follows
     *         is then merely redundant, never wrongly reassuring.
     */
    private static boolean gipkEnabledGlobally(Connection conn) {
        try {
            // One round trip: MySQL renders this as 1/0 or ON/OFF depending on
            // version and client, so both spellings are accepted.
            String value = scalar(conn, "SELECT @@GLOBAL.sql_generate_invisible_primary_key");
            return "ON".equalsIgnoreCase(value) || "1".equals(value);
        } catch (Exception e) {
            log.debug("Could not read @@GLOBAL.sql_generate_invisible_primary_key: {}", e.toString());
            return false;
        }
    }

    /**
     * Whether this MySQL supports the generated invisible primary key.
     *
     * @param version the raw {@code VERSION()} string, e.g. {@code 8.0.36-log}.
     * @return true from 8.0.30 onward. An unparseable version is treated as
     *         supported: the pre-existing-table check still runs, and a wrong
     *         guess there fails loudly rather than silently.
     */
    static boolean supportsGipk(String version) {
        if (version == null) {
            return true;
        }
        // MariaDB reports MySQL-like versions but has no GIPK. It is not a
        // supported source for a keyless table either way; treat it as old so
        // the refusal names the real reason.
        if (version.toLowerCase().contains("mariadb")) {
            return false;
        }
        String[] parts = version.split("[.\\-+~]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (major != GIPK_MAJOR) {
                return major > GIPK_MAJOR;
            }
            if (minor != GIPK_MINOR) {
                return minor > GIPK_MINOR;
            }
            return patch >= GIPK_PATCH;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * Lists base tables with no PRIMARY KEY and no non-null UNIQUE key.
     *
     * <p>A UNIQUE index counts as a row identity only when EVERY one of its
     * columns is {@code NOT NULL}. MySQL does not treat NULLs as equal for
     * uniqueness, so a single nullable member makes the whole index permissive:
     * {@code UNIQUE(a, b)} with {@code b} nullable accepts {@code (1, NULL)}
     * twice. Testing "any column of the index is NOT NULL" would pass such a
     * table as keyed while it has no identity at all -- the exact silent
     * collapse this check exists to prevent -- so the test is per index, with
     * {@code GROUP BY s.index_name} and a {@code HAVING} that requires zero
     * nullable members.</p>
     */
    static String keylessTablesQuery(String databaseFilter) {
        return
                "SELECT t.table_schema, t.table_name "
                        + "FROM information_schema.tables t "
                        + "WHERE t.table_type = 'BASE TABLE' "
                        + "  AND t.table_schema NOT IN "
                        + "      ('mysql','information_schema','performance_schema','sys') "
                        + (databaseFilter == null ? "" : "  AND t.table_schema IN (" + databaseFilter + ") ")
                        + "  AND NOT EXISTS ("
                        + "      SELECT 1 FROM information_schema.statistics s "
                        + "      JOIN information_schema.columns c "
                        + "        ON c.table_schema = s.table_schema "
                        + "       AND c.table_name = s.table_name "
                        + "       AND c.column_name = s.column_name "
                        + "      WHERE s.table_schema = t.table_schema "
                        + "        AND s.table_name = t.table_name "
                        + "        AND s.non_unique = 0 "
                        + "      GROUP BY s.index_name "
                        + "      HAVING SUM(CASE WHEN c.is_nullable = 'YES' THEN 1 ELSE 0 END) = 0) "
                        + "ORDER BY t.table_schema, t.table_name";
    }

    private static List<String> keylessTables(Connection conn, String databaseFilter) throws Exception {
        List<String> tables = new ArrayList<>();
        String sql = keylessTablesQuery(databaseFilter);
        assertReadOnlySql(sql);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(rs.getString(1) + "." + rs.getString(2));
            }
        }
        return tables;
    }

    /**
     * The connector's {@code database.include.list} as a quoted SQL list, so
     * the check reports only databases actually being replicated.
     *
     * <p>Debezium matches that list as REGULAR EXPRESSIONS, so a pattern entry
     * such as {@code app.*} names no schema literally. Turning it into
     * {@code IN ('app.*')} would match nothing and the check would report a
     * clean source while the replicated schemas go uninspected -- a false PASS,
     * the one outcome worse than a false refusal. So any entry that is not a
     * plain literal makes this return null, which widens the scan to ALL
     * non-system schemas. Over-scanning can only surface a table that is
     * genuinely keyless; it can never hide one.</p>
     */
    /**
     * Drops tables the connector is not actually replicating.
     *
     * <p>A table that is excluded is never read from the binlog, so it cannot
     * be replicated incorrectly and must not block startup. Without this, an
     * operator who had already excluded a keyless table still could not start:
     * the only ways past were adding a key to a table they did not own, or
     * disabling the whole check and losing the protection for every other
     * table.</p>
     *
     * <p>Both Debezium list properties are honoured, with its own semantics:
     * entries are REGULAR EXPRESSIONS matched against the fully-qualified
     * {@code db.table}, and {@code table.include.list} — when set — means
     * anything NOT listed is excluded. The scan is deliberately conservative in
     * the same direction as {@code databaseFilter}: if a pattern cannot be
     * compiled it is ignored rather than treated as a match, so a malformed
     * exclude can only leave a keyless table reported (a false refusal), never
     * silently drop one from the check (a false pass).</p>
     *
     * @param keyless fully-qualified {@code db.table} names found keyless.
     * @param props the connector properties.
     * @return those still in scope for replication.
     */
    static List<String> withoutExcluded(List<String> keyless, Properties props) {
        List<java.util.regex.Pattern> excludes =
                compilePatterns(props.getProperty("table.exclude.list"));
        List<java.util.regex.Pattern> includes =
                compilePatterns(props.getProperty("table.include.list"));

        List<String> inScope = new ArrayList<>();
        for (String table : keyless) {
            if (matchesAny(excludes, table)) {
                log.info("Keyless table {} is excluded by table.exclude.list, so it is not "
                        + "replicated and does not block startup.", table);
                continue;
            }
            if (!includes.isEmpty() && !matchesAny(includes, table)) {
                log.info("Keyless table {} is outside table.include.list, so it is not "
                        + "replicated and does not block startup.", table);
                continue;
            }
            inScope.add(table);
        }
        return inScope;
    }

    /**
     * Compiles a Debezium comma-separated regex list, skipping what will not
     * compile.
     *
     * <p>An uncompilable pattern is dropped rather than propagated, because
     * this list can only ever REMOVE tables from the refusal set: treating a
     * broken pattern as matching would silently exclude a genuinely keyless
     * table from the check.</p>
     */
    private static List<java.util.regex.Pattern> compilePatterns(String csv) {
        List<java.util.regex.Pattern> patterns = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) {
            return patterns;
        }
        for (String raw : csv.split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) {
                continue;
            }
            try {
                patterns.add(java.util.regex.Pattern.compile(pattern));
            } catch (java.util.regex.PatternSyntaxException e) {
                log.warn("Ignoring unparseable table list pattern '{}' when deciding whether a "
                        + "keyless table blocks startup: {}", pattern, e.getMessage());
            }
        }
        return patterns;
    }

    /**
     * Whether any pattern matches the fully-qualified name, Debezium-style.
     *
     * <p>Debezium anchors these patterns (a full match, not a substring), so
     * {@code aerion.trade} must not be excluded by a pattern written for
     * {@code aerion.trade_history}.</p>
     */
    private static boolean matchesAny(List<java.util.regex.Pattern> patterns, String table) {
        for (java.util.regex.Pattern p : patterns) {
            if (p.matcher(table).matches()) {
                return true;
            }
        }
        return false;
    }

    static String databaseFilter(Properties props) {
        String include = props.getProperty("database.include.list");
        if (include == null || include.trim().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String db : include.split(",")) {
            String name = db.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(",");
            }
            // A regex metacharacter means this entry is a pattern, not a name.
            // information_schema names cannot contain a quote either. In both
            // cases fall back to scanning everything rather than building a
            // predicate that silently matches nothing.
            if (!LITERAL_SCHEMA_NAME.matcher(name).matches()) {
                log.info("database.include.list entry '{}' is a pattern, not a literal schema "
                        + "name, so the keyless-table check scans every non-system schema "
                        + "instead of guessing which ones it matches.", name);
                return null;
            }
            sb.append("'").append(name).append("'");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String scalar(Connection conn, String sql) throws Exception {
        assertReadOnlySql(sql);
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String refusalTooOld(String version, List<String> keyless) {
        return KeylessTableWarning.banner(keyless, false)
                + "\nREFUSING TO REPLICATE. MySQL " + version + " is older than "
                + GIPK_MAJOR + "." + GIPK_MINOR + "." + GIPK_PATCH + ", where the generated invisible "
                + "primary key was introduced, so MySQL cannot supply an identity for these tables "
                + "either. Add a primary key to each, or upgrade to MySQL "
                + GIPK_MAJOR + "." + GIPK_MINOR + "." + GIPK_PATCH + "+.\n"
                + "Set " + SKIP_PROPERTY + "=true to override, accepting that those tables may diverge.";
    }

    static String refusalPreExisting(List<String> keyless) {
        return KeylessTableWarning.banner(keyless, true)
                + "\nREFUSING TO REPLICATE until each table above has a primary key.\n"
                + "Set " + SKIP_PROPERTY + "=true to override, accepting that those tables may diverge.";
    }
}

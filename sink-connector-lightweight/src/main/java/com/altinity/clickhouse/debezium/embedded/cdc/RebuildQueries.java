package com.altinity.clickhouse.debezium.embedded.cdc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The plain-JDBC statements the primary-key rebuild ({@link PrimaryKeyRebuild})
 * and its online backfill ({@link PrimaryKeyBackfill}) run against ClickHouse
 * (Spec 06.09 section 2): one {@link Statement} per call, no retry -- each caller
 * decides how a failure is retried (the swap restarts at step 1 on redelivery,
 * the backfill retries the whole attempt). Every failure, including one raised
 * while reading rows, reaches the caller as the exception its {@link Failure}
 * builds; logging stays with the caller, which knows the table and the step.
 */
final class RebuildQueries {

    /** Builds the caller's exception for a statement that failed. */
    @FunctionalInterface
    interface Failure {
        RuntimeException of(String sql, Exception cause);
    }

    /** Reads one row of a result set. */
    @FunctionalInterface
    interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private RebuildQueries() {
    }

    static void exec(Connection ch, String sql, Failure failure) {
        try (Statement st = ch.createStatement()) {
            st.execute(sql);
        } catch (Exception e) {
            throw failure.of(sql, e);
        }
    }

    /** The first column of the first row, or {@code null} when there is no row. */
    static String scalar(Connection ch, String sql, Failure failure) {
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (Exception e) {
            throw failure.of(sql, e);
        }
    }

    /** Every row of {@code sql}, read by {@code reader}, in result order. */
    static <T> List<T> rows(Connection ch, String sql, RowReader<T> reader, Failure failure) {
        List<T> out = new ArrayList<>();
        try (Statement st = ch.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(reader.read(rs));
            }
        } catch (Exception e) {
            throw failure.of(sql, e);
        }
        return out;
    }

    /** The first column of every row, as strings. */
    static List<String> column(Connection ch, String sql, Failure failure) {
        return rows(ch, sql, rs -> rs.getString(1), failure);
    }

    /** The {@code system.columns} query for one table, in column order. */
    static String columnsQuery(String db, String table) {
        return "SELECT name, type, default_kind FROM system.columns WHERE database = '"
                + PrimaryKeyRebuild.lit(db) + "' AND table = '" + PrimaryKeyRebuild.lit(table)
                + "' ORDER BY position";
    }

    /** The table's columns (name, type, default kind), in column order. */
    static List<PrimaryKeyRebuild.ColumnInfo> columns(Connection ch, String sql, Failure failure) {
        return rows(ch, sql,
                rs -> new PrimaryKeyRebuild.ColumnInfo(rs.getString(1), rs.getString(2), rs.getString(3)),
                failure);
    }
}

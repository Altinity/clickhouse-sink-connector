package com.altinity.clickhouse.sink.connector.common;

import com.altinity.clickhouse.sink.connector.db.batch.MissingTargetColumnException;
import com.altinity.clickhouse.sink.connector.db.operations.ColumnTypeOverrideMismatchException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Spec 10.01 section 6 (Failure Modes & Recovery): which failures the
 * classifier sends to the unbounded retry of spec 10.02 and which it stops on.
 *
 * <p>The retry has no attempt cap by design, so every failure classified
 * RETRIABLE or UNKNOWN stalls its worker until the cause is removed, with a
 * WARN per attempt as its only in-process signal. These tests pin exactly
 * which failures land there, so a change to that set is a reviewed change.</p>
 */
public class ClickHouseErrorClassifierFailureModesTest {

    private static SQLException clickHouse(int code, String text) {
        return new SQLException("Code: " + code + ". DB::Exception: " + text);
    }

    @Test
    @DisplayName("ClickHouse-side conditions that clear by themselves are retried, never stopped on")
    public void selfHealingServerConditionsAreRetriable() {
        int[] codes = {
                242,  // TABLE_IS_READ_ONLY: a Replicated table lost its Keeper session
                999,  // KEEPER_EXCEPTION
                243,  // NOT_ENOUGH_SPACE: the server disk is full
                202,  // TOO_MANY_SIMULTANEOUS_QUERIES
                159,  // TIMEOUT_EXCEEDED
                209,  // SOCKET_TIMEOUT
                210,  // NETWORK_ERROR
                252,  // TOO_MANY_PARTS
                241,  // MEMORY_LIMIT_EXCEEDED
        };
        for (int code : codes) {
            assertEquals(ClickHouseErrorClassifier.ErrorCategory.RETRIABLE,
                    ClickHouseErrorClassifier.classify(new RuntimeException("insert failed",
                            clickHouse(code, "x"))),
                    "code " + code + " clears without operator action; stopping the connector on it "
                            + "would turn a self-healing stall into an outage");
        }
    }

    @Test
    @DisplayName("privilege and read-only errors outside the FATAL set are retried forever (pinned)")
    public void privilegeErrorsOutsideTheFatalSetAreRetried() {
        // 164 READONLY and 291 DATABASE_ACCESS_DENIED are deterministic until an
        // operator changes a grant or a setting, yet they are not in
        // FATAL_ERROR_CODES (only 497 ACCESS_DENIED and 516 AUTHENTICATION_FAILED
        // are). They stall the worker with a WARN per attempt; the fix (grant or
        // setting) is picked up on the next attempt without a restart.
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.RETRIABLE,
                ClickHouseErrorClassifier.classify(clickHouse(164, "Cannot execute query in readonly mode")));
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.RETRIABLE,
                ClickHouseErrorClassifier.classify(clickHouse(291, "Access denied to database db")));
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(clickHouse(497, "Not enough privileges")));
    }

    @Test
    @DisplayName("the connector's own deterministic refusals carry no code and are retried forever (pinned)")
    public void connectorRefusalsAreUnknownAndRetried() {
        // MissingTargetColumnException (spec 08.04), the IllegalStateException of a
        // refused ReplacingMergeTree target or a refused delete marker (spec 08.01
        // section 3.2): deterministic until the ClickHouse table is fixed, and
        // retried with backoff so the fix is picked up without a restart.
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.UNKNOWN,
                ClickHouseErrorClassifier.classify(new MissingTargetColumnException(
                        "Column 'note' is carried by the source record but does not exist")));
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.UNKNOWN,
                ClickHouseErrorClassifier.classify(new IllegalStateException(
                        "ReplacingMergeTree table db.t has no usable version column")));
    }

    @Test
    @DisplayName("a server error quoted inside a connector message is classified by its code")
    public void codeQuotedInsideAWrapperIsFound() {
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(new RuntimeException("Fatal ClickHouse error, stopping task",
                        new RuntimeException("wrapped", clickHouse(16, "No such column note in table db.t")))),
                "a column removed out of band makes the cached INSERT name a missing column: code 16, FATAL");
    }

    @Test
    @Disabled("DEFECT FM-08.05-4: ColumnTypeOverrideMismatchException is documented to halt the connector "
            + "(spec 08.05 section 3.3, spec 10.04 section 3.8) but carries no ClickHouse code and is not a "
            + "TERMINAL_EXCEPTION_TYPES member, so it classifies UNKNOWN and the batch is retried forever")
    @DisplayName("a column type override that contradicts the table is terminal, as documented")
    public void columnTypeOverrideMismatchIsFatal() {
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL,
                ClickHouseErrorClassifier.classify(new ColumnTypeOverrideMismatchException(
                        "column_type_override for db.t.c says Decimal(38,10) but the table has String")),
                "a configuration contradiction cannot heal by retry; only a config change and a restart fix it");
    }
}

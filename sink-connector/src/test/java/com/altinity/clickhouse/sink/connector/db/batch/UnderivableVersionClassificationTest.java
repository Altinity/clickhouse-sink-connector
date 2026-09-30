package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.common.ClickHouseErrorClassifier;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Failure mode FM-02.05-1 (spec 02.05 section 6): what the worker does with the
 * refusal of an underivable {@code _version}.
 *
 * <p>{@code PreparedStatementFieldMapper.rejectUnderivableVersion} refuses a
 * version {@code <= 0} with an {@link IllegalStateException}. The refusal is
 * deterministic -- the same record is redelivered with the same missing
 * coordinates -- but the exception carries no ClickHouse error code, so
 * {@code ClickHouseErrorClassifier.classify} answers {@code UNKNOWN} and
 * {@code ClickHouseBatchRunnable} retries the same batch with backoff forever,
 * stalling every table hashed to that worker, instead of stopping loudly as it
 * does for a FATAL error (spec 03.01 section 3.3, spec 10.01 section 3.1).</p>
 */
public class UnderivableVersionClassificationTest {

    private static IllegalStateException refusal() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("srv.db.t");
        return assertThrows(IllegalStateException.class,
                () -> PreparedStatementFieldMapper.rejectUnderivableVersion(record),
                "a record with no derivable version (-1) is refused");
    }

    @Test
    @DisplayName("today the refusal carries no ClickHouse code and is classified UNKNOWN (retried)")
    public void refusalIsCurrentlyClassifiedUnknown() {
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.UNKNOWN, ClickHouseErrorClassifier.classify(refusal()),
                "UNKNOWN is retried by the worker; this pins the 2.11.0 behaviour recorded as DEFECT FM-02.05-1");
    }

    @Test
    @Disabled("DEFECT FM-02.05-1: a deterministic version refusal is classified UNKNOWN and retried forever "
            + "instead of stopping the worker and the engine loudly")
    @DisplayName("the refusal of an underivable version is FATAL: no retry can derive the missing coordinate")
    public void underivableVersionIsClassifiedFatal() {
        assertEquals(ClickHouseErrorClassifier.ErrorCategory.FATAL, ClickHouseErrorClassifier.classify(refusal()));
    }
}

package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Enforces spec 10.06 (no-row-loss is parameter-independent) at the code level:
 * the class that decides WHEN a Debezium offset may be committed
 * ({@link DebeziumOffsetManagement}) must reach that decision without reading any
 * tuning parameter.
 *
 * <p>The zero-loss guarantee (Invariant I8; spec 09.01) holds because the durable
 * offset is advanced only after a batch's rows are durably written, in handoff
 * (binlog) order. That property is a function of the handoff / write / acknowledge
 * events alone. If the commit-decision class ever gained a dependency on
 * {@link ClickHouseSinkConnectorConfig} or {@link Properties} — a flush timeout,
 * a buffer size, a pool size, a retry budget, a heartbeat interval — the guarantee
 * would become a function of that knob, exactly the coupling spec 10.06 forbids.
 *
 * <p>This is a guard test, not a behavioural one: it fails the build if a future
 * change couples the commit frontier to configuration, forcing a spec revision
 * (Constitution, Law of Reviewed Immutability). It first asserts the decision API
 * still exists, so it can never pass vacuously after a rename — a rename must
 * revisit this test and spec 10.06.</p>
 */
public class OffsetCommitDecisionParameterFreeTest {

    private static final Class<?> DECISION = DebeziumOffsetManagement.class;

    /** Types that carry connector tuning parameters. Reached as real class
     * references (not strings) so a typo cannot make the check vacuous. */
    private static boolean isConfigurationType(Class<?> t) {
        return t == ClickHouseSinkConnectorConfig.class || t == Properties.class;
    }

    @Test
    @DisplayName("The offset-commit decision API exists (this guard cannot pass vacuously)")
    public void decisionApiExists() {
        assertDoesNotThrow(() -> DECISION.getDeclaredMethod(
                "registerHandoff", List.class, List.class),
                "registerHandoff(List, List) is the producer side of the commit frontier");
        assertDoesNotThrow(() -> DECISION.getDeclaredMethod(
                "checkIfBatchCanBeCommitted", List.class),
                "checkIfBatchCanBeCommitted(List) is where a written batch's offset becomes eligible");
        assertDoesNotThrow(() -> DECISION.getDeclaredMethod("hasUnwrittenBatches"),
                "hasUnwrittenBatches() is the quiescence predicate");
        assertDoesNotThrow(() -> DECISION.getDeclaredMethod("outstandingCount"),
                "outstandingCount() reports the frontier backlog");
        assertDoesNotThrow(() -> DECISION.getDeclaredMethod("reset"),
                "reset() is the in-process-restart abandon path");
    }

    @Test
    @DisplayName("No method of the commit-decision class takes or returns a configuration type")
    public void noMethodTouchesConfiguration() {
        for (Method m : DECISION.getDeclaredMethods()) {
            assertFalse(isConfigurationType(m.getReturnType()),
                    "commit-decision method " + m.getName() + " returns a configuration type; "
                            + "the offset frontier must not depend on a tuning parameter (spec 10.06)");
            for (Class<?> p : m.getParameterTypes()) {
                assertFalse(isConfigurationType(p),
                        "commit-decision method " + m.getName() + " takes a configuration parameter (" + p.getSimpleName()
                                + "); the offset frontier must not depend on a tuning parameter (spec 10.06)");
            }
        }
    }

    @Test
    @DisplayName("No constructor or field of the commit-decision class holds a configuration type")
    public void noConstructorOrFieldHoldsConfiguration() {
        for (Constructor<?> c : DECISION.getDeclaredConstructors()) {
            for (Class<?> p : c.getParameterTypes()) {
                assertFalse(isConfigurationType(p),
                        "commit-decision constructor takes a configuration parameter (" + p.getSimpleName()
                                + "); the offset frontier must not depend on a tuning parameter (spec 10.06)");
            }
        }
        for (Field f : DECISION.getDeclaredFields()) {
            assertFalse(isConfigurationType(f.getType()),
                    "commit-decision field " + f.getName() + " holds a configuration type; "
                            + "the offset frontier must not depend on a tuning parameter (spec 10.06)");
        }
    }
}

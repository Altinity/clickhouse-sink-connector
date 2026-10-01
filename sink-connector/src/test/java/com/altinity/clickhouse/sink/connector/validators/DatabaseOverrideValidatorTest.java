package com.altinity.clickhouse.sink.connector.validators;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Spec 03.04 section 6, FM-03.04-1: a malformed
 * {@code clickhouse.database.override.map} must stop the connector at
 * configuration load.
 *
 * <p>{@code DatabaseOverrideValidator.ensureValid} throws its
 * {@link ConfigException} INSIDE a {@code try} whose {@code catch (Exception)}
 * only prints the stack trace, so every malformed value is accepted. What
 * happens next depends on how it is malformed: a missing ':' makes
 * {@code Utils.parseSourceToDestinationDatabaseMap} return {@code null}, the
 * worker stores the null and throws {@code NullPointerException} from
 * {@code resolveDatabaseName} on every batch (classified UNKNOWN, retried
 * forever); an invalid or duplicated destination makes it throw, the worker
 * logs "Error parsing database override map" once and keeps an EMPTY map, and
 * every row is written to the SOURCE-named database, which the worker creates
 * on first sight.</p>
 */
public class DatabaseOverrideValidatorTest {

    private static Map<String, String> props(String overrideMap) {
        Map<String, String> props = new HashMap<>();
        props.put("clickhouse.database.override.map", overrideMap);
        return props;
    }

    @Test
    @Disabled("DEFECT FM-03.04-1: DatabaseOverrideValidator swallows its own ConfigException, so a malformed "
            + "override map is accepted and replication either retries an NPE forever or writes to the source-named "
            + "database")
    @DisplayName("A malformed database override map is rejected when the configuration is loaded")
    public void aMalformedOverrideMapIsRejectedWhenTheConfigIsLoaded() {
        // No ':' -- parseSourceToDestinationDatabaseMap returns null.
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(props("employees")));
        // Invalid destination name -- parseSourceToDestinationDatabaseMap throws.
        assertThrows(ConfigException.class, () -> new ClickHouseSinkConnectorConfig(props("employees:1x")));
        // Duplicated source -- parseSourceToDestinationDatabaseMap throws.
        assertThrows(ConfigException.class,
                () -> new ClickHouseSinkConnectorConfig(props("employees:target_a,employees:target_b")));
    }
}

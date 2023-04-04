package com.altinity.clickhouse.sink.connector.deduplicator;

import com.google.common.base.Strings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enum with de-duplication policy options.
 */
public enum DeDuplicationPolicy {
    // De-duplicator is turned off
    OFF,

    // Keep old value
    OLD,

    // Keep new value
    NEW,
    ;

    /**
     * List of names (string) of all enum items.
     * Lowercase.
     */
    public static final List<String> POLICY_NAMES =
            Arrays.stream(DeDuplicationPolicy.values())
                    .map(policy -> policy.name().toLowerCase())
                    .collect(Collectors.toList());

    /**
     * Creates the DeDuplicationPolicy object from a string.
     * Case-insensitive.
     *
     * @param name enum item (name)
     * @return DeDuplicationPolicy instance
     */
    public static DeDuplicationPolicy of(final String name) {
        // Sanity check for empty values
        if (Strings.isNullOrEmpty(name)) {
            return DeDuplicationPolicy.OFF;
        }

        // Try to find enum value with the same name (case-insensitive)
        for (final DeDuplicationPolicy policy : DeDuplicationPolicy.values()) {
            if (policy.name().equalsIgnoreCase(name)) {
                return policy;
            }
        }

        // Nothing found, throw an exception
        throw new IllegalArgumentException(
                String.format(
                        "Unsupported DeDuplicationPolicy name: %s. Supported are: %s",
                        name, String.join(",", POLICY_NAMES)));
    }
}

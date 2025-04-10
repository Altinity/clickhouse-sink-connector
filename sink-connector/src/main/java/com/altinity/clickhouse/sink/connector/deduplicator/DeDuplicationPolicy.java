package com.altinity.clickhouse.sink.connector.deduplicator;

import com.google.common.base.Strings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enum with de-duplication policy options.
 * <p>
 * This enum provides several de-duplication policies, which determine
 * how to handle a newly received record if there is already
 * an existing record with the same key.
 * </p>
 */
public enum DeDuplicationPolicy {
    /**
     * De-duplicator is turned off. All records are accepted without
     * any check for duplication.
     */
    OFF,

    /**
     * Keep the old value and ignore the new one if a duplicate
     * key is found.
     */
    OLD,

    /**
     * Keep the new value and overwrite the old one if a duplicate
     * key is found.
     */
    NEW,
    ;

    /**
     * List of names (string) of all enum items, in lowercase form.
     */
    public static final List<String> POLICY_NAMES =
            Arrays.stream(DeDuplicationPolicy.values())
                    .map(policy -> policy.name().toLowerCase())
                    .collect(Collectors.toList());

    /**
     * Creates the {@link DeDuplicationPolicy} object from a string,
     * ignoring case.
     * <p>
     * If the given name is null or empty, {@link DeDuplicationPolicy#OFF}
     * is returned. If the given name does not match any policy, an
     * {@link IllegalArgumentException} is thrown.
     * </p>
     *
     * @param name The name of the de-duplication policy.
     * @return The corresponding {@link DeDuplicationPolicy} instance.
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
                        name,
                        String.join(",", POLICY_NAMES)
                )
        );
    }
}

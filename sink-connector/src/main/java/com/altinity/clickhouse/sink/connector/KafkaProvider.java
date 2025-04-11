package com.altinity.clickhouse.sink.connector;

import com.google.common.base.Strings;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * KafkaProvider is an enum with possible values of Kafka provider.
 * <p>
 * It represents the possible Kafka provider types such as self-hosted
 * or managed by Confluent.
 * </p>
 */
public enum KafkaProvider {

    /**
     * Default value.
     */
    UNKNOWN,

    /**
     * Kafka/KC is on self-hosted node.
     */
    SELF_HOSTED,

    /**
     * Hosted/managed by Confluent.
     */
    CONFLUENT;

    /**
     * All valid enum values.
     */
    public static final List<String> PROVIDER_NAMES =
            Arrays.stream(KafkaProvider.values())
                    .map(kafkaProvider -> kafkaProvider.name().toLowerCase())
                    .collect(Collectors.toList());

    /**
     * Creates the KafkaProvider object from a string.
     *
     * @param kafkaProviderStr the provider name as a string.
     * @return the corresponding KafkaProvider enum value.
     * @throws IllegalArgumentException if the provider name is unsupported.
     */
    public static KafkaProvider of(final String kafkaProviderStr) {
        if (Strings.isNullOrEmpty(kafkaProviderStr)) {
            return KafkaProvider.UNKNOWN;
        }
        for (final KafkaProvider p : KafkaProvider.values()) {
            if (p.name().equalsIgnoreCase(kafkaProviderStr)) {
                return p;
            }
        }
        throw new IllegalArgumentException(
                String.format(
                        "Unsupported provider name: %s. Supported are: %s",
                        kafkaProviderStr, String.join(",", PROVIDER_NAMES)));
    }
}

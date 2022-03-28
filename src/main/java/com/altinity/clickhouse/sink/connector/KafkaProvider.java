package com.altinity.clickhouse.sink.connector;

import com.google.common.base.Strings;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/* Enum which represents allowed values of kafka provider. (Hosted Platform) */
public enum KafkaProvider {
    // Default value, when nothing is provided. (More like Not Applicable)
    UNKNOWN,

    // Kafka/KC is on self hosted node
    SELF_HOSTED,

    // Hosted/managed by Confluent
    CONFLUENT,
    ;

    // All valid enum values
    public static final List<String> PROVIDER_NAMES =
        Arrays.stream(KafkaProvider.values())
            .map(kafkaProvider -> kafkaProvider.name().toLowerCase())
            .collect(Collectors.toList());

    // Returns the KafkaProvider object from string. It does convert an empty or null value to an
    // Enum.
    public static KafkaProvider of(final String kafkaProviderStr) {

      if (Strings.isNullOrEmpty(kafkaProviderStr)) {
        return KafkaProvider.UNKNOWN;
      }

      for (final KafkaProvider b : KafkaProvider.values()) {
        if (b.name().equalsIgnoreCase(kafkaProviderStr)) {
          return b;
        }
      }
      throw new IllegalArgumentException(
          String.format(
              "Unsupported provider name: %s. Supported are: %s",
              kafkaProviderStr, String.join(",", PROVIDER_NAMES)));
    }
}
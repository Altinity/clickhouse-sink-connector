package com.altinity.clickhouse.sink.connector.metadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The SinkConfig class defines the configuration related to the sink operations
 * in the ClickHouse connector, including different insert modes and primary key
 * modes.
 * <p>
 * This class provides the configuration for insert operations, such as
 * determining the insert strategy (INSERT, UPSERT, or UPDATE), and the primary key
 * strategy (None, Kafka, Record Key, or Record Value).
 * </p>
 */
public class SinkConfig {

    /**
     * Enum representing the different insert modes for the sink connector.
     * <p>
     * The insert mode defines how records are inserted into the target table.
     * </p>
     */
    public enum InsertMode {
        /**
         * Insert new records into the table.
         */
        INSERT,

        /**
         * Perform an upsert operation, which inserts new records or updates
         * existing records.
         */
        UPSERT,

        /**
         * Perform an update operation on existing records.
         */
        UPDATE
    }

    /**
     * Enum representing the different primary key modes for the sink connector.
     * <p>
     * The primary key mode defines how the primary key is determined for the records.
     * </p>
     */
    public enum PrimaryKeyMode {
        /**
         * No primary key is used.
         */
        NONE,

        /**
         * Use Kafka metadata as the primary key (e.g., topic, partition, offset).
         */
        KAFKA,

        /**
         * Use the record key as the primary key.
         */
        RECORD_KEY,

        /**
         * Use the record value as the primary key.
         */
        RECORD_VALUE
    }

    /**
     * The default set of Kafka primary key column names.
     * <p>
     * These columns represent metadata from Kafka that can be used as a primary key
     * in the sink operation. The default columns include topic, partition, and offset.
     * </p>
     */
    public static final List<String> DEFAULT_KAFKA_PK_NAMES = Collections.unmodifiableList(
            Arrays.asList(
                    "__connect_topic",
                    "__connect_partition",
                    "__connect_offset"
            )
    );
}

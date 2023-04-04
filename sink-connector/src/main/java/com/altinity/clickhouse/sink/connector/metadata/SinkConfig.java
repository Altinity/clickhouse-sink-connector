package com.altinity.clickhouse.sink.connector.metadata;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SinkConfig {

    public enum InsertMode {
        INSERT,
        UPSERT,
        UPDATE

    }

    public enum PrimaryKeyMode {
        NONE,
        KAFKA,
        RECORD_KEY,
        RECORD_VALUE
    }

    public static final List<String> DEFAULT_KAFKA_PK_NAMES = Collections.unmodifiableList(
            Arrays.asList(
                    "__connect_topic",
                    "__connect_partition",
                    "__connect_offset"
            )
    );
}

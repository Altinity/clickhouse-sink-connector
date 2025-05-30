package com.altinity.clickhouse.debezium.embedded.cdc;

import java.util.Arrays;
import java.util.List;

public class IgnoreDDLRegexLoader {
    private static final List<String> REGEX_PATTERNS = Arrays.asList(
        "^ALTER TABLE .*\\s+analyze\\s+PARTITION\\s+p[0-9]+$",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+ADD\\s+PARTITION\\s*\\(",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+DROP\\s+PARTITION\\s+\\S+",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+REORGANIZE\\s+PARTITION\\s+.*?\\s+INTO\\s*\\(",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+REMOVE\\s+PARTITIONING",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+TRUNCATE\\s+PARTITION\\s+\\S+",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+ANALYZE\\s+PARTITION\\s+\\S+",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+CHECK\\s+PARTITION\\s+\\S+",
        "(?i)ALTER\\s+TABLE\\s+\\S+\\s+OPTIMIZE\\s+PARTITION\\s+\\S+"
    );

    public static List<String> loadRegexPatterns() {
        return REGEX_PATTERNS;
    }
} 
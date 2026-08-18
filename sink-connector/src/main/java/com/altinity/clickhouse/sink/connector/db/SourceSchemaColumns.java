package com.altinity.clickhouse.sink.connector.db;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts the set of column names present in a source change event
 * ({@link ClickHouseStruct}). These are the columns whose values the source
 * intends to write; they are the authoritative input to the
 * {@link SourceSchemaIntegrityValidator} data-loss check.
 *
 * <p>The connector's INSERT path iterates the <i>destination</i> column cache,
 * not these source fields, so any source column absent from the destination
 * cache is silently dropped. Surfacing the source columns lets the connector
 * detect that condition before it loses data.</p>
 */
public final class SourceSchemaColumns {

    private static final Logger log = LogManager.getLogger(SourceSchemaColumns.class);

    private SourceSchemaColumns() {
    }

    /**
     * Returns the distinct column names from a record's after-image (preferred)
     * or before-image. Order is preserved (insertion order) for readable logs.
     *
     * @param record the source change event; may be null.
     * @return list of source column names (possibly empty, never null).
     */
    public static List<String> fromRecord(ClickHouseStruct record) {
        Set<String> columns = new LinkedHashSet<>();
        if (record == null) {
            return new ArrayList<>(columns);
        }
        try {
            collect(record.getAfterStruct(), columns);
            // Fall back to / union with the before-image so DELETE-only or
            // before-only events still contribute their columns.
            collect(record.getBeforeStruct(), columns);
        } catch (Exception e) {
            log.warn("Error extracting source columns from record: {}", e.getMessage());
        }
        return new ArrayList<>(columns);
    }

    private static void collect(Struct struct, Set<String> out) {
        if (struct == null || struct.schema() == null) {
            return;
        }
        for (Field f : struct.schema().fields()) {
            if (f != null && f.name() != null) {
                out.add(f.name());
            }
        }
    }
}

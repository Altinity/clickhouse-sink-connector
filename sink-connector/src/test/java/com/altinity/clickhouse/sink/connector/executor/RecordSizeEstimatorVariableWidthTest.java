package com.altinity.clickhouse.sink.connector.executor;

import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import io.debezium.engine.ChangeEvent;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handoff byte cap is only as good as the estimate it is charged with
 * (spec 01.05 section 3.4 item 7, section 6 FM-01.05-2).
 *
 * <p>{@code RecordSizeEstimator.estimateGroup} walks the envelope of the FIRST
 * row of each table in a group and charges that size to every row of the
 * table, on the premise that "the rows of one table in one batch are alike in
 * width". Production tables do not honour it: a JSON/TEXT/BLOB column is empty
 * on most rows and megabytes on a few (an audit table, a document table, a
 * batch job that rewrites large payloads). When the sampled first row is narrow
 * and the rows behind it are wide, the cap is charged a few hundred bytes per
 * row while each row pins megabytes of heap: the byte cap (one quarter of the
 * heap) cannot see the heap filling, and the JVM goes into the back-to-back
 * full-GC spiral the cap exists to prevent.</p>
 */
public class RecordSizeEstimatorVariableWidthTest {

    private static final Schema ROW = SchemaBuilder.struct().name("row")
            .field("id", Schema.INT64_SCHEMA)
            .field("doc", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static final Schema ENVELOPE = SchemaBuilder.struct().name("envelope")
            .field("before", SchemaBuilder.struct().name("row").optional()
                    .field("id", Schema.INT64_SCHEMA)
                    .field("doc", Schema.OPTIONAL_STRING_SCHEMA)
                    .build())
            .field("after", ROW)
            .field("op", Schema.STRING_SCHEMA)
            .build();

    private static ClickHouseStruct insert(long id, String doc) {
        Struct after = new Struct(ROW).put("id", id).put("doc", doc);
        Struct envelope = new Struct(ENVELOPE).put("after", after).put("op", "c");
        SourceRecord source = new SourceRecord(Collections.emptyMap(), Collections.emptyMap(),
                "srv.db.documents", ENVELOPE, envelope);
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTopic("srv.db.documents");
        record.setSourceRecord(new ChangeEvent<SourceRecord, SourceRecord>() {
            @Override
            public SourceRecord key() {
                return null;
            }

            @Override
            public SourceRecord value() {
                return source;
            }

            @Override
            public String destination() {
                return "srv.db.documents";
            }

            @Override
            public Integer partition() {
                return null;
            }
        });
        return record;
    }

    private static String chars(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    @Test
    @Disabled("DEFECT FM-01.05-2: estimateGroup charges the first row's size to every row of the table, "
            + "so wide rows behind a narrow first row are under-charged by orders of magnitude and the "
            + "handoff byte cap cannot bound the heap")
    @DisplayName("Wide rows behind a narrow first row of the same table are charged at least their payload")
    public void wideRowsBehindANarrowFirstRowAreCharged() {
        List<ClickHouseStruct> group = new ArrayList<>();
        group.add(insert(1L, ""));                       // the sampled row: an empty document
        String megabyte = chars(1 << 19);                // 512 Ki chars = 1 MiB of UTF-16
        for (long id = 2; id <= 101; id++) {
            // 100 rows of 1 MiB each (one shared instance keeps the test small;
            // in production every row carries its own copy).
            group.add(insert(id, megabyte));
        }

        long charged = RecordSizeEstimator.estimateGroup(group);

        long payloadFloor = 100L * (1L << 20);
        assertTrue(charged >= payloadFloor,
                "100 rows each carrying a 1 MiB document pin at least 100 MiB of heap in production; "
                        + "the byte cap was "
                        + "charged " + charged + " bytes (~" + (charged >> 10) + " KiB)");
    }
}

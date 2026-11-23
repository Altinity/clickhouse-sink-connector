package com.altinity.clickhouse.sink.connector.model;

import com.altinity.clickhouse.sink.connector.common.SnowFlakeId;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-metadata parsing that must not abandon the remaining fields of a record
 * (spec 02.01 section 3.1), and the Kafka Connect version inputs (spec 02.01
 * section 3.1, paths 1 and 4).
 */
public class ClickHouseStructGtidSafetyTest {

    private static final long TS = 1_757_900_000_000L;

    private static final Schema SOURCE = SchemaBuilder.struct()
            .field("ts_ms", Schema.INT64_SCHEMA)
            .field("thread", Schema.OPTIONAL_INT32_SCHEMA)
            .field("gtid", Schema.OPTIONAL_STRING_SCHEMA)
            .field("lsn", Schema.OPTIONAL_INT64_SCHEMA)
            .field("db", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static ClickHouseStruct parse(Integer thread, String gtid) {
        Struct source = new Struct(SOURCE).put("ts_ms", TS).put("thread", thread).put("gtid", gtid)
                .put("lsn", 77L).put("db", "example");
        // The envelope map: no "thread" key of its own, as in every real record.
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("source", source);
        envelope.put("ts_ms", TS + 250);
        ClickHouseStruct record = new ClickHouseStruct();
        record.setAdditionalMetaData(envelope);
        return record;
    }

    @Test
    @DisplayName("source.thread is read from the source struct and does not abort GTID, LSN and database parsing")
    public void serverThreadDoesNotAbortTheRemainingFields() {
        ClickHouseStruct record = parse(4242, "3e11fa47-71ca-11e1-9e33-c80aa9429562:17");

        assertEquals(4242, record.getThread(), "the thread id comes from source.thread");
        assertEquals(17L, record.getGtid(), "pre-fix: NullPointerException on the envelope map left gtid unset");
        assertEquals(77L, record.getLsn(), "pre-fix: the LSN after the thread was never read");
        assertEquals("example", record.getDatabase(), "pre-fix: the database after the thread was never read");
        assertEquals(TS + 250, record.getDebezium_ts_ms());
    }

    @Test
    @DisplayName("a non-numeric GTID transaction number leaves gtid unset and the later fields parsed")
    public void malformedGtidDoesNotAbortTheRemainingFields() {
        ClickHouseStruct record = parse(1, "3e11fa47-71ca-11e1-9e33-c80aa9429562:not-a-number");

        assertEquals(-1L, record.getGtid(), "a malformed GTID is no GTID");
        assertEquals(77L, record.getLsn(), "pre-fix: NumberFormatException skipped the LSN");
        assertEquals("example", record.getDatabase(), "pre-fix: NumberFormatException skipped the database");
    }

    @Test
    @DisplayName("parseGtidTransaction: classic and tagged forms, and every malformed form yields -1")
    public void gtidTransactionNumberIsTheLastNumericSegment() {
        assertEquals(42L, ClickHouseStruct.parseGtidTransaction("3e11fa47-71ca-11e1-9e33-c80aa9429562:42"));
        assertEquals(42L, ClickHouseStruct.parseGtidTransaction("3e11fa47-71ca-11e1-9e33-c80aa9429562:tag_1:42"),
                "MySQL 8.4 tagged GTID uuid:tag:n");
        assertEquals(7L, ClickHouseStruct.parseGtidTransaction("uuid: 7 "), "surrounding blanks are ignored");
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction(null));
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction("0-1-5"), "MariaDB domain-server-seq");
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction("uuid:1-5"), "a GTID set range");
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction("uuid:tag"), "a tag without a number");
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction("uuid:"), "an empty number");
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction("uuid:-3"), "a sign is not a transaction number");
        assertEquals(-1L, ClickHouseStruct.parseGtidTransaction("uuid:99999999999999999999"), "overflow");
    }

    @Test
    @DisplayName("the record's position carries its GTID transaction number without changing its identity")
    public void sourcePositionCarriesTheGtid() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setFile("mysql-bin.000003");
        record.setPos(500L);
        record.setRow(1);
        SourcePosition anonymous = record.getSourcePosition();
        assertEquals(SourcePosition.NO_GTID, anonymous.gtidTransaction());

        record.setGtid(900L);
        SourcePosition withGtid = record.getSourcePosition();
        assertEquals(900L, withGtid.gtidTransaction());
        assertEquals(anonymous, withGtid, "the GTID is metadata, not part of the position");
        assertEquals(anonymous.hashCode(), withGtid.hashCode());
        assertSame(anonymous, anonymous.withGtidTransaction(-5L), "a negative number means no GTID");
    }

    @Test
    @DisplayName("an earlier-numbered file of the same log is recognised; an earlier position in the same file is not")
    public void earlierFileOfTheSameLog() {
        SourcePosition mark = SourcePosition.ofBinlog("mysql-bin.000500", 900L, 0);
        assertTrue(SourcePosition.ofBinlog("mysql-bin.000020", 4L, 0).inEarlierFileOfSameLog(mark));
        assertTrue(!SourcePosition.ofBinlog("mysql-bin.000500", 4L, 0).inEarlierFileOfSameLog(mark),
                "same file: an ordinary earlier position");
        assertTrue(!SourcePosition.ofBinlog("mysql-bin.000501", 4L, 0).inEarlierFileOfSameLog(mark));
        assertTrue(!SourcePosition.ofBinlog("binlog.000001", 4L, 0).inEarlierFileOfSameLog(mark),
                "a different basename is handled by sameLog");
    }

    @Test
    @DisplayName("Kafka Connect: the version timestamp is the later of statement time and envelope time")
    public void kafkaConnectVersionTimestamp() {
        ClickHouseStruct record = new ClickHouseStruct();
        record.setTs_ms(TS + 1_000);
        assertEquals(TS + 1_000, record.versionTimestamp(), "no envelope time: the statement time");
        record.setDebezium_ts_ms(TS + 10_000);
        assertEquals(TS + 10_000, record.versionTimestamp(), "the envelope time when later");
        record.setVersionTs(TS + 3);
        assertEquals(TS + 3, record.versionTimestamp(), "the sequence's floored timestamp wins when set");
    }

    @Test
    @DisplayName("Kafka Connect without GTID: the offset carries into the timestamp field instead of wrapping")
    public void kafkaConnectOffsetDoesNotWrap() {
        long previous = Long.MIN_VALUE;
        for (long offset : new long[] {0L, 300_023L, (1L << 22) - 1, 1L << 22, (1L << 22) + 1, 5_000_000_000L}) {
            ClickHouseStruct record = new ClickHouseStruct();
            record.setTs_ms(TS);
            record.setKafkaOffset(offset);
            record.calculateVersion(true);
            assertTrue(record.getVersion() > previous, "offset " + offset + " must rank above the previous one");
            if (offset < (1L << 22)) {
                assertEquals(SnowFlakeId.generate(TS, offset, false), record.getVersion(),
                        "below 2^22 the value is the unchanged snowflake");
            }
            previous = record.getVersion();
        }
    }

    @Test
    @DisplayName("Kafka Connect redelivery: the same record yields the same version")
    public void kafkaConnectVersionIsRedeliveryStable() {
        long[] versions = new long[2];
        for (int i = 0; i < 2; i++) {
            ClickHouseStruct record = new ClickHouseStruct();
            record.setTs_ms(TS);
            record.setDebezium_ts_ms(TS + 10_000);
            record.setGtid(501L);
            record.calculateVersion(true);
            versions[i] = record.getVersion();
        }
        assertEquals(versions[0], versions[1]);
        assertEquals(SnowFlakeId.generate(TS + 10_000, 501L, false), versions[0]);
    }
}

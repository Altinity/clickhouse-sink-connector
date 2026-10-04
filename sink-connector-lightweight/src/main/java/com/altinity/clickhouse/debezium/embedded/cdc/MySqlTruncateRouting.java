package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

/**
 * Keeps a MySQL {@code TRUNCATE TABLE} replicated the way it was under Debezium
 * 3.1.3 now that the connector embeds Debezium 3.3 (spec 01.11).
 *
 * <p><b>What changed upstream.</b> Debezium's binlog reader turns a
 * {@code TRUNCATE TABLE} query event into either a truncate change event
 * ({@code op = t}) or a schema-change event. In 3.1.3
 * ({@code BinlogStreamingChangeEventSource.handleQueryEvent}) it emitted the
 * truncate change event when {@code skipped.operations} did NOT contain
 * {@code t}, and the schema-change event otherwise. 3.3 emits the truncate
 * change event when {@code t} is not skipped and NOTHING otherwise. The
 * Debezium default -- what a configuration that leaves the property unset
 * gets -- is {@code t}, so under 3.3 a MySQL TRUNCATE would no longer reach
 * ClickHouse at all: the replica would keep every row the source removed, with
 * no error.</p>
 *
 * <p><b>What this class does.</b> For binlog connectors (MySQL, MariaDB) it
 * remembers whether the operator's effective {@code skipped.operations}
 * contained {@code t} and hands Debezium the same set WITHOUT {@code t}, so a
 * truncate always arrives as a change event. When the operator's set contained
 * {@code t}, the connector converts that event into the schema-change record
 * 3.1.3 would have produced ({@link #toSchemaChangeRecord}), and it flows
 * through the unchanged DDL path: the pre-DDL drain, the ignore rules,
 * {@code disable.drop.truncate}, the history-mode bulk close (spec 12.03) and
 * the primary-key-backfill cancellation (spec 06.09). When the operator chose a
 * set without {@code t} (for example {@code none}), the event stays on the
 * {@code op = t} row path of spec 04.05, exactly as under 3.1.3.</p>
 */
public final class MySqlTruncateRouting {

    private static final Logger log = LogManager.getLogger(MySqlTruncateRouting.class);

    /** Debezium's property. */
    static final String SKIPPED_OPERATIONS = "skipped.operations";
    /** Debezium's default value of {@link #SKIPPED_OPERATIONS} (CommonConnectorConfig). */
    static final String DEBEZIUM_DEFAULT = "t";
    static final String TRUNCATE_CODE = "t";
    /**
     * Connector-internal record of the operator's intent, written once so that an
     * engine rebuilt from the same (already rewritten) properties keeps it.
     * {@code ddl} or {@code row}.
     */
    static final String ROUTE_PROPERTY = "clickhouse.sink.internal.mysql.truncate.route";
    static final String ROUTE_DDL = "ddl";
    static final String ROUTE_ROW = "row";

    private MySqlTruncateRouting() {
    }

    /**
     * Rewrites {@code skipped.operations} for a binlog connector and records the
     * truncate route. Idempotent.
     *
     * @param props the connector properties the engine is built from (mutated).
     * @return {@code true} when MySQL truncate events must be converted to the
     *         DDL path, {@code false} otherwise (row path, or not a binlog
     *         connector).
     */
    public static boolean apply(Properties props) {
        if (!BinlogKeepAlivePreflight.isBinlogConnector(props)) {
            return false;
        }
        String route = props.getProperty(ROUTE_PROPERTY);
        if (route != null) {
            return ROUTE_DDL.equals(route);
        }
        String configured = props.getProperty(SKIPPED_OPERATIONS);
        List<String> operations = parse(configured == null ? DEBEZIUM_DEFAULT : configured);
        boolean viaDdl = operations.remove(TRUNCATE_CODE);
        String handedToDebezium = operations.isEmpty() ? "none" : String.join(",", operations);
        props.setProperty(SKIPPED_OPERATIONS, handedToDebezium);
        props.setProperty(ROUTE_PROPERTY, viaDdl ? ROUTE_DDL : ROUTE_ROW);
        if (viaDdl) {
            // DESTRUCTIVE: log text only -- names the route a TRUNCATE MySQL already
            // executed takes; nothing is executed here.
            log.info("skipped.operations={} ({}): a MySQL TRUNCATE TABLE is applied through the DDL path, "
                            + "as under Debezium 3.1.3. Debezium 3.3 drops the statement entirely when 't' is "
                            + "skipped, so the connector hands it skipped.operations={} and converts each "
                            + "truncate event into the schema-change record (spec 01.11).",
                    configured == null ? DEBEZIUM_DEFAULT : configured,
                    configured == null ? "Debezium default" : "configured", handedToDebezium);
        } else {
            // DESTRUCTIVE: log text only -- nothing is executed here.
            log.info("skipped.operations={}: a MySQL TRUNCATE TABLE is applied as a replicated truncate "
                    + "event (spec 04.05), as under Debezium 3.1.3 (spec 01.11).", configured);
        }
        return viaDdl;
    }

    /** Debezium's parsing: {@code none} means nothing; otherwise comma-separated codes. */
    static List<String> parse(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.trim().equalsIgnoreCase("none")) {
            return out;
        }
        for (String code : value.split(",")) {
            String c = code.trim();
            if (!c.isEmpty() && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Whether a record is a binlog truncate change event: an envelope with
     * {@code op = t} and a source naming the database and table.
     */
    public static boolean isTruncateEvent(SourceRecord sr) {
        if (sr == null || !(sr.value() instanceof Struct)) {
            return false;
        }
        Struct value = (Struct) sr.value();
        Schema schema = value.schema();
        if (schema == null || schema.field("op") == null || schema.field("source") == null) {
            return false;
        }
        if (!TRUNCATE_CODE.equals(value.get("op"))) {
            return false;
        }
        Object source = value.get("source");
        if (!(source instanceof Struct)) {
            return false;
        }
        return nonEmpty((Struct) source, "db") != null && nonEmpty((Struct) source, "table") != null;
    }

    /**
     * Builds the schema-change record Debezium 3.1.3 emitted for this truncate:
     * key {@code {databaseName}}, value {@code {source, ts_ms, databaseName,
     * schemaName, ddl, tableChanges}} with the statement
     * {@code TRUNCATE TABLE `<table>`} in the database of the event, and the same
     * source partition, offset and source block as the truncate event.
     */
    public static SourceRecord toSchemaChangeRecord(SourceRecord sr) {
        Struct value = (Struct) sr.value();
        Struct source = (Struct) value.get("source");
        String database = nonEmpty(source, "db");
        String table = nonEmpty(source, "table");
        Schema keySchema = SchemaBuilder.struct()
                .name("io.debezium.connector.mysql.SchemaChangeKey")
                .field("databaseName", Schema.STRING_SCHEMA)
                .build();
        Schema tableChangeSchema = SchemaBuilder.struct().optional().build();
        Schema valueSchema = SchemaBuilder.struct()
                .name("io.debezium.connector.mysql.SchemaChangeValue")
                .field("source", source.schema())
                .field("ts_ms", Schema.INT64_SCHEMA)
                .field("databaseName", Schema.OPTIONAL_STRING_SCHEMA)
                .field("schemaName", Schema.OPTIONAL_STRING_SCHEMA)
                .field("ddl", Schema.OPTIONAL_STRING_SCHEMA)
                .field("tableChanges", SchemaBuilder.array(tableChangeSchema).build())
                .build();
        Struct key = new Struct(keySchema).put("databaseName", database);
        Struct ddlValue = new Struct(valueSchema)
                .put("source", source)
                .put("ts_ms", eventTimeMs(value, source))
                .put("databaseName", database)
                .put("ddl", ddlFor(table))
                .put("tableChanges", Collections.emptyList());
        return new SourceRecord(sr.sourcePartition(), sr.sourceOffset(), sr.topic(), sr.kafkaPartition(),
                keySchema, key, valueSchema, ddlValue, sr.timestamp(), sr.headers());
    }

    /** The statement the DDL path translates; the database comes from the record key. */
    static String ddlFor(String table) {
        // DESTRUCTIVE: statement text only, built from the truncate event of a
        // TRUNCATE MySQL already executed on exactly this one table; it is handed
        // to the DDL path, which applies disable.drop.truncate, the ignore rules
        // and history mode before anything reaches ClickHouse (spec 01.11).
        return "TRUNCATE TABLE `" + table.replace("`", "``") + "`";
    }

    /**
     * The 3.1.3 schema-change record carried the binlog event time
     * ({@code SchemaChangeEvent.getTimestamp()}), which is {@code source.ts_ms};
     * the truncate envelope's own {@code ts_ms} is the connector's clock and is
     * only the fallback.
     */
    private static long eventTimeMs(Struct value, Struct source) {
        Field sourceTs = source.schema().field("ts_ms");
        if (sourceTs != null && source.get(sourceTs) instanceof Long) {
            return (Long) source.get(sourceTs);
        }
        Field envelopeTs = value.schema().field("ts_ms");
        if (envelopeTs != null && value.get(envelopeTs) instanceof Long) {
            return (Long) value.get(envelopeTs);
        }
        return System.currentTimeMillis();
    }

    private static String nonEmpty(Struct struct, String field) {
        if (struct.schema().field(field) == null) {
            return null;
        }
        Object v = struct.get(field);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }
}

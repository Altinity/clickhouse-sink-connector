/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mysql;

import com.altinity.clickhouse.debezium.embedded.cdc.BinlogConnectionGuard;
import com.altinity.clickhouse.debezium.embedded.cdc.BinlogEventAudit;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import io.debezium.connector.base.ChangeEventQueueMetrics;
import io.debezium.connector.binlog.metrics.BinlogStreamingChangeEventSourceMetrics;
import io.debezium.pipeline.metrics.CapturedTablesSupplier;
import io.debezium.pipeline.source.spi.EventMetadataProvider;

/**
 * The connector's copy of Debezium 3.7.0.Final's class of the same fully qualified name (specs 01.09, 01.10).
 *
 * <p>Identical to Debezium's -- a constructor that delegates to
 * {@link BinlogStreamingChangeEventSourceMetrics} -- plus two calls: it hands the task's binlog client,
 * which Debezium exposes to nothing else in the embedding application, to
 * {@link BinlogConnectionGuard#install}, which gives the client guarded sockets (a read timeout, and end
 * of stream as a failure) before it connects, and to {@link BinlogEventAudit#install}, which registers,
 * ahead of Debezium's own listener, a listener for the events Debezium drops without a signal. Debezium
 * constructs this class once per MySQL task ({@code MySqlConnectorTask.start}) with the
 * {@code BinaryLogClient} it then passes to {@code MySqlChangeEventSourceFactory}, i.e. the client the
 * streaming source configures and connects (3.1.3/3.3.2 handed it through
 * {@code taskContext.getBinaryLogClient()}; 3.7 passes it as a constructor argument).
 * The lightweight jar's shade plugin keeps this class and drops Debezium's; surefire puts
 * {@code target/classes} ahead of the dependency jars.</p>
 *
 * <p>Must track Debezium's class on every Debezium upgrade: if Debezium's constructor gains a
 * parameter, this copy stops matching and the connector fails at task start with a
 * {@code NoSuchMethodError} -- loudly, never silently.</p>
 */
public class MySqlStreamingChangeEventSourceMetrics
        extends BinlogStreamingChangeEventSourceMetrics<MySqlDatabaseSchema, MySqlPartition> {

    /** Read reflectively by {@link BinlogConnectionGuard#shadowMarker()}; Debezium's class has no such field. */
    public static final String BINLOG_CONNECTION_GUARD = BinlogConnectionGuard.MARKER;

    public MySqlStreamingChangeEventSourceMetrics(MySqlTaskContext taskContext,
                                                  ChangeEventQueueMetrics changeEventQueueMetrics,
                                                  EventMetadataProvider metadataProvider,
                                                  CapturedTablesSupplier capturedTablesSupplier,
                                                  BinaryLogClient client) {
        super(taskContext, changeEventQueueMetrics, metadataProvider, capturedTablesSupplier, client);
        BinlogConnectionGuard.install(client);
        BinlogEventAudit.install(client);
    }
}

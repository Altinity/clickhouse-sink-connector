package com.altinity.clickhouse.debezium.embedded.cdc;

/** Test access to the package-private self-test of {@link BinlogTransactionCompressionPreflight}. */
public final class BinlogTransactionCompressionPreflightAccess {

    private BinlogTransactionCompressionPreflightAccess() {
    }

    public static String streamingDecoderMarker() {
        return BinlogTransactionCompressionPreflight.streamingDecoderMarker();
    }

    public static boolean decoderSelfTest() {
        return BinlogTransactionCompressionPreflight.decoderSelfTest();
    }
}

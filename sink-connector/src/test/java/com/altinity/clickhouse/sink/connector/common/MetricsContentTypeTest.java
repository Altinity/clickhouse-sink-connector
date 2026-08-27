package com.altinity.clickhouse.sink.connector.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Regression tests for issue #1096: the {@code /metrics} endpoint served its
 * response with no {@code Content-Type} header.
 *
 * <p>Prometheus 3.x refuses to scrape such a target -- it fails the scrape with
 * {@code non-compliant scrape target sending blank Content-Type and no
 * fallback_scrape_protocol specified for target} -- so the connector silently
 * stopped being monitored after a Prometheus upgrade.
 *
 * <p>These tests start the real metrics HTTP server on a free port and scrape
 * it over real HTTP, because the header is only observable on the wire: the
 * handler is a lambda registered inside a private method, so nothing short of
 * an actual request proves what a scraper receives.
 */
public class MetricsContentTypeTest {

    /** The Prometheus text exposition format content type. */
    private static final String EXPECTED_CONTENT_TYPE =
            "text/plain; version=0.0.4; charset=utf-8";

    /** Captures one real scrape of the /metrics endpoint. */
    private static final class Scrape {
        private String contentType;
        private int contentLength;
        private byte[] body;
        private int status;
    }

    @AfterEach
    public void stopMetrics() {
        Metrics.stop();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Starts the metrics server and scrapes it exactly as Prometheus would.
     * The server is started on a background thread, so the connection is
     * retried briefly rather than assumed ready.
     */
    private static Scrape scrapeMetrics() throws Exception {
        int port = freePort();
        Metrics.initialize("true", String.valueOf(port));

        IOException last = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                HttpURLConnection connection = (HttpURLConnection)
                        new URL("http://127.0.0.1:" + port + "/metrics").openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(5000);

                Scrape scrape = new Scrape();
                scrape.status = connection.getResponseCode();
                scrape.contentType = connection.getHeaderField("Content-Type");
                scrape.contentLength = connection.getHeaderFieldInt("Content-Length", -1);

                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        buffer.write(chunk, 0, read);
                    }
                }
                scrape.body = buffer.toByteArray();
                connection.disconnect();
                return scrape;
            } catch (IOException e) {
                // The HttpServer is started asynchronously; retry until it binds.
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("metrics endpoint never became reachable", last);
    }

    /**
     * The defect: no Content-Type at all. Before the fix
     * {@code getHeaderField("Content-Type")} was null, which is precisely the
     * "blank Content-Type" Prometheus 3.x rejects.
     */
    @Test
    public void metricsEndpointDeclaresPrometheusContentType() throws Exception {
        Scrape scrape = scrapeMetrics();

        Assertions.assertEquals(200, scrape.status);
        Assertions.assertNotNull(scrape.contentType,
                "no Content-Type: Prometheus 3.x rejects this scrape as non-compliant");
        Assertions.assertEquals(EXPECTED_CONTENT_TYPE, scrape.contentType);
    }

    /**
     * The declared Content-Length must match the bytes actually written.
     *
     * <p>The handler used to call {@code response.getBytes()} twice -- once for
     * the length, once for the payload -- each time encoding with the platform
     * default charset. The current scrape output is ASCII, so this test also
     * passed before the fix: it does not reproduce a live failure, it pins the
     * invariant that the declared {@code charset=utf-8} now promises. Under a
     * non-UTF-8 default charset a non-ASCII metric label would make the two
     * encodings disagree and leave a scraper short of the promised
     * Content-Length.
     */
    @Test
    public void contentLengthMatchesUtf8BodyLength() throws Exception {
        Scrape scrape = scrapeMetrics();

        Assertions.assertEquals(scrape.body.length, scrape.contentLength,
                "declared Content-Length must equal the bytes written");
        Assertions.assertArrayEquals(
                new String(scrape.body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8),
                scrape.body,
                "body must be valid UTF-8, as the declared charset promises");
    }

    /** The endpoint must still return the metrics themselves, not just a header. */
    @Test
    public void metricsBodyIsStillExposed() throws Exception {
        Scrape scrape = scrapeMetrics();

        String body = new String(scrape.body, StandardCharsets.UTF_8);
        Assertions.assertTrue(body.contains("jvm_memory_used_bytes"),
                "expected JVM metrics in the scrape, got: "
                        + body.substring(0, Math.min(200, body.length())));
    }
}

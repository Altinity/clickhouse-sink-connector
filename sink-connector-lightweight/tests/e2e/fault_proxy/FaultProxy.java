import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A TCP proxy that injects network faults between the sink connector and its MySQL source, for the
 * chaos harness (tests/e2e/binlog_transaction_compression_chaos.sh, spec 01.08 section 6).
 *
 * <pre>java FaultProxy &lt;listenPort&gt; &lt;targetHost&gt; &lt;targetPort&gt; &lt;controlFile&gt;</pre>
 *
 * The control file holds one line, re-read every 100 ms:
 * <ul>
 *   <li>{@code pass} -- forward both ways (the default when the file is missing or empty).</li>
 *   <li>{@code reset <n>} -- once per distinct {@code n}: close every open connection pair with an RST
 *       on both sides (a link flap, a firewall flush, a server-side kill of the dump thread), then
 *       forward new connections.</li>
 *   <li>{@code partition} -- stop reading from both sides of every open pair and forward nothing, and
 *       hold new connections without forwarding. The peers see silence; a peer whose send buffer fills
 *       blocks in its write (as behind a real partition, where no ACKs return).</li>
 *   <li>{@code slow <bytesPerSecond>} -- forward, throttled server-to-client (a thin WAN link).</li>
 * </ul>
 * Leaving {@code partition} models a healed link faithfully: a pair whose server side is still open
 * resumes; a pair whose server side closed during the partition (MySQL aborts a dump connection it
 * could not write to for {@code net_write_timeout}) becomes a ZOMBIE -- its client side is kept open
 * and silent forever, because behind a real partition the server's FIN/RST never reached the client and
 * the client, which only reads, sends nothing that would provoke a new RST. Every state change is
 * logged on stdout with a timestamp.
 */
public final class FaultProxy {

    private static volatile String mode = "pass";
    private static volatile long throttle = 0;
    private static final List<Pair> PAIRS = new CopyOnWriteArrayList<>();

    private FaultProxy() {
    }

    static void log(String msg) {
        System.out.println("[" + LocalTime.now() + "] proxy: " + msg);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        int listenPort = Integer.parseInt(args[0]);
        String targetHost = args[1];
        int targetPort = Integer.parseInt(args[2]);
        Path control = Path.of(args[3]);
        Thread watcher = new Thread(() -> watch(control), "control");
        watcher.setDaemon(true);
        watcher.start();
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(listenPort));
            log("listening on " + listenPort + " -> " + targetHost + ":" + targetPort);
            while (true) {
                Socket client = server.accept();
                Thread t = new Thread(() -> open(client, targetHost, targetPort), "open");
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private static void watch(Path control) {
        String lastReset = "";
        while (true) {
            try {
                String line = Files.exists(control)
                        ? new String(Files.readAllBytes(control), StandardCharsets.UTF_8).trim() : "pass";
                if (line.isEmpty()) {
                    line = "pass";
                }
                String[] parts = line.split("\\s+");
                String next = parts[0];
                if (next.equals("reset")) {
                    String gen = parts.length > 1 ? parts[1] : "0";
                    if (!gen.equals(lastReset)) {
                        lastReset = gen;
                        log("RESET generation " + gen + ": resetting " + PAIRS.size() + " connection pair(s)");
                        for (Pair p : PAIRS) {
                            p.reset();
                        }
                    }
                    next = "pass";
                }
                if (next.equals("slow")) {
                    throttle = parts.length > 1 ? Long.parseLong(parts[1]) : 1_000_000L;
                } else {
                    throttle = 0;
                }
                if (!next.equals(mode)) {
                    String previous = mode;
                    mode = next;
                    log("mode " + previous + " -> " + next + (throttle > 0 ? " (" + throttle + " B/s)" : ""));
                    if (previous.equals("partition")) {
                        synchronized (FaultProxy.class) {
                            FaultProxy.class.notifyAll();
                        }
                    }
                }
                Thread.sleep(100);
            } catch (Exception e) {
                log("control error: " + e);
            }
        }
    }

    static void awaitNotPartitioned() throws InterruptedException {
        synchronized (FaultProxy.class) {
            while (mode.equals("partition")) {
                FaultProxy.class.wait(200);
            }
        }
    }

    private static void open(Socket client, String host, int port) {
        try {
            awaitNotPartitioned();
            Socket upstream = new Socket();
            upstream.connect(new InetSocketAddress(host, port), 10_000);
            Pair pair = new Pair(client, upstream);
            PAIRS.add(pair);
            log("connection " + pair.id + " opened (" + PAIRS.size() + " open)");
            pair.start();
        } catch (Exception e) {
            log("open failed: " + e);
            closeQuietly(client);
        }
    }

    static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closing a socket that is already gone
        }
    }

    static final class Pair {
        private static int seq = 0;
        final int id;
        final Socket client;
        final Socket upstream;
        volatile boolean zombie = false;
        volatile boolean closed = false;

        Pair(Socket client, Socket upstream) {
            synchronized (Pair.class) {
                this.id = ++seq;
            }
            this.client = client;
            this.upstream = upstream;
        }

        void start() {
            Thread up = new Thread(() -> pump(client, upstream, false), "c2s-" + id);
            Thread down = new Thread(() -> pump(upstream, client, true), "s2c-" + id);
            up.setDaemon(true);
            down.setDaemon(true);
            up.start();
            down.start();
        }

        void reset() {
            try {
                client.setSoLinger(true, 0);
                upstream.setSoLinger(true, 0);
            } catch (IOException ignored) {
                // the socket is already closed
            }
            close();
        }

        void close() {
            if (!closed) {
                closed = true;
                closeQuietly(client);
                closeQuietly(upstream);
                PAIRS.remove(this);
            }
        }

        private void pump(Socket from, Socket to, boolean serverToClient) {
            byte[] buf = new byte[65536];
            boolean sawPartition = false;
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                while (!closed) {
                    if (mode.equals("partition")) {
                        sawPartition = true;
                        awaitNotPartitioned();
                    }
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        n = -1;
                    }
                    if (n < 0) {
                        if (serverToClient && sawPartition) {
                            // The server gave up during the partition; its FIN/RST never reached the client.
                            zombie = true;
                            log("connection " + id + " is a ZOMBIE: server side closed during the partition, "
                                    + "client side kept open and silent");
                            closeQuietly(upstream);
                            return;
                        }
                        break;
                    }
                    if (zombie) {
                        return;
                    }
                    if (serverToClient && throttle > 0) {
                        long sleepMs = (n * 1000L) / throttle;
                        if (sleepMs > 0) {
                            Thread.sleep(sleepMs);
                        }
                    }
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (Exception e) {
                // fall through to close
            }
            if (!zombie) {
                log("connection " + id + " closed");
                close();
            }
        }
    }
}

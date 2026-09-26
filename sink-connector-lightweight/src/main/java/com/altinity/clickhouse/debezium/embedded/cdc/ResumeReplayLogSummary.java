package com.altinity.clickhouse.debezium.embedded.cdc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces Debezium's per-event "Skipping previously processed row event"
 * lines -- each carrying the full row image -- with one summary per resume:
 * the operation types and how many events of each were skipped (spec 01.07
 * §3.5).
 *
 * <p><b>The noise.</b> Every engine start resumes from the durable offset,
 * which Debezium records as the position of the transaction's BEGIN plus the
 * number of events already delivered. The binlog client re-reads the
 * transaction from BEGIN and Debezium skips the events it has already
 * delivered -- logging each one at INFO with its complete row image
 * ({@code Event{header=..., data=WriteRowsEventData{..., rows=[ ... ]}}},
 * some seventy lines per event). One deployment logged 5,227 such events at
 * one start: seven rotated 6 MB files in fourteen seconds, all of it row data
 * nobody reads and none of it an error. The operator's rule: do not print the
 * row data; print the operation type and the count of that operation.</p>
 *
 * <p><b>What this does.</b> A log4j filter installed on Debezium's
 * {@code BinlogStreamingChangeEventSource} logger DENIES those lines and
 * counts them by binlog event type. The count is reported as ONE INFO line
 * when the replay ends -- the next line from that logger that is not a skip,
 * i.e. the reader moved past the resume point -- and, for a long replay, one
 * progress line per minute. Nothing else from that logger is touched, and no
 * row image is ever written by this class.</p>
 *
 * <p>Installed programmatically at {@code setup()} so no deployment's
 * {@code log4j2.xml} needs to change; idempotent per process.</p>
 */
public final class ResumeReplayLogSummary extends AbstractFilter {

    private static final Logger log = LogManager.getLogger(ResumeReplayLogSummary.class);

    /** Debezium's binlog streaming source: the logger that emits the skip lines. */
    static final String DEBEZIUM_LOGGER = "io.debezium.connector.binlog.BinlogStreamingChangeEventSource";

    /** How every skipped-event line begins ("... row event: ..." or "... {kind} event: ..."). */
    static final String PREFIX = "Skipping previously processed ";

    /** A replay still running after this long gets a progress line, then another per interval. */
    static final long PROGRESS_INTERVAL_MS = 60_000L;

    private static final Pattern EVENT_TYPE = Pattern.compile("eventType=([A-Z_]+)");
    private static final Pattern NEXT_POSITION = Pattern.compile("nextPosition=(\\d+)");

    /** Millisecond clock; tests substitute one. */
    static volatile LongSupplier clock = System::currentTimeMillis;

    private static volatile ResumeReplayLogSummary installed;

    // Guarded by this.
    private final Map<String, Long> countsByOperation = new TreeMap<>();
    private long skipped;
    private long firstMillis;
    private long lastProgressMillis;
    private long firstPosition = -1L;
    private long lastPosition = -1L;

    ResumeReplayLogSummary() {
        super(Result.NEUTRAL, Result.NEUTRAL);
    }

    /**
     * Installs the filter on Debezium's binlog streaming logger, once per
     * process. Safe to call from every {@code setup()}.
     *
     * @return the installed instance.
     */
    public static synchronized ResumeReplayLogSummary install() {
        if (installed != null) {
            return installed;
        }
        ResumeReplayLogSummary filter = new ResumeReplayLogSummary();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Configuration configuration = context.getConfiguration();
        LoggerConfig existing = configuration.getLoggerConfig(DEBEZIUM_LOGGER);
        LoggerConfig target;
        if (DEBEZIUM_LOGGER.equals(existing.getName())) {
            target = existing;
        } else {
            // Inherit the level of whatever config covered the logger and keep
            // additivity, so nothing else about its output changes.
            target = new LoggerConfig(DEBEZIUM_LOGGER, existing.getLevel(), true);
            configuration.addLogger(DEBEZIUM_LOGGER, target);
        }
        target.addFilter(filter);
        context.updateLoggers();
        installed = filter;
        log.info("Resume replay summary installed: Debezium's per-event 'Skipping previously processed "
                + "... event' lines (each with a full row image) are counted by operation and reported as "
                + "one line per resume instead of being written (spec 01.07 section 3.5).");
        return filter;
    }

    /** The installed instance, or null before {@link #install()}. Package-private for tests. */
    static ResumeReplayLogSummary installed() {
        return installed;
    }

    /** Emits the pending summary, if any, of the installed instance -- called when an engine stops. */
    public static void flushInstalled(String why) {
        ResumeReplayLogSummary filter = installed;
        if (filter != null) {
            filter.flush(why);
        }
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null || !DEBEZIUM_LOGGER.equals(event.getLoggerName()) || event.getMessage() == null) {
            return Result.NEUTRAL;
        }
        String message = event.getMessage().getFormattedMessage();
        if (message != null && message.startsWith(PREFIX)) {
            record(message);
            return Result.DENY;
        }
        // Any other line from the streaming source means the reader is past
        // the resume point: the replay is over, report it.
        flush("the reader moved past the resume point");
        return Result.NEUTRAL;
    }

    /** Counts one skipped event. Package-private for tests. */
    synchronized void record(String message) {
        long now = clock.getAsLong();
        if (skipped == 0) {
            firstMillis = now;
            lastProgressMillis = now;
            countsByOperation.clear();
            firstPosition = -1L;
            lastPosition = -1L;
        }
        skipped++;
        countsByOperation.merge(operationOf(message), 1L, Long::sum);
        Matcher position = NEXT_POSITION.matcher(message);
        if (position.find()) {
            long next = Long.parseLong(position.group(1));
            if (firstPosition < 0) {
                firstPosition = next;
            }
            lastPosition = next;
        }
        if (now - lastProgressMillis >= PROGRESS_INTERVAL_MS) {
            log.info("Resume replay in progress: {} previously processed binlog event(s) skipped so far "
                    + "in {} s -- {}; the row images are not logged (spec 01.07 section 3.5).",
                    skipped, (now - firstMillis) / 1000L, describeCounts());
            lastProgressMillis = now;
        }
    }

    /** Reports and resets the pending counts; a no-op when nothing was skipped. */
    public synchronized void flush(String why) {
        if (skipped == 0) {
            return;
        }
        long now = clock.getAsLong();
        log.info("Resume replay done ({}): skipped {} previously processed binlog event(s) already "
                + "applied before this start, {} s, binlog positions {}..{} -- {}. Only the operation "
                + "types and counts are logged, never the row images (spec 01.07 section 3.5).",
                why, skipped, (now - firstMillis) / 1000L, firstPosition, lastPosition, describeCounts());
        skipped = 0;
        countsByOperation.clear();
        firstPosition = -1L;
        lastPosition = -1L;
    }

    /** Pending count of skipped events. Package-private for tests. */
    synchronized long skipped() {
        return skipped;
    }

    /** Pending counts by operation (a copy). Package-private for tests. */
    synchronized Map<String, Long> countsByOperation() {
        return new TreeMap<>(countsByOperation);
    }

    private synchronized String describeCounts() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> entry : countsByOperation.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.length() == 0 ? "no event type recognised" : sb.toString();
    }

    /**
     * The operation named by a skipped-event line: the binlog event type
     * mapped to the row operation it carries.
     */
    static String operationOf(String message) {
        Matcher type = EVENT_TYPE.matcher(message);
        if (!type.find()) {
            return "UNKNOWN";
        }
        String eventType = type.group(1);
        switch (eventType) {
            case "WRITE_ROWS":
            case "EXT_WRITE_ROWS":
                return "INSERT";
            case "UPDATE_ROWS":
            case "EXT_UPDATE_ROWS":
                return "UPDATE";
            case "DELETE_ROWS":
            case "EXT_DELETE_ROWS":
                return "DELETE";
            default:
                return eventType;
        }
    }
}

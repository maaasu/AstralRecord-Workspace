package io.github.maaasu.astralRecord.infrastructure.logging;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;

import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Async audit log queue.
 *
 * <p>CSV persistence has been removed. The queue is still consumed so the
 * integration point can later be replaced by DB persistence.</p>
 *
 * @param <E> log entry type
 */
public final class AuditLogger<E extends LogEntry> {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String DEFAULT_LOG_DIR = "logs";
    private static final String DEFAULT_CSV_HEADER = "Timestamp,Type,Details";
    private static final String DEFAULT_FILE_NAME_PREFIX = "audit";
    private static AuditLogger<DefaultLogEntry> defaultInstance;

    private final String logDir;
    private final String fileNamePrefix;

    private final ConcurrentLinkedQueue<E> logQueue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /**
     * Construct from a sample entry.
     *
     * @param sampleEntry sample entry instance
     */
    public AuditLogger(E sampleEntry) {
        this.logDir = sampleEntry.logDir();
        this.fileNamePrefix = sampleEntry.fileNamePrefix();
    }

    /**
     * Construct with direct config values.
     *
     * @param logDir logical output directory name
     * @param csvHeader unused compatibility parameter
     * @param fileNamePrefix logical file name prefix
     */
    public AuditLogger(String logDir, String csvHeader, String fileNamePrefix) {
        this.logDir = logDir;
        this.fileNamePrefix = fileNamePrefix;
    }

    /**
     * Initialize background worker.
     */
    public synchronized void init() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AuditLogger-Worker[" + logDir + "/" + fileNamePrefix + "]");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::flush, 100, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Shutdown worker and drain queued items as much as possible.
     */
    public synchronized void shutdown() {
        if (!running) return;
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    /**
     * Enqueue one entry.
     *
     * @param entry log entry
     */
    public void offer(E entry) {
        if (!entry.isEnabled()) return;
        logQueue.offer(entry);
    }

    private void flush() {
        if (!running && logQueue.isEmpty()) return;

        List<E> entries = new ArrayList<>();
        E entry;
        while ((entry = logQueue.poll()) != null) {
            entries.add(entry);
            if (entries.size() > 10000) break;
        }

        if (entries.isEmpty()) return;

        // CSV output removed.
        // TODO: Persist entries to DB once audit history repository is available.
        entries.clear();

        if (!logQueue.isEmpty()) {
            flush();
        }
    }

    /**
     * Initialize default audit logger.
     */
    public static synchronized void initDefault() {
        if (defaultInstance == null) {
            defaultInstance = new AuditLogger<>(DEFAULT_LOG_DIR, DEFAULT_CSV_HEADER, DEFAULT_FILE_NAME_PREFIX);
        }
        defaultInstance.init();
    }

    /**
     * Shutdown default audit logger.
     */
    public static synchronized void shutdownDefault() {
        if (defaultInstance != null) {
            defaultInstance.shutdown();
        }
    }

    static void consoleLog(String details) {
        customLog("CONSOLE", details);
    }

    /**
     * Enqueue system audit log.
     *
     * @param details details
     */
    public static void logSystem(String details) {
        customLog("SYSTEM", details);
    }

    /**
     * Enqueue error audit log.
     *
     * @param message message
     * @param t throwable
     */
    public static void logError(String message, Throwable t) {
        String details = (t != null) ? String.format("%s (Exception: %s)", message, t.getMessage()) : message;
        customLog("ERROR", details);
        if (t != null) {
            logStackTrace(t);
        }
    }

    /**
     * Enqueue custom audit log.
     *
     * @param type type
     * @param details details
     */
    public static void customLog(String type, String details) {
        if (!ConfigProperties.getInstance().isLoggingAuditEnabled()) {
            return;
        }
        if (defaultInstance == null) return;
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        defaultInstance.offer(new DefaultLogEntry(timestamp, type, details));
    }

    private static void logStackTrace(Throwable t) {
        if (!ConfigProperties.getInstance().isLoggingAuditEnabled()) {
            return;
        }
        if (defaultInstance == null) return;
        java.io.StringWriter sw = new java.io.StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        defaultInstance.offer(new DefaultLogEntry(timestamp, "STK", sw.toString()));
    }

    /**
     * Default audit entry shape.
     */
    @LogEntryMeta(
            logDir = "logs/audit",
            csvHeader = "Timestamp,Type,Details",
            fileNamePrefix = "audit"
    )
    public record DefaultLogEntry(String timestamp, String type, String details) implements LogEntry {
        @Override
        public String toCsvRow() {
            return String.format("\"%s\",\"%s\",\"%s\"",
                    escapeCsv(timestamp),
                    escapeCsv(type),
                    escapeCsv(details));
        }

        private static String escapeCsv(String value) {
            if (value == null) return "";
            return value.replace("\"", "\"\"");
        }
    }
}
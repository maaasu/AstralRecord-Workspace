package io.github.maaasu.astralRecord.infrastructure.logging;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
 * 監査ログをファイルに記録するための汎用クラスです。
 * <p>
 * {@link LogEntry} を実装した任意のエントリ型を指定することで、様々なクラスのログ記録に再利用できます。
 * 出力先ディレクトリ・CSVヘッダー・ファイル名プレフィックスはエントリ型の {@link LogEntry#logDir()}・
 * {@link LogEntry#csvHeader()}・{@link LogEntry#fileNamePrefix()} で定義します。
 * </p>
 * <p>
 * 非同期キューを使用して、高負荷時でもパフォーマンスを維持します。
 * </p>
 *
 * <h3>使用例（エントリ型に設定を持たせる場合）</h3>
 * <pre>{@code
 * // 独自エントリの定義（logDir・csvHeader・fileNamePrefix をオーバーライド）
 * record TradeEntry(String timestamp, String player, String item) implements LogEntry {
 *     public String toCsvRow() {
 *         return "\"" + timestamp + "\",\"" + player + "\",\"" + item + "\"";
 *     }
 *     public String logDir()        { return "logs/trade"; }
 *     public String csvHeader()     { return "Timestamp,Player,Item"; }
 *     public String fileNamePrefix(){ return "trade"; }
 * }
 *
 * // インスタンス生成（サンプルインスタンスを渡すだけ、内容は使われない）
 * AuditLogger<TradeEntry> logger = new AuditLogger<>(new TradeEntry("", "", ""));
 * logger.init();
 * logger.offer(new TradeEntry("2024-01-01 00:00:00", "Steve", "Diamond"));
 * }</pre>
 *
 * @param <E> ログエントリの型。{@link LogEntry} を実装している必要があります。
 */
public final class AuditLogger<E extends LogEntry> {

    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ---- デフォルトインスタンス（Logger クラスや既存コードからの後方互換用） ----
    private static final String DEFAULT_LOG_DIR = "logs";
    private static final String DEFAULT_CSV_HEADER = "Timestamp,Type,Details";
    private static final String DEFAULT_FILE_NAME_PREFIX = "audit";
    private static AuditLogger<DefaultLogEntry> defaultInstance;

    // ---- インスタンスフィールド ----
    /** ログファイルの出力先ディレクトリパス（プラグインのデータフォルダからの相対パス） */
    private final String logDir;
    /** CSVファイルの先頭に書き出すヘッダー行 */
    private final String csvHeader;
    /** CSVファイルのファイル名プレフィックス */
    private final String fileNamePrefix;

    private final ConcurrentLinkedQueue<E> logQueue = new ConcurrentLinkedQueue<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    /**
     * エントリのサンプルインスタンスから設定を取得して {@code AuditLogger} を生成します。
     * {@link LogEntry#logDir()}・{@link LogEntry#csvHeader()}・{@link LogEntry#fileNamePrefix()} の
     * デフォルト実装またはオーバーライドした値が使用されます。
     * <p>
     * {@code record} など引数付きコンストラクタしか持たないエントリ型でも使用できます。
     * </p>
     *
     * <pre>{@code
     * // record エントリの場合
     * AuditLogger<TradeEntry> logger = new AuditLogger<>(
     *         new TradeEntry("", "", ""));
     * }</pre>
     *
     * @param sampleEntry メタ情報取得用のサンプルインスタンス（内容は使用されません）
     */
    public AuditLogger(E sampleEntry) {
        this.logDir = sampleEntry.logDir();
        this.csvHeader = sampleEntry.csvHeader();
        this.fileNamePrefix = sampleEntry.fileNamePrefix();
    }

    /**
     * 出力先ディレクトリ・CSVヘッダー・ファイル名プレフィックスを直接指定して {@code AuditLogger} を生成します。
     * エントリ型にデフォルトコンストラクタがない場合や、エントリ型と異なる設定を使いたい場合に使用します。
     *
     * @param logDir         ログファイルの出力先ディレクトリパス（プラグインのデータフォルダからの相対パス）
     * @param csvHeader      CSVファイルの先頭に書き出すヘッダー行
     * @param fileNamePrefix CSVファイルのファイル名プレフィックス（実際のファイル名は {@code "<prefix>-yyyy-MM-dd.csv"}）
     */
    public AuditLogger(String logDir, String csvHeader, String fileNamePrefix) {
        this.logDir = logDir;
        this.csvHeader = csvHeader;
        this.fileNamePrefix = fileNamePrefix;
    }

    // =========================================================================
    // ライフサイクル
    // =========================================================================

    /**
     * ロガーを初期化し、バックグラウンドでの書き込みを開始します。
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
     * ロガーを終了し、残っているログをすべて書き出します。
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
        flush(); // 残っているログを書き出し
    }

    // =========================================================================
    // ログ追加
    // =========================================================================

    /**
     * エントリをキューに追加します。
     *
     * @param entry 追加するログエントリ
     */
    public void offer(E entry) {
        if (!entry.isEnabled()) return;
        logQueue.offer(entry);
    }

    // =========================================================================
    // フラッシュ（内部）
    // =========================================================================

    private void flush() {
        if (!running && logQueue.isEmpty()) return;

        List<E> entries = new ArrayList<>();
        E entry;
        while ((entry = logQueue.poll()) != null) {
            entries.add(entry);
            if (entries.size() > 10000) break;
        }

        if (entries.isEmpty()) return;

        File dataFolder = AstralRecord.getInstance().getDataFolder();
        File logDirFile = new File(dataFolder, logDir);
        if (!logDirFile.exists() && !logDirFile.mkdirs()) {
            return; // ログディレクトリ作成失敗
        }

        String fileName = String.format("%s-%s.csv", fileNamePrefix, LocalDateTime.now().format(FILE_DATE_FORMATTER));
        File logFile = new File(logDirFile, fileName);
        boolean isNewFile = !logFile.exists();

        try (FileWriter fw = new FileWriter(logFile, true);
             BufferedWriter bw = new BufferedWriter(fw);
             PrintWriter pw = new PrintWriter(bw)) {

            if (isNewFile) {
                pw.println(csvHeader);
            }

            for (E e : entries) {
                pw.println(e.toCsvRow());
            }
            pw.flush();

        } catch (IOException e) {
            // ここでのエラーは Logger 経由で出すと無限ループになる可能性があるため注意
            System.err.println("監査ログバッチの書き込みに失敗しました: " + e.getMessage());
        }

        // まだキューに残っている場合は再度呼び出し
        if (!logQueue.isEmpty()) {
            flush();
        }
    }

    // =========================================================================
    // 静的ユーティリティ（後方互換 / Logger クラスからの利用）
    // =========================================================================

    /**
     * デフォルトインスタンスを初期化します。
     * {@link io.github.maaasu.astralRecord.AstralRecord} の起動時に呼び出してください。
     */
    public static synchronized void initDefault() {
        if (defaultInstance == null) {
            defaultInstance = new AuditLogger<>(DEFAULT_LOG_DIR, DEFAULT_CSV_HEADER, DEFAULT_FILE_NAME_PREFIX);
        }
        defaultInstance.init();
    }

    /**
     * デフォルトインスタンスを終了します。
     */
    public static synchronized void shutdownDefault() {
        if (defaultInstance != null) {
            defaultInstance.shutdown();
        }
    }

    /**
     * ログを記録します（コンソール出力用）。
     */
    static void consoleLog(String details) {
        customLog("CONSOLE", details);
    }

    /**
     * システムイベントを記録します。
     *
     * @param details 詳細情報
     */
    public static void logSystem(String details) {
        customLog("SYSTEM", details);
    }

    /**
     * エラーを記録します。
     *
     * @param message エラーメッセージ
     * @param t       発生した例外
     */
    public static void logError(String message, Throwable t) {
        String details = (t != null) ? String.format("%s (Exception: %s)", message, t.getMessage()) : message;
        customLog("ERROR", details);
        if (t != null) {
            logStackTrace(t);
        }
    }

    /**
     * 任意のログを記録します。
     *
     * @param type    種類
     * @param details 詳細情報
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

    // =========================================================================
    // デフォルトエントリ（Timestamp, Type, Details の3カラム）
    // =========================================================================

    /**
     * デフォルトの監査ログエントリです。
     * Timestamp・Type・Details の3カラムをCSV形式で出力します。
     *
     * @param timestamp タイムスタンプ
     * @param type      ログ種別
     * @param details   詳細情報
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

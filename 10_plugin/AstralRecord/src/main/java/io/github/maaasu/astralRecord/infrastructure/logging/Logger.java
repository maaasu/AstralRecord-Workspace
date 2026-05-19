package io.github.maaasu.astralRecord.infrastructure.logging;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.infrastructure.logging.Entry.ConsoleLogEntry;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

public final class Logger {
    // ANSIエスケープコード（色設定用）
    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_RED    = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_CYAN   = "\u001B[96m";
    private static final String ANSI_GRAY   = "\u001B[90m";

    // Prefix
    private static final String INFO_PREFIX  = "[INFO] ";
    private static final String WARN_PREFIX  = "[WARN] ";
    private static final String ERROR_PREFIX = "[ERROR] ";
    private static final String DEBUG_PREFIX = "[DEBUG] ";

    /**
     * Crafty Controller モード用: jansi を完全に回避し、FileDescriptor.out へ直接 UTF-8 で書き込む
     * static フィールドとして保持することでクラスロード時に一度だけ生成する
     */
    private static final PrintStream DIRECT_UTF8_OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter AUDIT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Logger() {
        // utility
    }


    public static void info(String message, Object... args) {
        log(Level.INFO, INFO_PREFIX + message, null, args);
    }

    public static void warn(String message, Object... args) {
        log(Level.WARNING, WARN_PREFIX + message, null, args);
    }

    public static void error(String message, Throwable t, Object... args) {
        log(Level.SEVERE, ERROR_PREFIX + message, t, args);
    }

    public static void debug(String message, Object... args) {
        if (ConfigProperties.getInstance().isPluginDebugMode()) {
            log(Level.INFO, DEBUG_PREFIX + message, null, args);
        }
    }

    public static void info(LogId logId, Object... args) {
        log(Level.INFO, INFO_PREFIX + LogMessageProvider.format(logId.getId(), args), null);
    }

    public static void warn(LogId logId, Object... args) {
        log(Level.WARNING, WARN_PREFIX + LogMessageProvider.format(logId.getId(), args), null);
    }

    public static void error(LogId logId, Throwable t, Object... args) {
        log(Level.SEVERE, ERROR_PREFIX + LogMessageProvider.format(logId.getId(), args), t);
    }

    public static void debug(LogId logId, Object... args) {
        if (ConfigProperties.getInstance().isPluginDebugMode()) {
            log(Level.INFO, DEBUG_PREFIX + LogMessageProvider.format(logId.getId(), args), null);
        }
    }

    /**
     * LogIdに基づいて自動的に適切なレベルでログを出力します。
     *
     * @param logId ログID
     * @param args フォーマット引数
     */
    public static void log(LogId logId, Object... args) {
        String id = logId.getId();
        if (id.startsWith("I_")) {
            info(logId, args);
        } else if (id.startsWith("W_")) {
            warn(logId, args);
        } else if (id.startsWith("E_")) {
            error(logId, null, args);
        } else if (id.startsWith("D_")) {
            debug(logId, args);
        } else {
            info(logId, args);
        }
    }

    /**
     * LogIdに基づいて自動的に適切なレベルでログを出力します。
     *
     * @param logId ログID
     * @param t 例外
     * @param args フォーマット引数
     */
    public static void log(LogId logId, Throwable t, Object... args) {
        if (t == null) {
            log(logId, args);
            return;
        }
        String id = logId.getId();
        if (id.startsWith("E_")) {
            error(logId, t, args);
        } else {
            // エラー以外で例外が渡された場合も一応出力
            log(Level.SEVERE, ERROR_PREFIX + LogMessageProvider.format(logId.getId(), args), t);
        }
    }

    private static void log(Level level, String message, Throwable t, Object... args) {
        String formatted = safeFormat(message, args);

        ConfigProperties config = ConfigProperties.getInstance();

        if (config.isLoggingCraftyControllerColors()) {
            // ── Crafty Controller モード ──────────────────────────────────────────
            // jansi (Paper on Windows) が System.out を横取りして Windows Console API 経由で
            // CP932 に変換するため日本語が文字化けする。
            // FileDescriptor.out へ直接 UTF-8 で書き込むことで jansi を完全に回避する。
            String coloredMessage = buildAnsiColor(level, formatted);
            String timestamp = LocalDateTime.now().format(TIME_FMT);
            String levelName = levelLabel(level);
            DIRECT_UTF8_OUT.printf("[%s %s]: [AstralRecord] %s%n", timestamp, levelName, coloredMessage);
            if (t != null) {
                t.printStackTrace(DIRECT_UTF8_OUT);
            }
        } else {
            // ── 通常モード (標準ターミナル / useAnsiColors 設定に従う) ──────────
            java.util.logging.Logger utilLogger = AstralRecord.getInstance().getLogger();
            String coloredMessage = buildAnsiColor(level, formatted);
            if (t == null)
                utilLogger.log(level, coloredMessage);
            else
                utilLogger.log(level, coloredMessage, t);
        }

        // AuditLogger (DB) は常に実行
        AuditLogger<ConsoleLogEntry> auditLogger = AuditLoggerRegistry.getLogger(ConsoleLogEntry.class);
        if (auditLogger != null) {
            String timestamp = LocalDateTime.now().format(AUDIT_FMT);
            auditLogger.offer(new ConsoleLogEntry(timestamp, level.getName(), formatted));
        }
    }

    /**
     * 設定に応じて ANSI カラーコードを付与します。
     * craftyControllerColors: true の場合は useAnsiColors に関わらず常にカラーを付与します。
     */
    private static String buildAnsiColor(Level level, String message) {
        ConfigProperties config = ConfigProperties.getInstance();
        boolean useAnsi  = config.isLoggingUseAnsiColors();
        boolean craftyMode = config.isLoggingCraftyControllerColors();

        if (!useAnsi && !craftyMode) {
            return message;
        }

        String colorCode;
        if (level == Level.SEVERE) {
            colorCode = ANSI_RED;
        } else if (level == Level.WARNING) {
            colorCode = ANSI_YELLOW;
        } else if (level == Level.FINE || level == Level.FINER || level == Level.FINEST) {
            colorCode = ANSI_GRAY;
        } else if (message.startsWith(DEBUG_PREFIX)) {
            colorCode = ANSI_GRAY;
        } else {
            colorCode = ANSI_CYAN;
        }
        return colorCode + message + ANSI_RESET;
    }

    /** Paper のログレベル表示に合わせたラベルを返します。 */
    private static String levelLabel(Level level) {
        if (level == Level.SEVERE)  return "ERROR";
        if (level == Level.WARNING) return "WARN";
        return "INFO";
    }

    private static String safeFormat(String pattern, Object... args) {
        try {
            return (args == null || args.length == 0) ? pattern : String.format(pattern, args);
        } catch (Exception e) {
            // フォーマット失敗時は生メッセージと例外情報を返す
            return pattern + " (format-error: " + e.getMessage() + ")";
        }
    }
}

package io.github.maaasu.astralRecord.infrastructure.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link LogEntry} 実装クラスに付与するメタ情報アノテーションです。
 * <p>
 * {@code record} のようにデフォルトコンストラクタを持たないエントリ型でも、
 * このアノテーションを使用することで {@link AuditLoggerRegistry} が
 * {@link AuditLogger} を自動生成・初期化できます。
 * </p>
 *
 * <pre>{@code
 * @LogEntryMeta(
 *     logDir        = "logs/console",
 *     csvHeader     = "Timestamp,Prefix,Log",
 *     fileNamePrefix = "console"
 * )
 * public record ConsoleLogEntry(String timestamp, String prefix, String log)
 *         implements LogEntry { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogEntryMeta {

    /**
     * ログファイルの出力先ディレクトリパス（プラグインのデータフォルダからの相対パス）。
     * <p>デフォルトは {@code "logs"} です。</p>
     */
    String logDir() default "logs";

    /**
     * CSVファイルの先頭に書き出すヘッダー行。
     * <p>デフォルトは {@code "Timestamp,Type,Details"} です。</p>
     */
    String csvHeader() default "Timestamp,Type,Details";

    /**
     * CSVファイルのファイル名プレフィックス。
     * <p>実際のファイル名は {@code "<prefix>-yyyy-MM-dd.csv"} の形式になります。</p>
     * <p>デフォルトは {@code "audit"} です。</p>
     */
    String fileNamePrefix() default "audit";
}

package io.github.maaasu.astralRecord.infrastructure.logging;

import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;

/**
 * 監査ログの1エントリを表すインターフェースです。
 * このインターフェースを実装することで、任意のクラスが {@link AuditLogger} を使用して
 * 独自のフィールドを持つログをファイルに記録できます。
 * <p>
 * {@link #logDir()}・{@link #csvHeader()}・{@link #fileNamePrefix()} をオーバーライドすることで、
 * 出力先ディレクトリ・CSVヘッダー・ファイル名プレフィックスをエントリ型ごとに定義できます。
 * これらをオーバーライドした場合、{@link AuditLogger} のコンストラクタへの指定は不要です。
 * </p>
 */
public interface LogEntry {

    /**
     * このエントリをCSV行として返します。
     * 各フィールドはカンマ区切りで連結してください。
     * {@link AuditLogger} はこの値をそのままファイルに書き出します。
     *
     * @return CSV形式の1行文字列
     */
    String toCsvRow();

    /**
     * ログファイルの出力先ディレクトリパスを返します（プラグインのデータフォルダからの相対パス）。
     * <p>デフォルトは {@code "logs"} です。エントリ型ごとに変更する場合はオーバーライドしてください。</p>
     *
     * @return ログディレクトリパス
     */
    default String logDir() {
        return "logs";
    }

    /**
     * CSVファイルの先頭に書き出すヘッダー行を返します。
     * <p>デフォルトは {@code "Timestamp,Type,Details"} です。エントリ型ごとに変更する場合はオーバーライドしてください。</p>
     *
     * @return CSVヘッダー行
     */
    default String csvHeader() {
        return "Timestamp,Type,Details";
    }

    /**
     * 出力するCSVファイルのファイル名プレフィックスを返します。
     * <p>実際のファイル名は {@code "<prefix>-yyyy-MM-dd.csv"} の形式になります。</p>
     * <p>デフォルトは {@code "audit"} です。エントリ型ごとに変更する場合はオーバーライドしてください。</p>
     *
     * @return ファイル名プレフィックス
     */
    default String fileNamePrefix() {
        return "audit";
    }

    /**
     * Configの設定値を元に、このエントリ型の出力可否を返します。
     * <p>
     * {@code true} が返された場合、このエントリ型のログ出力は有効です。
     * </p>
     *
     * @return 出力可否
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * config.yml でこのエントリ型の出力可否を制御するキーを返します。
     * <p>
     * {@code null}（デフォルト）の場合は常に出力されます。<br>
     * エントリ型ごとに出力可否を設定したい場合は、対応する config.yml のキー文字列をオーバーライドして返してください。
     * </p>
     * <p>例：{@code "logging.entry.ConsoleLogEntry"}</p>
     *
     * @return config.yml のキー文字列、または {@code null}（常に有効）
     */
    default String configKey() {
        return null;
    }
}

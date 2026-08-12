package io.github.maaasu.astralRecord.infrastructure.util;

import java.text.MessageFormat;

/**
 * メッセージのフォーマット処理を提供するユーティリティクラス。
 */
public final class MessageFormatUtil {

    private MessageFormatUtil() {
        // utility class
    }

    /**
     * メッセージに引数を適用してフォーマットします。
     * 引数が {@code null} または空の場合はメッセージをそのまま返します。
     * <p>
     * メッセージに {0} 形式のプレースホルダーが含まれている場合は MessageFormat を使用し、
     * %s 形式のプレースホルダーが含まれている場合は String.format を使用します。
     *
     * @param message フォーマット対象のメッセージ文字列
     * @param args    フォーマット引数
     * @return フォーマットされたメッセージ文字列
     */
    public static String format(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }
        try {
            // {0}, {1}, ... 形式のプレースホルダーが含まれている場合は MessageFormat を使用
            if (message.matches("(?s).*\\{[0-9]+}.*")) {
                return MessageFormat.format(message, args);
            }
            // それ以外の場合は String.format を使用（%s など）
            return String.format(message, args);
        } catch (Exception e) {
            return message + " (format-error: " + e.getMessage() + ")";
        }
    }
}

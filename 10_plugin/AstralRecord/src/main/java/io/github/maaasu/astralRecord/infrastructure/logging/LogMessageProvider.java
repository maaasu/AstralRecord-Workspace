package io.github.maaasu.astralRecord.infrastructure.logging;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MessageFormatUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * リソースバンドルからメッセージを取得するためのユーティリティクラス。
 * logger.properties は UTF-8 エンコーディングで読み込みます。
 */
public class LogMessageProvider {
    private static final String BUNDLE_NAME = "logger";
    private static ResourceBundle resourceBundle;

    /** UTF-8 で .properties を読み込む ResourceBundle.Control */
    private static final ResourceBundle.Control UTF8_CONTROL = new ResourceBundle.Control() {
        @Override
        @SuppressWarnings("RedundantThrows")
        public ResourceBundle newBundle(String baseName, Locale locale, String format,
                                        ClassLoader loader, boolean reload)
                throws IllegalAccessException, InstantiationException, IOException {
            String bundleName = toBundleName(baseName, locale);
            String resourceName = toResourceName(bundleName, "properties");
            try (InputStream is = loader.getResourceAsStream(resourceName)) {
                if (is == null) return null;
                return new PropertyResourceBundle(new InputStreamReader(is, StandardCharsets.UTF_8));
            }
        }
    };

    static {
        loadBundle(Locale.getDefault());
    }

    /**
     * 指定されたロケールでリソースバンドルをロードします。
     * @param locale ロケール
     */
    public static void loadBundle(Locale locale) {
        try {
            resourceBundle = ResourceBundle.getBundle(BUNDLE_NAME, locale, UTF8_CONTROL);
        } catch (MissingResourceException e) {
            resourceBundle = ResourceBundle.getBundle(BUNDLE_NAME, Locale.ENGLISH, UTF8_CONTROL);
        }
    }

    /**
     * キーに対応するメッセージを取得します。
     * カラーコード（&）は自動的に変換されます。
     *
     * @param key メッセージキー
     * @return メッセージ文字列（見つからない場合はキー自体）
     */
    public static String getMessage(String key) {
        try {
            String message = resourceBundle.getString(key);
            return ColorCodeUtil.translateAlternateColorCodes(message);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /**
     * キーに対応するメッセージを取得し、引数を適用してフォーマットします。
     *
     * @param key メッセージキー
     * @param args フォーマット引数
     * @return フォーマットされたメッセージ文字列
     */
    public static String format(String key, Object... args) {
        return MessageFormatUtil.format(getMessage(key), args);
    }
}

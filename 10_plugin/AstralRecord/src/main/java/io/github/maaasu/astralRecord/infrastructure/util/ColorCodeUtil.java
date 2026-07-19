package io.github.maaasu.astralRecord.infrastructure.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class ColorCodeUtil {
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private ColorCodeUtil() {
        // utility class
    }

    // 基本カラー
    public static final String BLACK = "§0";
    public static final String DARK_BLUE = "§1";
    public static final String DARK_GREEN = "§2";
    public static final String DARK_AQUA = "§3";
    public static final String DARK_RED = "§4";
    public static final String DARK_PURPLE = "§5";
    public static final String GOLD = "§6";
    public static final String GRAY = "§7";
    public static final String DARK_GRAY = "§8";
    public static final String BLUE = "§9";
    public static final String GREEN = "§a";
    public static final String AQUA = "§b";
    public static final String RED = "§c";
    public static final String LIGHT_PURPLE = "§d";
    public static final String YELLOW = "§e";
    public static final String WHITE = "§f";

    // フォーマット
    public static final String OBFUSCATED = "§k";
    public static final String BOLD = "§l";
    public static final String STRIKETHROUGH = "§m";
    public static final String UNDERLINE = "§n";
    public static final String ITALIC = "§o";
    public static final String RESET = "§r";

    /**
     * 指定されたメッセージにカラーコードを適用します。
     *
     * @param colorCode カラーコード (例: ColorCodeUtil.RED)
     * @param message   メッセージ
     * @return カラーコードが適用されたメッセージ
     */
    @Contract(pure = true)
    public static @NonNull String colorize(String colorCode, String message) {
        return colorCode + message + RESET;
    }

    /**
     * & 記号を § に変換します。
     * 設定ファイルなどで &c のような形式を使用している場合に便利です。
     *
     * @param text 変換するテキスト
     * @return § に変換されたテキスト
     */
    public static String translateAlternateColorCodes(String text) {
        if (text == null) {
            return null;
        }
        return text.replace('&', '§');
    }

    /**
     * マスタデータ由来の表示文字列を legacy color 適用済み文字列へ変換します。
     *
     * @param text     表示文字列
     * @param fallback 未設定時の代替表示
     * @return legacy color 適用済み表示文字列
     */
    public static @NonNull String toLegacyText(String text, String fallback) {
        String source = text == null || text.isBlank() ? fallback : text;
        if (source == null) {
            return "";
        }
        String translated = translateAlternateColorCodes(source);
        return translated == null ? "" : translated;
    }

    /**
     * ファイルベースの表示文字列を legacy カラーコード対応の Component に変換します。
     *
     * @param text          表示文字列
     * @param fallback      未設定時の代替表示文字列
     * @param fallbackColor カラーコード未指定部分に適用する色。未指定の場合は {@code null}
     * @return カラーコードを反映した Component
     */
    public static @NonNull Component toComponent(String text, String fallback, @Nullable TextColor fallbackColor) {
        Component component = LEGACY_SERIALIZER.deserialize(toLegacyText(text, fallback));
        return fallbackColor == null ? component : component.colorIfAbsent(fallbackColor);
    }

    /**
     * ファイルベースの表示文字列を legacy カラーコード対応の Component に変換します。
     *
     * @param text     表示文字列
     * @param fallback 未設定時の代替表示文字列
     * @return カラーコードを反映した Component
     */
    public static @NonNull Component toComponent(String text, String fallback) {
        return LEGACY_SERIALIZER.deserialize(toLegacyText(text, fallback));
    }

    /**
     * マスタデータ由来の表示文字列をカラーなしのプレーン文字列へ変換します。
     *
     * @param text     表示文字列
     * @param fallback 未設定時の代替表示
     * @return カラーコードを除去した表示文字列
     */
    public static @NonNull String toPlainText(String text, String fallback) {
        String stripped = stripColor(toLegacyText(text, fallback));
        if (stripped == null || stripped.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return stripped;
    }

    /**
     * カラーコードを除去します。
     *
     * @param text カラーコードを含むテキスト
     * @return カラーコードが除去されたテキスト
     */
    public static String stripColor(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?i)§[0-9A-FK-OR]", "");
    }
}

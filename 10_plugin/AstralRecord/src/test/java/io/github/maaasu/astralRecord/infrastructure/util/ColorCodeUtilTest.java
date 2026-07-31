package io.github.maaasu.astralRecord.infrastructure.util;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ColorCodeUtilTest {

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Legacy color code 正規化
     * 検証契約: & codeを§相当Componentへ変換し色未指定部分だけfallback colorを適用する。
     */
    @Test
    void toComponentTranslatesLegacyColorCodesAndAppliesFallbackColor() {
        var component = ColorCodeUtil.toComponent("&c警告 &f詳細", "fallback", NamedTextColor.GRAY);

        assertEquals("§c警告 §f詳細", LegacyComponentSerializer.legacySection().serialize(component));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Legacy color code 正規化
     * 検証契約: blank sourceをfallback textへ置換する。
     */
    @Test
    void toComponentUsesFallbackWhenTheSourceIsBlank() {
        var component = ColorCodeUtil.toComponent(" ", "&a代替", NamedTextColor.GRAY);

        assertEquals("§a代替", LegacyComponentSerializer.legacySection().serialize(component));
    }

    /**
     * 設計入力: PLUGIN_GUIDE.md
     * 章・見出し: # AstralRecord Plugin > ## 共通基盤の設定スナップショットと入力正規化 > ### Legacy color code 正規化
     * 検証契約: sourceにcolor codeがない場合component全体へfallback colorを適用する。
     */
    @Test
    void toComponentAppliesFallbackColorWhenTheSourceHasNoColorCode() {
        var component = ColorCodeUtil.toComponent("通常表示", "fallback", NamedTextColor.GRAY);

        assertEquals("§7通常表示", LegacyComponentSerializer.legacySection().serialize(component));
    }
}

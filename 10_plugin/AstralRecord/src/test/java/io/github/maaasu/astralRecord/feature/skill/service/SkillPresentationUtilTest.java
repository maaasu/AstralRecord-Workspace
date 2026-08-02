package io.github.maaasu.astralRecord.feature.skill.service;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SkillPresentationUtilTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 6. マスター表示の共通化
     * 検証契約: GUI向けのマスター表示は ColorCodeUtil 経由で & カラーコードを Component 化し、生の & を残さない。
     */
    @Test
    void masterTextComponentNormalizesLegacyAmpersandColors() {
        String legacy = LegacyComponentSerializer.legacySection().serialize(
            SkillPresentationUtil.masterTextComponent("&d追尾火焔のシジル", "fallback", NamedTextColor.WHITE)
        );

        assertEquals("§d追尾火焔のシジル", legacy);
        assertFalse(legacy.contains("&"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: スキルマスターのタグIDはスキルマネージャーで日本語名へ変換する。
     */
    @Test
    void skillTagsUseJapaneseDisplayNames() {
        assertEquals("アクティブ", SkillPresentationUtil.skillTagDisplayName("active"));
        assertEquals("雷", SkillPresentationUtil.skillTagDisplayName("LIGHTNING"));
        assertEquals("その他", SkillPresentationUtil.skillTagDisplayName("unknown_tag"));
    }
}

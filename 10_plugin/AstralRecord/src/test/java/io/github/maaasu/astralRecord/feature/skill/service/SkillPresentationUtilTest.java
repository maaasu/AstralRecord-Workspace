package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * 検証契約: スキルマスターのタグIDは、カタログに従い日本語へ変換する。
     */
    @Test
    void skillTagsUseJapaneseDisplayNames() {
        assertEquals("アクティブ", SkillPresentationUtil.skillTagDisplayName("active"));
        assertEquals("雷", SkillPresentationUtil.skillTagDisplayName("LIGHTNING"));
        assertEquals("その他", SkillPresentationUtil.skillTagDisplayName("unknown_tag"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 種別と重複する active/passive タグは一覧から除き、残るタグだけを日本語表示する。
     */
    @Test
    void tagDisplayOmitsActivityTagAlreadyShownAsSkillKind() {
        SkillDefinition definition = definition(List.of("active", "magic", "fire"), List.of());

        assertEquals("魔法 / 炎", SkillPresentationUtil.skillTagDisplayNames(
            definition, Set.of(MasterTagIds.Activity.ACTIVE, MasterTagIds.Activity.PASSIVE)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 旧 lore の消費・クールダウン一行は、専用表示と二重に並べない。
     */
    @Test
    void flavorLoreExcludesLegacyCompactCostAndCooldownLine() {
        SkillDefinition definition = definition(List.of(), List.of(
            "&7敵へ炎ダメージを与える。",
            "&8消費MP: 18 / クールダウン: 3.0秒"
        ));

        List<String> rendered = SkillPresentationUtil.skillDescriptionAndFlavorLore(definition, null).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();

        assertEquals(List.of("敵へ炎ダメージを与える。"), rendered);
        assertFalse(rendered.stream().anyMatch(line -> line.contains("クールダウン")));
    }

    private static SkillDefinition definition(List<String> tags, List<String> lore) {
        return new SkillDefinition(
            "mage_fireball", "mage_fireball", "火焔弾", null, "FIRE_CHARGE", lore,
            60L, 18.0D, 0L, 1, null, Map.of(), tags, SkillKind.ACTIVE, true,
            SkillResourceType.MANA, 18.0D, "mage_fireball", 3, List.of(), List.of(), List.of()
        );
    }
}

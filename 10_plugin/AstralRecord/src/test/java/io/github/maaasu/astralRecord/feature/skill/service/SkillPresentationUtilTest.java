package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.model.ResolvedLearnedSkill;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 3. 解決済みスキル
     * 検証契約: レベル・シジル由来のスキルダメージ補正を倍率と倍率配列へ計算し、説明文へ展開する。
     */
    @Test
    void resolvedValuesExpandScalarAndArrayPlaceholders() {
        SkillDefinition definition = new SkillDefinition(
            "adventurer_smash", "adventurer_smash", "スマッシュ",
            "&7射程{skill.range}m、威力{skill.effectiveDamageRatio:percent}%。持続{skill.durationTicks:seconds}秒。",
            "AMETHYST_SHARD", List.of(
                "倍率: {skill.effectiveDamageRatios:percent}",
                "先頭: {skill.effectiveDamageRatios[0]:percent}%"
            ),
            0L, 0.0D, 0L, 1, null,
            Map.of(
                "range", 15.05D,
                "damageRatio", 1.15D,
                "damageRatios", List.of(1.15D, 0.90D),
                "durationTicks", 25.0D
            ),
            List.of(), SkillKind.ACTIVE, true, SkillResourceType.ENERGY, 0.0D,
            "adventurer_smash", 2, List.of(), List.of(), List.of()
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(), UUID.randomUUID(), definition.getId(), 2,
            List.of(), 0, null, null
        );
        ResolvedLearnedSkill resolved = new ResolvedLearnedSkill(
            learned,
            definition,
            Map.of(StatusType.SKILL_DAMAGE_INCREASE, 5.0D),
            Set.of()
        );

        List<net.kyori.adventure.text.Component> components =
            SkillPresentationUtil.skillDescriptionAndFlavorLore(resolved, null);
        List<String> rendered = components.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();

        assertEquals("射程15.1m、威力120.8%。持続1.3秒。", rendered.get(0));
        assertEquals("倍率: 120.8 / 94.5", rendered.get(1));
        assertEquals("先頭: 120.8%", rendered.get(2));

        String legacy = LegacyComponentSerializer.legacySection().serialize(components.get(0));
        assertEquals("§7射程§e15.1§7m、威力§e120.8§7%。持続§e1.3§7秒。", legacy);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_1-モデル定義.md
     * 章・見出し: # 13_1-モデル定義 > ## 3. 解決済みスキル
     * 検証契約: プレースホルダーを使わない固定説明文は、解決済みスキル補正で書き換えない。
     */
    @Test
    void fixedAttackRatioRemainsUnchangedForResolvedSkillDamageIncrease() {
        SkillDefinition definition = definition(List.of(), List.of("&7近接攻撃力100%のダメージ。"));
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(), UUID.randomUUID(), definition.getId(), 2,
            List.of(), 0, null, null
        );
        ResolvedLearnedSkill resolved = new ResolvedLearnedSkill(
            learned, definition, Map.of(StatusType.SKILL_DAMAGE_INCREASE, 5.0D), Set.of()
        );

        List<String> rendered = SkillPresentationUtil.skillDescriptionAndFlavorLore(resolved, null).stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();

        assertEquals(List.of("近接攻撃力100%のダメージ。"), rendered);
    }

    private static SkillDefinition definition(List<String> tags, List<String> lore) {
        return new SkillDefinition(
            "adventurer_smash", "adventurer_smash", "スマッシュ", null, "IRON_SWORD", lore,
            60L, 18.0D, 0L, 1, null, Map.of(), tags, SkillKind.ACTIVE, true,
            SkillResourceType.ENERGY, 18.0D, "adventurer_smash", 3, List.of(), List.of(), List.of()
        );
    }
}

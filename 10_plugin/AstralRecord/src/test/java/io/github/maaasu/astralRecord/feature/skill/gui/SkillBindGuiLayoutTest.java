package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.feature.skill.model.SkillBindInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindType;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBindGuiLayoutTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 通常攻撃を0、前後ページを45/53、presetを46〜48・50〜52、戻る/閉じるを49へ配置する。
     */
    @Test
    void mapsSixPresetSlotsAroundBackButton() {
        int[] presetSlots = {46, 47, 48, 50, 51, 52};
        for (int index = 0; index < presetSlots.length; index++) {
            int preset = index + 1;
            int slot = presetSlots[index];
            assertEquals(slot, SkillBindGui.presetSlot(preset));
            assertEquals(preset, SkillBindGui.presetIndexAtSlot(slot));
        }

        assertEquals(0, SkillBindGui.NORMAL_ATTACK_SLOT);
        assertEquals(26, SkillBindGui.CONTENT_SLOT_COUNT);
        assertEquals(45, SkillBindGui.PREVIOUS_PAGE_SLOT);
        assertEquals(49, SkillBindGui.BACK_SLOT);
        assertEquals(53, SkillBindGui.NEXT_PAGE_SLOT);
        assertEquals(-1, SkillBindGui.presetSlot(0));
        assertEquals(-1, SkillBindGui.presetSlot(7));
        assertEquals(-1, SkillBindGui.presetIndexAtSlot(49));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 5. 識別とページング
     * 検証契約: メインの49だけを共有ナビゲーションへ公開し、詳細・合成の戻る操作は各画面専用にする。
     */
    @Test
    void synthesisBackDoesNotUseSharedNavigationSlot() {
        assertEquals(49, new SkillBindInventoryHolder(SkillBindScreen.MAIN, 1, 0).getBackSlot());
        SkillBindInventoryHolder detail = new SkillBindInventoryHolder(SkillBindScreen.DETAIL, 1, 2, "skill");
        assertEquals(-1, detail.getBackSlot());
        assertEquals(-1, new SkillBindInventoryHolder(SkillBindScreen.SYNTHESIS, 1, 0, "skill").getBackSlot());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-スキルバインドGUI.md
     * 章・見出し: # 13_4-スキルバインドGUI > ## 2. バインド
     * 検証契約: 通常攻撃はアクティブ・左クリックの設定先では表示するが、設定不能なパッシブ枠の選択中は表示しない。
     */
    @Test
    void normalAttackIsHiddenOnlyWhileSelectingPassiveSlot() {
        assertFalse(SkillBindGui.shouldShowNormalAttack(SkillBindType.PASSIVE));
        assertTrue(SkillBindGui.shouldShowNormalAttack(SkillBindType.ACTIVE));
        assertTrue(SkillBindGui.shouldShowNormalAttack(SkillBindType.LEFT_CLICK));
        assertTrue(SkillBindGui.shouldShowNormalAttack(null));
        assertTrue(SkillBindGui.shouldShowNormalAttack(0, SkillBindType.ACTIVE));
        assertFalse(SkillBindGui.shouldShowNormalAttack(1, SkillBindType.ACTIVE));
        assertFalse(SkillBindGui.shouldShowNormalAttack(1, null));
        // 通常攻撃を表示しないページでは、スキル一覧を slot 0 から左詰めで描画する。
        assertEquals(0, SkillBindGui.contentSlotOffset(0, SkillBindType.PASSIVE));
        assertEquals(0, SkillBindGui.contentSlotOffset(1, SkillBindType.ACTIVE));
        assertEquals(1, SkillBindGui.contentSlotOffset(0, SkillBindType.ACTIVE));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 現在レベルと最大レベルが同じスキルは比率表示を赤色太字の max 表示へ置き換える。
     */
    @Test
    void displaysMaxLabelWhenSkillReachesMaxLevel() {
        Component display = SkillBindGui.skillLevelDisplay(3, 3, NamedTextColor.GOLD);

        assertEquals(" max", PlainTextComponentSerializer.plainText().serialize(display));
        Component max = display.children().get(0);
        assertEquals("max", PlainTextComponentSerializer.plainText().serialize(max));
        assertEquals(NamedTextColor.RED, max.color());
        assertEquals(TextDecoration.State.TRUE, max.decoration(TextDecoration.BOLD));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 最大レベル未満のスキルは従来どおり Lv.現在/最大 で表示する。
     */
    @Test
    void keepsLevelRatioBelowMaxLevel() {
        Component display = SkillBindGui.skillLevelDisplay(2, 3, NamedTextColor.GOLD);

        assertEquals(" Lv.2/3", PlainTextComponentSerializer.plainText().serialize(display));
        assertEquals(NamedTextColor.GOLD, display.color());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 使用許可を失った設定済みスキル枠は、本来のアイコンではなく薄灰色の羊毛で表示する。
     */
    @Test
    void usesGrayWoolForUnpermittedBoundSkill() {
        String bindingId = UUID.randomUUID().toString();
        SkillManagerEntry unpermitted = entry(bindingId, false);
        SkillManagerEntry permitted = entry(UUID.randomUUID().toString(), true);

        assertEquals(
            Material.LIGHT_GRAY_WOOL,
            SkillBindGui.bindSlotMaterial(true, bindingId, false, unpermitted)
        );
        assertEquals(
            Material.DIAMOND_SWORD,
            SkillBindGui.bindSlotMaterial(true, permitted.bindingId(), false, permitted)
        );
        assertEquals(
            Material.IRON_BARS,
            SkillBindGui.bindSlotMaterial(false, bindingId, false, unpermitted)
        );
    }

    private static SkillManagerEntry entry(String bindingId, boolean permitted) {
        SkillDefinition definition = new SkillDefinition(
            "test_skill", "test_skill", "テストスキル", null, "DIAMOND_SWORD", List.of(),
            0L, 0.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            null, null, "test_skill", 1, List.of(), List.of(), List.of()
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.fromString(bindingId), UUID.randomUUID(), "test_skill", 1, List.of(), 0, null, null
        );
        return new SkillManagerEntry(learned, definition, permitted);
    }
}

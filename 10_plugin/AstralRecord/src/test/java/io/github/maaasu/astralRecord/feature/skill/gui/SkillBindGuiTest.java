package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindPreset;
import io.github.maaasu.astralRecord.feature.skill.model.SkillBindSession;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.model.SkillRequiredItemDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.LearnedSkillInstance;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillBindGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 習得個体がなくても、空の active 枠には現在の使用許可 active スキルだけを表示する。
     */
    @Test
    void emptyActiveSlotShowsPermittedActiveSkills() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillBindGuiTest");
        SkillBindGui gui = new SkillBindGui(plugin, mock(ItemService.class), mock(SkillService.class));
        SkillBindSession session = new SkillBindSession(presets());
        SkillDefinition active = definition("active_skill", "アクティブスキル", SkillKind.ACTIVE, true);
        SkillDefinition passive = definition("passive_skill", "パッシブスキル", SkillKind.PASSIVE, true);

        Inventory inventory = gui.createMainInventory(
            session, List.of(), Map.of(), List.of(active, passive), 5, 0
        );
        List<String> lore = lore(inventory.getItem(SkillBindGui.ACTION_RING_BIND_SLOT_START));

        assertTrue(lore.contains("現在の使用許可スキル"));
        assertTrue(lore.contains("  ▸ アクティブスキル"));
        assertFalse(lore.contains("  ▸ パッシブスキル"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 1. スキルマネージャー
     * 検証契約: 空の passive 枠には bind-required の使用許可 passive だけを表示する。
     */
    @Test
    void emptyPassiveSlotShowsOnlyBindRequiredPermittedPassives() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillBindGuiTest");
        SkillBindGui gui = new SkillBindGui(plugin, mock(ItemService.class), mock(SkillService.class));
        SkillBindSession session = new SkillBindSession(presets());
        SkillDefinition bindRequired = definition("bind_required", "設定必須パッシブ", SkillKind.PASSIVE, true);
        SkillDefinition alwaysActive = definition("always_active", "常時発動パッシブ", SkillKind.PASSIVE, false);
        SkillDefinition active = definition("active_skill", "アクティブスキル", SkillKind.ACTIVE, true);

        Inventory inventory = gui.createMainInventory(
            session, List.of(), Map.of(), List.of(bindRequired, alwaysActive, active), 5, 0
        );
        List<String> lore = lore(inventory.getItem(SkillBindGui.PASSIVE_BIND_SLOT_START));

        assertTrue(lore.contains("現在の使用許可スキル"));
        assertTrue(lore.contains("  ▸ 設定必須パッシブ"));
        assertFalse(lore.contains("  ▸ 常時発動パッシブ"));
        assertFalse(lore.contains("  ▸ アクティブスキル"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 3. スキルマネージャーの習得表示
     * 検証契約: 最大レベル未満の習得済み個体は、次のレベル、必要素材、右クリックによるレベルアップ操作を表示する。
     */
    @Test
    void learnedSkillBelowMaxShowsNextLevelMaterialsAndLevelUpAction() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillBindGuiTest");
        ItemService itemService = itemServiceWithSkillGemRaw();
        SkillBindGui gui = new SkillBindGui(plugin, itemService, mock(SkillService.class));
        SkillBindSession session = new SkillBindSession(presets());

        Inventory inventory = gui.createMainInventory(
            session, List.of(learnedEntry(1, 3)), Map.of(), List.of(), 5, 0
        );
        List<String> lore = lore(inventory.getItem(1));

        assertTrue(lore.contains("次のレベル: Lv.1 → Lv.2"));
        assertTrue(lore.contains("レベルアップに必要な素材:"));
        assertTrue(lore.contains("• スキルジェムの原石(無印) ×2"));
        assertTrue(lore.contains("右クリック: レベルアップ"));
        assertFalse(lore.contains("レベル: MAX"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 3. スキルマネージャーの習得表示
     * 検証契約: 未習得スキルは、習得に必要な素材のマスター表示名と個数をShop形式の箇条書きで表示する。
     */
    @Test
    void unlearnedSkillShowsRequiredItemNameAndAmount() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillBindGuiTest");
        ItemService itemService = itemServiceWithSkillGemRaw();
        SkillBindGui gui = new SkillBindGui(plugin, itemService, mock(SkillService.class));
        SkillBindSession session = new SkillBindSession(presets());
        SkillDefinition definition = unlearnedDefinition();

        Inventory inventory = gui.createMainInventory(
            session, List.of(), List.of(definition), Map.of(), List.of(definition), 5, 0
        );
        List<String> lore = lore(inventory.getItem(1));

        assertTrue(lore.contains("未習得"));
        assertTrue(lore.contains("習得に必要な素材:"));
        assertTrue(lore.contains("• スキルジェムの原石(無印) ×3"));
        assertTrue(lore.contains("左クリック: 習得"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 3. スキルマネージャーの習得表示
     * 検証契約: 最大レベルの習得済み個体はMAXを表示し、レベルアップ操作と必要素材を表示しない。
     */
    @Test
    void learnedSkillAtMaxShowsMaxWithoutLevelUpAction() {
        PluginMock plugin = MockBukkit.createMockPlugin("SkillBindGuiTest");
        SkillBindGui gui = new SkillBindGui(plugin, mock(ItemService.class), mock(SkillService.class));
        SkillBindSession session = new SkillBindSession(presets());

        Inventory inventory = gui.createMainInventory(
            session, List.of(learnedEntry(3, 3)), Map.of(), List.of(), 5, 0
        );
        List<String> lore = lore(inventory.getItem(1));

        assertTrue(lore.contains("レベル: MAX"));
        assertFalse(lore.contains("レベルアップに必要な素材:"));
        assertFalse(lore.contains("右クリック: レベルアップ"));
    }

    private static List<SkillBindPreset> presets() {
        List<SkillBindPreset> presets = new ArrayList<>();
        for (int index = 1; index <= SkillBindGui.PRESET_COUNT; index++) {
            presets.add(unlockedPreset(index));
        }
        return presets;
    }

    private static SkillBindPreset unlockedPreset(int presetIndex) {
        UUID accountId = UUID.randomUUID();
        return new SkillBindPreset(
            null, accountId, presetIndex, List.of(), null, List.of(), true, true, 1
        );
    }

    private static SkillDefinition definition(
        String id,
        String name,
        SkillKind kind,
        boolean passiveBindRequired
    ) {
        return new SkillDefinition(
            id, id, name, null, "BOOK", List.of(), 0L, 0.0D, 0L, 1, null,
            Map.of(), List.of(), kind, passiveBindRequired, null, null, id, 1,
            List.of(), List.of(), List.of()
        );
    }

    private static SkillManagerEntry learnedEntry(int level, int maxLevel) {
        SkillDefinition definition = new SkillDefinition(
            "test_skill", "test_skill", "テストスキル", null, "BOOK", List.of(),
            0L, 0.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            null, null, "test_skill", maxLevel, List.of(), List.of(), List.of(), List.of(),
            List.of(new SkillRequiredItemDefinition("skill_gem_raw", 2))
        );
        LearnedSkillInstance learned = new LearnedSkillInstance(
            UUID.randomUUID(), UUID.randomUUID(), "test_skill", level, List.of(), 0, null, null
        );
        return new SkillManagerEntry(learned, definition, true);
    }

    private static SkillDefinition unlearnedDefinition() {
        return new SkillDefinition(
            "unlearned_skill", "unlearned_skill", "未習得テスト", null, "BOOK", List.of(),
            0L, 0.0D, 0L, 1, null, Map.of(), List.of(), SkillKind.ACTIVE, true,
            null, null, "unlearned_skill", 3, List.of(), List.of(), List.of(),
            List.of(new SkillRequiredItemDefinition("skill_gem_raw", 3)), List.of()
        );
    }

    private static ItemService itemServiceWithSkillGemRaw() {
        ItemService itemService = mock(ItemService.class);
        ItemModel item = mock(ItemModel.class);
        when(item.getId()).thenReturn("skill_gem_raw");
        when(item.getName()).thenReturn("&fスキルジェムの原石(無印)");
        when(itemService.findLoadedById("skill_gem_raw")).thenReturn(item);
        return itemService;
    }

    private static List<String> lore(ItemStack item) {
        return item.getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();
    }
}

package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_0-概要.md
     * 章・見出し: # 09_0-概要 > ## 2. 責務
     * 検証契約: 通常転職対象の冒険者・一次職・二次職・三次職を渡すと、各クラスを転職GUIの定義済み枠へ表示する。
     */
    @Test
    void rendersEveryConfiguredClassTierAtItsDefinedSlot() {
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ClassScreenView view = new ClassScreenView(new NamespacedKey("astralrecord", "class_id"));
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        Map<Integer, String> expectedClassIdBySlot = Map.ofEntries(
            Map.entry(13, "adventurer"),
            Map.entry(20, "swordsman"),
            Map.entry(22, "hunter"),
            Map.entry(24, "mage"),
            Map.entry(28, "paladin"),
            Map.entry(29, "swordmaster"),
            Map.entry(30, "sharpshooter"),
            Map.entry(31, "phantom_archer"),
            Map.entry(32, "wizard"),
            Map.entry(33, "archmage"),
            Map.entry(37, "royal_crusader"),
            Map.entry(38, "grandmaster"),
            Map.entry(39, "elemental_shooter"),
            Map.entry(40, "spectral_archer"),
            Map.entry(41, "astral_wizard"),
            Map.entry(42, "arc_sage")
        );

        List<ClassViewEntry> entries = expectedClassIdBySlot.values().stream()
            .map(this::classEntry)
            .toList();
        view.render(inventory, astPlayer, entries);

        assertEquals(expectedClassIdBySlot.size(), countClassItems(view, inventory));
        expectedClassIdBySlot.forEach((slot, classId) ->
            assertEquals(classId, view.getClassId(inventory.getItem(slot)))
        );
    }

    private ClassViewEntry classEntry(String classId) {
        return new ClassViewEntry(
            classId,
            "職業",
            classId,
            null,
            "IRON_SWORD",
            "アタッカー",
            List.of(),
            true,
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private int countClassItems(ClassScreenView view, Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (view.getClassId(inventory.getItem(slot)) != null) {
                count++;
            }
        }
        return count;
    }
}

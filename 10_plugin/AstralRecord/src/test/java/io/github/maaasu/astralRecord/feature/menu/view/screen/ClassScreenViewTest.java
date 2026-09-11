package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ClassScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_0-概要.md
     * 章・見出し: # 09_0-概要 > ## 2. 責務
     * 検証契約: class master の GUI スロットを指定した職業を渡すと、各クラスを指定枠へ表示する。
     */
    @Test
    void rendersClassesAtMasterConfiguredSlots() {
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ClassScreenView view = new ClassScreenView(new NamespacedKey("astralrecord", "class_id"));
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        Map<Integer, String> expectedClassIdBySlot = Map.of(13, "adventurer", 20, "swordsman");

        List<ClassViewEntry> entries = expectedClassIdBySlot.entrySet().stream()
            .map(entry -> classEntry(entry.getValue(), entry.getKey()))
            .toList();
        view.render(inventory, astPlayer, entries);

        assertEquals(expectedClassIdBySlot.size(), countClassItems(view, inventory));
        expectedClassIdBySlot.forEach((slot, classId) ->
            assertEquals(classId, view.getClassId(inventory.getItem(slot)))
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_0-概要.md
     * 章・見出し: # 09_0-概要 > ## 2. 責務
     * 検証契約: adminChangeOnly により一般プレイヤーの転職が拒否された職業は、クラスGUIへ調整中の赤字案内を表示する。
     */
    @Test
    void rendersAdjustmentNoticeForAdminOnlyClass() {
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        ClassScreenView view = new ClassScreenView(new NamespacedKey("astralrecord", "class_id"));
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);

        view.render(inventory, astPlayer, List.of(classEntry("paladin", 28, true)));

        var adjustmentLine = inventory.getItem(28).getItemMeta().lore().stream()
            .filter(line -> PlainTextComponentSerializer.plainText().serialize(line)
                .equals("現在調整中のためこのクラスへの転職はできません"))
            .findFirst()
            .orElse(null);
        assertNotNull(adjustmentLine);
        assertEquals(NamedTextColor.RED, adjustmentLine.color());
    }

    private ClassViewEntry classEntry(String classId, int guiSlot) {
        return classEntry(classId, guiSlot, false);
    }

    private ClassViewEntry classEntry(String classId, int guiSlot, boolean adjustmentInProgress) {
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
            List.of(),
            adjustmentInProgress,
            null,
            guiSlot
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

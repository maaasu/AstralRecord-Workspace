package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CraftShortcutViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_1-モデル定義.md
     * 章・見出し: # 09_1-モデル定義 > ## 2. クラフトショートカット
     * 検証契約: 4つの共通shortcut定義を単一player描画contextで描く。
     */
    @Test
    void rendersSharedDefinitionsWithOnePlayerRenderContext() {
        var astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        var context = new PlayerGuiRenderContext(
            astPlayer.getAccount(),
            astPlayer.getStatusSnapshot(),
            2,
            3,
            789L,
            100L,
            new PlayerEquipmentSnapshot(
                Component.text("星頭巾"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );
        var shortcutKey = new NamespacedKey("astralrecord", "menu_shortcut_test");
        var actionKey = new NamespacedKey("astralrecord", "menu_action_test");
        var view = new CraftShortcutView(shortcutKey, actionKey);
        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(inventory.getMatrix()).thenReturn(new ItemStack[MenuShortcutSettings.SLOT_COUNT]);

        view.renderCraftShortcuts(player, MenuShortcutSettings.defaults(), context);

        ArgumentCaptor<ItemStack> resultCaptor = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setResult(resultCaptor.capture());
        ItemStack result = resultCaptor.getValue();
        assertEquals(Material.NETHER_STAR, result.getType());
        assertEquals(
            MenuShortcutAction.MAIN_MENU.getCode(),
            result.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING)
        );
        ArgumentCaptor<ItemStack[]> matrixCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setMatrix(matrixCaptor.capture());
        ItemStack[] matrix = matrixCaptor.getValue();
        assertEquals(Material.PLAYER_HEAD, matrix[0].getType());
        assertEquals(Material.BEACON, matrix[1].getType());
        assertEquals(Material.BUNDLE, matrix[2].getType());
        assertEquals(Material.NETHERITE_CHESTPLATE, matrix[3].getType());
        assertTrue(view.isCraftShortcutIcon(matrix[0]));
        assertLoreContains(matrix[2].getItemMeta().lore(), "789 G");
        assertLoreContains(matrix[3].getItemMeta().lore(), "星頭巾");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: 基礎範囲と合計範囲が異なるstatusだけをカタログ順で最大8件表示し、9件目を「… ほか1件」とする。
     */
    @Test
    void statusShortcutShowsOnlyChangedStatusesUpToDisplayLimit() {
        var astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        EnumMap<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);
        values.put(StatusType.MAX_HEALTH, new StatusValue(20.0D, 0.0D));
        values.put(StatusType.MAX_MANA, new StatusValue(10.0D, 1.0D));
        values.put(StatusType.MAX_ENERGY, new StatusValue(100.0D, 1.0D));
        values.put(StatusType.MAX_SHIELD, new StatusValue(0.0D, 1.0D));
        values.put(StatusType.STRENGTH, new StatusValue(5.0D, 1.0D));
        values.put(StatusType.DEXTERITY, new StatusValue(5.0D, 1.0D));
        values.put(StatusType.INTELLIGENCE, new StatusValue(5.0D, 1.0D));
        values.put(StatusType.VITALITY, new StatusValue(5.0D, 1.0D));
        values.put(StatusType.AGILITY, new StatusValue(5.0D, 1.0D));
        values.put(StatusType.LUCK, new StatusValue(5.0D, 1.0D));
        StatusSnapshot snapshot = new StatusSnapshot(
            values,
            20.0D,
            10.0D,
            100.0D,
            0.0D,
            0L,
            LocalDateTime.now()
        );
        var context = new PlayerGuiRenderContext(
            astPlayer.getAccount(),
            snapshot,
            0,
            0,
            0L,
            100L,
            new PlayerEquipmentSnapshot(
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );
        var view = new CraftShortcutView(
            new NamespacedKey("astralrecord", "menu_shortcut_status_limit_test"),
            new NamespacedKey("astralrecord", "menu_action_status_limit_test")
        );
        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(inventory.getMatrix()).thenReturn(new ItemStack[MenuShortcutSettings.SLOT_COUNT]);

        view.renderCraftShortcuts(player, MenuShortcutSettings.defaults(), context);

        ArgumentCaptor<ItemStack[]> matrixCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setMatrix(matrixCaptor.capture());
        List<String> statusLoreLines = plainLoreLines(matrixCaptor.getValue()[0]);
        String statusLore = String.join("\n", statusLoreLines);
        List<String> expectedStatusPrefixes = List.of(
            "最大MP:",
            "最大EN:",
            "最大シールド:",
            "筋力:",
            "器用さ:",
            "知力:",
            "体力:",
            "敏捷性:"
        );
        List<String> actualStatusPrefixes = statusLoreLines.stream()
            .map(line -> expectedStatusPrefixes.stream()
                .filter(line::startsWith)
                .findFirst()
                .orElse(null))
            .filter(java.util.Objects::nonNull)
            .toList();
        assertFalse(statusLore.contains("最大HP"));
        assertTrue(statusLore.contains("最大MP: 11"));
        assertEquals(expectedStatusPrefixes, actualStatusPrefixes);
        assertEquals(
            statusLoreLines.indexOf("敏捷性: 6") + 1,
            statusLoreLines.indexOf("… ほか1件")
        );
        assertTrue(statusLore.contains("… ほか1件"));
        assertFalse(statusLore.contains("幸運"));
    }

    private static void assertLoreContains(java.util.List<Component> lore, String expected) {
        assertTrue(lore != null && lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(line -> line.contains(expected)));
    }

    private static String plainLore(ItemStack itemStack) {
        return String.join("\n", plainLoreLines(itemStack));
    }

    private static List<String> plainLoreLines(ItemStack itemStack) {
        java.util.List<Component> lore = itemStack.getItemMeta().lore();
        if (lore == null) {
            return List.of();
        }
        return lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();
    }
}

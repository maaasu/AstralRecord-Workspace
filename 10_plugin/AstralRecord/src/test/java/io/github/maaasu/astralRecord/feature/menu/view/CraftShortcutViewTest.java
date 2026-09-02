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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
            "最大ENG:",
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: クラフト欄に shortcut item が残っていない場合は、枠を変更せず inventory 全体同期も発生させない。
     */
    @Test
    void doesNotUpdateInventoryWhenCraftShortcutsAreAlreadyAbsent() {
        var view = new CraftShortcutView(
            new NamespacedKey("astralrecord", "menu_shortcut_noop_test"),
            new NamespacedKey("astralrecord", "menu_action_noop_test")
        );
        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(inventory.getResult()).thenReturn(new ItemStack(Material.AIR));

        assertFalse(view.clearCraftShortcuts(player));

        verify(player, never()).updateInventory();
        verify(inventory, never()).setMatrix(any(ItemStack[].class));
        verify(inventory, never()).setResult(any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: 残存 shortcut item がある場合だけクラフト枠を空にし、変更有無を呼び出し元へ返す。
     */
    @Test
    void reportsExistingCraftShortcutsWhenCleared() {
        var shortcutKey = new NamespacedKey("astralrecord", "menu_shortcut_clear_test");
        var view = new CraftShortcutView(
            shortcutKey,
            new NamespacedKey("astralrecord", "menu_action_clear_test")
        );
        ItemStack shortcut = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = shortcut.getItemMeta();
        meta.getPersistentDataContainer().set(shortcutKey, PersistentDataType.INTEGER, 0);
        shortcut.setItemMeta(meta);

        Player player = mock(Player.class);
        InventoryView inventoryView = mock(InventoryView.class);
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(player.getOpenInventory()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {
            shortcut,
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(inventory.getResult()).thenReturn(shortcutIcon(shortcutKey));

        assertTrue(view.clearCraftShortcuts(player));

        verify(inventory).setMatrix(any(ItemStack[].class));
        verify(inventory).setResult(any(ItemStack.class));
        verify(player, never()).updateInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: 所持品とカーソルにも shortcut item がない場合は、インベントリ同期を発生させない。
     */
    @Test
    void doesNotUpdateInventoryWhenPlayerHasNoCraftShortcutItems() {
        var view = new CraftShortcutView(
            new NamespacedKey("astralrecord", "menu_shortcut_player_noop_test"),
            new NamespacedKey("astralrecord", "menu_action_player_noop_test")
        );
        Player player = mock(Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(36);
        when(player.getItemOnCursor()).thenReturn(new ItemStack(Material.AIR));

        assertFalse(view.removeCraftShortcutItems(player));

        verify(player, never()).updateInventory();
        verify(inventory, never()).setItem(anyInt(), any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: 通常アイテムと shortcut がクラフト行列に混在しても、shortcut だけを除去して通常入力を保持する。
     */
    @Test
    void clearsOnlyShortcutItemsFromMixedCraftMatrix() {
        NamespacedKey shortcutKey = new NamespacedKey("astralrecord", "menu_shortcut_mixed_test");
        CraftShortcutView view = new CraftShortcutView(
            shortcutKey,
            new NamespacedKey("astralrecord", "menu_action_mixed_test")
        );
        ItemStack regular = new ItemStack(Material.DIAMOND);
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {
            regular,
            shortcutIcon(shortcutKey),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(inventory.getResult()).thenReturn(new ItemStack(Material.AIR));

        assertTrue(view.clearCraftShortcuts(inventory));

        ArgumentCaptor<ItemStack[]> matrixCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        verify(inventory).setMatrix(matrixCaptor.capture());
        assertEquals(Material.DIAMOND, matrixCaptor.getValue()[0].getType());
        assertEquals(Material.AIR, matrixCaptor.getValue()[1].getType());
        verify(inventory, never()).setResult(any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: 通常のクラフト行列を保持したまま、結果枠に残った shortcut だけを除去する。
     */
    @Test
    void clearsShortcutResultWithoutChangingRegularCraftMatrix() {
        NamespacedKey shortcutKey = new NamespacedKey("astralrecord", "menu_shortcut_result_test");
        CraftShortcutView view = new CraftShortcutView(
            shortcutKey,
            new NamespacedKey("astralrecord", "menu_action_result_test")
        );
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {
            new ItemStack(Material.DIAMOND),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(inventory.getResult()).thenReturn(shortcutIcon(shortcutKey));

        assertTrue(view.clearCraftShortcuts(inventory));

        verify(inventory, never()).setMatrix(any(ItemStack[].class));
        verify(inventory).setResult(any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 6. クラフトショートカット描画
     * 検証契約: shortcut 行列を除去するときも、通常の結果アイテムは変更しない。
     */
    @Test
    void keepsRegularCraftResultWhenClearingShortcutMatrix() {
        NamespacedKey shortcutKey = new NamespacedKey("astralrecord", "menu_shortcut_matrix_test");
        CraftShortcutView view = new CraftShortcutView(
            shortcutKey,
            new NamespacedKey("astralrecord", "menu_action_matrix_test")
        );
        ItemStack regularResult = new ItemStack(Material.DIAMOND);
        CraftingInventory inventory = mock(CraftingInventory.class);
        when(inventory.getMatrix()).thenReturn(new ItemStack[] {
            shortcutIcon(shortcutKey),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR),
            new ItemStack(Material.AIR)
        });
        when(inventory.getResult()).thenReturn(regularResult);

        assertTrue(view.clearCraftShortcuts(inventory));

        verify(inventory).setMatrix(any(ItemStack[].class));
        verify(inventory, never()).setResult(any(ItemStack.class));
        assertEquals(Material.DIAMOND, regularResult.getType());
    }

    private static ItemStack shortcutIcon(NamespacedKey shortcutKey) {
        ItemStack shortcut = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = shortcut.getItemMeta();
        meta.getPersistentDataContainer().set(shortcutKey, PersistentDataType.INTEGER, 0);
        shortcut.setItemMeta(meta);
        return shortcut;
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

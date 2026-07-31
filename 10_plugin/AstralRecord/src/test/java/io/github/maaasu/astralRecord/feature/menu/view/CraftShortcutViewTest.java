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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CraftShortcutViewTest extends MockBukkitTestBase {

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
        String statusLore = plainLore(matrixCaptor.getValue()[0]);
        assertFalse(statusLore.contains("最大HP"));
        assertTrue(statusLore.contains("最大MP: 11"));
        assertTrue(statusLore.contains("… ほか1件"));
        assertFalse(statusLore.contains("幸運"));
    }

    private static void assertLoreContains(java.util.List<Component> lore, String expected) {
        assertTrue(lore != null && lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(line -> line.contains(expected)));
    }

    private static String plainLore(ItemStack itemStack) {
        java.util.List<Component> lore = itemStack.getItemMeta().lore();
        if (lore == null) {
            return "";
        }
        return lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", (left, right) -> left + "\n" + right);
    }
}

package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(Material.EMERALD, matrix[2].getType());
        assertEquals(Material.NETHERITE_CHESTPLATE, matrix[3].getType());
        assertTrue(view.isCraftShortcutIcon(matrix[0]));
        assertLoreContains(matrix[2].getItemMeta().lore(), "ゴールド: 789");
        assertLoreContains(matrix[3].getItemMeta().lore(), "星頭巾");
    }

    private static void assertLoreContains(java.util.List<Component> lore, String expected) {
        assertTrue(lore != null && lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(line -> line.contains(expected)));
    }
}

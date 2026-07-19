package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainMenuScreenViewTest extends MockBukkitTestBase {

    @Test
    void groupsMenuItemsIntoPersonalSocialAndUtilityRows() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        var context = new PlayerGuiRenderContext(
            astPlayer.getAccount(),
            astPlayer.getStatusSnapshot(),
            3,
            4,
            123L,
            100L,
            new PlayerEquipmentSnapshot(
                Component.text("星頭巾"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );

        new MainMenuScreenView().render(inventory, context);

        assertMaterial(inventory, MainMenuScreenView.STATUS_SLOT, Material.PLAYER_HEAD);
        assertMaterial(inventory, MainMenuScreenView.EQUIPMENT_GUI_SLOT, Material.NETHERITE_CHESTPLATE);
        assertMaterial(inventory, MainMenuScreenView.SKILL_BIND_SLOT, Material.ENCHANTED_BOOK);
        assertMaterial(inventory, 23, Material.GRAY_STAINED_GLASS_PANE);
        assertMaterial(inventory, MainMenuScreenView.PLAYER_SETTING_SLOT, Material.COMPARATOR);

        assertMaterial(inventory, MainMenuScreenView.ADVENTURE_RECORD_SLOT, Material.WRITTEN_BOOK);
        assertMaterial(inventory, MainMenuScreenView.MAIL_SLOT, Material.WRITABLE_BOOK);
        assertMaterial(inventory, MainMenuScreenView.PARTY_SLOT, Material.PLAYER_HEAD);
        assertMaterial(inventory, MainMenuScreenView.PLAYER_INFO_SLOT, Material.SPYGLASS);

        assertMaterial(inventory, MainMenuScreenView.CURRENCY_SLOT, Material.BUNDLE);
        assertMaterial(inventory, MainMenuScreenView.GUIDE_SLOT, Material.BOOK);
        assertMaterial(inventory, MainMenuScreenView.RETURN_TO_BASE_SLOT, Material.BEACON);
        assertMaterial(inventory, MainMenuScreenView.TRASH_SLOT, Material.LAVA_BUCKET);

        assertLoreContains(inventory, MainMenuScreenView.EQUIPMENT_GUI_SLOT, "星頭巾");
        assertLoreContains(inventory, MainMenuScreenView.CURRENCY_SLOT, "ゴールド: 123G");
        assertLoreContains(inventory, MainMenuScreenView.RETURN_TO_BASE_SLOT, "必要ゴールド 100");
    }

    private static void assertMaterial(Inventory inventory, int slot, Material expected) {
        assertEquals(expected, inventory.getItem(slot).getType());
    }

    private static void assertLoreContains(Inventory inventory, int slot, String expected) {
        var lore = inventory.getItem(slot).getItemMeta().lore();
        assertTrue(lore != null && lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(line -> line.contains(expected)));
    }
}

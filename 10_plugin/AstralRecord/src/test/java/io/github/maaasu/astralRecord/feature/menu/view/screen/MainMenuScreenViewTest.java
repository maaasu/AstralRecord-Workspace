package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MainMenuScreenViewTest extends MockBukkitTestBase {

    @Test
    void groupsMenuItemsIntoPersonalSocialAndUtilityRows() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);

        new MainMenuScreenView().render(inventory, player, 123L, List.of());

        assertMaterial(inventory, MainMenuScreenView.STATUS_SLOT, Material.PLAYER_HEAD);
        assertMaterial(inventory, MainMenuScreenView.EQUIPMENT_GUI_SLOT, Material.NETHERITE_CHESTPLATE);
        assertMaterial(inventory, MainMenuScreenView.SKILL_BIND_SLOT, Material.ENCHANTED_BOOK);
        assertMaterial(inventory, MainMenuScreenView.BUFF_SLOT, Material.POTION);
        assertMaterial(inventory, MainMenuScreenView.PLAYER_SETTING_SLOT, Material.COMPARATOR);

        assertMaterial(inventory, MainMenuScreenView.ADVENTURE_RECORD_SLOT, Material.WRITTEN_BOOK);
        assertMaterial(inventory, MainMenuScreenView.MAIL_SLOT, Material.WRITABLE_BOOK);
        assertMaterial(inventory, MainMenuScreenView.PARTY_SLOT, Material.PLAYER_HEAD);
        assertMaterial(inventory, MainMenuScreenView.PLAYER_INFO_SLOT, Material.SPYGLASS);

        assertMaterial(inventory, MainMenuScreenView.CURRENCY_SLOT, Material.EMERALD);
        assertMaterial(inventory, MainMenuScreenView.GUIDE_SLOT, Material.BOOK);
        assertMaterial(inventory, MainMenuScreenView.RETURN_TO_BASE_SLOT, Material.BEACON);
        assertMaterial(inventory, MainMenuScreenView.TRASH_SLOT, Material.LAVA_BUCKET);
    }

    private static void assertMaterial(Inventory inventory, int slot, Material expected) {
        assertEquals(expected, inventory.getItem(slot).getType());
    }
}

package io.github.maaasu.astralRecord.shared.gui.navigation;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GuiNavigationServiceTest extends MockBukkitTestBase {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    @Test
    void showsCloseWithoutHistoryAndOpensTheRecordedPreviousGui() {
        var player = server().addPlayer();
        AstPlayerCache.put(DesignTestFixtures.astPlayer(player, AccountMode.PLAYER));
        GuiNavigationService service = new GuiNavigationService(mock(AstralRecord.class));
        Inventory menu = inventory("menu");
        Inventory detail = inventory("detail");

        service.registerOpen(player, menu);

        assertEquals(Material.BARRIER, menu.getItem(49).getType());
        assertFalse(service.hasPrevious(player));

        service.registerOpen(player, detail);

        assertEquals(Material.SPECTRAL_ARROW, detail.getItem(49).getType());
        assertTrue(service.hasPrevious(player));
        assertTrue(service.openPrevious(player));
        assertSame(menu, player.getOpenInventory().getTopInventory());

        service.registerOpen(player, menu);
        assertFalse(service.hasPrevious(player));
    }

    @Test
    void alwaysCloseNavigationDoesNotExposeHistoryAsBackAction() {
        var player = server().addPlayer();
        AstPlayerCache.put(DesignTestFixtures.astPlayer(player, AccountMode.PLAYER));
        GuiNavigationService service = new GuiNavigationService(mock(AstralRecord.class));
        Inventory previous = inventory("previous");
        Inventory main = alwaysCloseInventory("main");

        service.registerOpen(player, previous);
        service.registerOpen(player, main);

        assertTrue(service.hasPrevious(player));
        assertTrue(service.isCloseNavigation(player, main));
        assertEquals(Material.BARRIER, main.getItem(49).getType());
    }

    private static Inventory inventory(String navigationId) {
        Inventory inventory = Bukkit.createInventory(new Holder(navigationId), 54);
        inventory.setItem(49, new ItemStack(Material.SPECTRAL_ARROW));
        return inventory;
    }

    private static Inventory alwaysCloseInventory(String navigationId) {
        Inventory inventory = Bukkit.createInventory(new AlwaysCloseHolder(navigationId), 54);
        inventory.setItem(49, new ItemStack(Material.SPECTRAL_ARROW));
        return inventory;
    }

    private record Holder(String navigationId) implements GuiNavigationHolder {
        @Override
        public @NotNull String getNavigationId() {
            return navigationId;
        }

        @Override
        public int getBackSlot() {
            return 49;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 54);
        }
    }


    private record AlwaysCloseHolder(String navigationId) implements GuiNavigationHolder {
        @Override
        public @NotNull String getNavigationId() {
            return navigationId;
        }

        @Override
        public int getBackSlot() {
            return 49;
        }

        @Override
        public boolean isAlwaysCloseNavigation() {
            return true;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, 54);
        }
    }
}

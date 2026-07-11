package io.github.maaasu.astralRecord.shared.gui.paging;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PagedGuiViewTest extends MockBukkitTestBase {

    private final PagedGuiView view = new PagedGuiView();

    @Test
    void rendersTopCloseOnlyWhenPlayerInventoryShortcutIsUnavailable() {
        Inventory chestOnly = Bukkit.createInventory(new ChestOnlyHolder(), PagedGuiView.SIZE);
        Inventory hotbarManaged = Bukkit.createInventory(new HotbarManagedHolder(), PagedGuiView.SIZE);

        view.render(chestOnly, List.of(), 0);
        view.render(hotbarManaged, List.of(), 0);

        assertEquals(Material.BARRIER, chestOnly.getItem(PagedGuiView.CLOSE_SLOT).getType());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, hotbarManaged.getItem(PagedGuiView.CLOSE_SLOT).getType());
    }

    private static final class ChestOnlyHolder implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }

    private static final class HotbarManagedHolder implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }
}

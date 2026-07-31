package io.github.maaasu.astralRecord.shared.gui.paging;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PagedGuiViewTest extends MockBukkitTestBase {
    private static final int FORMER_CHEST_CLOSE_SLOT = 50;

    private final PagedGuiView view = new PagedGuiView();

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 7. 共通 page GUI
     * 検証契約: chest inventory内へ専用close buttonを描画しない。
     */
    @Test
    void doesNotRenderCloseButtonInChestInventory() {
        Inventory hotbarManaged = Bukkit.createInventory(new HotbarManagedHolder(), PagedGuiView.SIZE);

        view.render(hotbarManaged, List.of(), 0);

        assertEquals(
            Material.GRAY_STAINED_GLASS_PANE,
            hotbarManaged.getItem(FORMER_CHEST_CLOSE_SLOT).getType()
        );
    }

    private static final class HotbarManagedHolder implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }
}

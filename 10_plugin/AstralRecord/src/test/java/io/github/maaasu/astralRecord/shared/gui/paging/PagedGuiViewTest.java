package io.github.maaasu.astralRecord.shared.gui.paging;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 7. 共通 page GUI
     * 検証契約: 共通ページングは左右の Forest Green Arrow プレイヤーヘッドを使用する。
     */
    @Test
    void rendersForestGreenPlayerHeadsForPageNavigation() {
        Inventory inventory = Bukkit.createInventory(new HotbarManagedHolder(), PagedGuiView.SIZE);

        view.render(inventory, java.util.Collections.nCopies(91, new org.bukkit.inventory.ItemStack(Material.STONE)), 1);

        assertEquals(Material.PLAYER_HEAD, inventory.getItem(PagedGuiView.PREVIOUS_SLOT).getType());
        assertEquals(Material.PLAYER_HEAD, inventory.getItem(PagedGuiView.NEXT_SLOT).getType());
        SkullMeta previous = (SkullMeta) inventory.getItem(PagedGuiView.PREVIOUS_SLOT).getItemMeta();
        SkullMeta next = (SkullMeta) inventory.getItem(PagedGuiView.NEXT_SLOT).getItemMeta();
        assertTrue(previous.getPlayerProfile().hasProperty("textures"));
        assertTrue(next.getPlayerProfile().hasProperty("textures"));
    }

    private static final class HotbarManagedHolder implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }
}

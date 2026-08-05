package io.github.maaasu.astralRecord.feature.sell.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellServiceSoundTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 売却
     * 検証契約: 非空の売却確認画面を表示する直前に確認音を一度再生する。
     */
    @Test
    void openingNonEmptyConfirmPlaysConfirmSound() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        MenuGuiTransitionService transitionService = mock(MenuGuiTransitionService.class);
        SellService service = new SellService(plugin, menuView, mock(InventoryService.class), transitionService);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getLocation()).thenReturn(location);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(transitionService).switchGuiWithoutInventoryReload(eq(player), any(Runnable.class));

        invokeOpenConfirm(service, player, List.of(normalizableItem()), 0);

        verify(player).playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.65F, 1.35F);
        verify(menuView).openSellConfirm(eq(player), any(List.class), eq(0));
    }

    private void invokeOpenConfirm(SellService service, Player player, List<ItemStack> items, int pageIndex) throws Exception {
        Method method = SellService.class.getDeclaredMethod("openSellConfirm", Player.class, List.class, int.class);
        method.setAccessible(true);
        method.invoke(service, player, items, pageIndex);
    }

    private ItemStack normalizableItem() {
        ItemStack source = mock(ItemStack.class);
        ItemStack cleaned = mock(ItemStack.class);
        when(source.getType()).thenReturn(Material.DIAMOND);
        when(source.clone()).thenReturn(cleaned);
        when(cleaned.getItemMeta()).thenReturn(null);
        when(cleaned.getMaxStackSize()).thenReturn(1);
        return source;
    }
}

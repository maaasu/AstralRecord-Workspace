package io.github.maaasu.astralRecord.feature.sell.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
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
        verify(menuView).openSellConfirm(eq(player), anyList(), eq(0));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 売却
     * 検証契約: 売却確認画面を売却確定せずに閉じた場合、保持中アイテムを通常インベントリへ返却し、売却状態を破棄する。
     */
    @Test
    void closingConfirmWithoutSellingReturnsReservedItems() {
        AstralRecord plugin = mock(AstralRecord.class);
        ItemService itemService = mock(ItemService.class);
        when(plugin.getItemService()).thenReturn(itemService);
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuGuiTransitionService transitionService = mock(MenuGuiTransitionService.class);
        SellService service = new SellService(plugin, menuView, inventoryService, transitionService);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        Inventory confirmInventory = mock(Inventory.class);
        when(menuView.getMenuScreen(confirmInventory)).thenReturn(MenuScreen.SELL_CONFIRM);

        ItemModel itemModel = mock(ItemModel.class);
        when(itemModel.getUnSellable()).thenReturn(false);
        when(itemService.findLoadedById("test_item")).thenReturn(itemModel);
        ItemStack reservedItem = astralItem();
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(inventoryService.canAddItemToNormalInventory(astPlayer, itemModel, 1)).thenReturn(true);
        when(inventoryService.returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class)))
            .thenReturn(InventoryType.BAG);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.open(player, List.of(reservedItem), 0);
            clearInvocations(menuView);
            service.handleClose(confirmInventory, player);
        }

        verify(inventoryService).returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class));
        verify(transitionService).restorePlayerInventory(player);
        verify(menuView, org.mockito.Mockito.never()).openSell(any(Player.class), anyList(), eq(0));
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

    private ItemStack astralItem() {
        ItemStack item = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(1);
        when(item.getMaxStackSize()).thenReturn(1);
        when(item.clone()).thenReturn(item);
        when(item.hasItemMeta()).thenReturn(true);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.hasLore()).thenReturn(false);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(invocation -> {
            NamespacedKey key = invocation.getArgument(0);
            return switch (key.getKey()) {
                case "item_id" -> "test_item";
                case "category" -> "material";
                default -> null;
            };
        });
        return item;
    }
}

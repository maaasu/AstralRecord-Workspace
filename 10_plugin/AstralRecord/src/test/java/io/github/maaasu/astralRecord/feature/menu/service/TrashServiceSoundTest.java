package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrashServiceSoundTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 3. ゴミ箱
     * 検証契約: 非空のゴミ箱確認画面を表示する直前に確認音を一度再生する。
     */
    @Test
    void openingNonEmptyConfirmPlaysConfirmSound() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        TrashService service = new TrashService(plugin, menuView, mock(InventoryService.class), mock(MenuGuiTransitionService.class));
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getLocation()).thenReturn(location);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        invokeOpenConfirm(service, player, List.of(normalizableItem()), 0);

        verify(player).playSound(location, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.65F, 1.35F);
        verify(menuView).openTrashConfirm(eq(player), anyList(), eq(0));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 3. ゴミ箱確定・返却 > ### 処理要点
     * 検証契約: ゴミ箱確認画面を破棄確定せず閉じた場合、保持 item を返却する。
     */
    @Test
    void closingConfirmWithoutDisposingReturnsReservedItems() {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuGuiTransitionService transitionService = mock(MenuGuiTransitionService.class);
        TrashService service = new TrashService(plugin, menuView, inventoryService, transitionService);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        Inventory confirmInventory = mock(Inventory.class);
        when(menuView.getMenuScreen(confirmInventory)).thenReturn(MenuScreen.TRASH_CONFIRM);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(inventoryService.returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class)))
            .thenReturn(InventoryType.BAG);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.open(player, List.of(normalizableItem()), 0);
            service.handleClose(confirmInventory, player);
        }

        verify(inventoryService).returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class));
        verify(transitionService).restorePlayerInventory(player);
        verify(player, never()).getWorld();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_4-統合フロー.md
     * 章・見出し: # 09_4-統合フロー > ## 3. ゴミ箱確定・返却 > ### 例外・終了条件
     * 検証契約: 通常インベントリへ返却できない item は player 位置へ drop し、消失させない。
     */
    @Test
    void failedTrashReturnDropsItemInsteadOfDiscarding() {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        TrashService service = new TrashService(
            plugin,
            menuView,
            inventoryService,
            mock(MenuGuiTransitionService.class)
        );
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(player.getWorld()).thenReturn(world);
        Inventory trashInventory = mock(Inventory.class);
        when(menuView.getMenuScreen(trashInventory)).thenReturn(MenuScreen.TRASH);
        when(trashInventory.getSize()).thenReturn(54);
        ItemStack reservedItem = normalizableItem();
        when(trashInventory.getItem(0)).thenReturn(reservedItem);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(inventoryService.returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class)))
            .thenReturn(null);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            service.open(player, List.of(reservedItem), 0);
            service.handleClose(trashInventory, player);
        }

        verify(world).dropItemNaturally(eq(location), any(ItemStack.class));
        verify(inventoryService, times(1)).returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 3. ゴミ箱
     * 検証契約: ゴミ箱確認画面の戻る操作でも保持 item を返却してから画面遷移する。
     */
    @Test
    void returningFromConfirmReturnsReservedItemsBeforeNavigation() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuGuiTransitionService transitionService = mock(MenuGuiTransitionService.class);
        TrashService service = new TrashService(plugin, menuView, inventoryService, transitionService);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        Inventory confirmInventory = mock(Inventory.class);
        when(confirmInventory.getSize()).thenReturn(27);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(MenuView.TRASH_CONFIRM_RETURN_SLOT);
        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(confirmInventory);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(inventoryService.returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class)))
            .thenReturn(InventoryType.BAG);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.open(player, List.of(normalizableItem()), 0);
            invokeConfirmClick(service, event, player);
        }

        verify(transitionService).switchGuiWithInventoryRestore(eq(player), any(Runnable.class));
        verify(inventoryService).returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 3. ゴミ箱
     * 検証契約: 確認画面から破棄せずに戻った場合、戻り先のゴミ箱編集画面を空状態へ更新し、
     * 返却済み item を次の確認操作へ再取り込みしない。
     */
    @Test
    void returningFromConfirmClearsStaleTrashScreen() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        ItemService itemService = mock(ItemService.class);
        when(plugin.getItemService()).thenReturn(itemService);
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuGuiTransitionService transitionService = mock(MenuGuiTransitionService.class);
        GuiNavigationService navigationService = mock(GuiNavigationService.class);
        TrashService service = new TrashService(plugin, menuView, inventoryService, transitionService);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(plugin.getGuiNavigationService()).thenReturn(navigationService);

        Inventory confirmInventory = mock(Inventory.class);
        when(confirmInventory.getSize()).thenReturn(27);
        InventoryView confirmView = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(MenuView.TRASH_CONFIRM_RETURN_SLOT);
        when(event.getView()).thenReturn(confirmView);
        when(confirmView.getTopInventory()).thenReturn(confirmInventory);

        Inventory trashInventory = mock(Inventory.class);
        InventoryView openView = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(openView);
        when(openView.getTopInventory()).thenReturn(trashInventory);
        when(menuView.getMenuScreen(trashInventory)).thenReturn(MenuScreen.TRASH);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(inventoryService.returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class)))
            .thenReturn(InventoryType.BAG);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(transitionService).switchGuiWithInventoryRestore(eq(player), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(navigationService).openPrevious(
            eq(player),
            any(Runnable.class),
            any(Runnable.class)
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.open(player, List.of(normalizableItem()), 0);
            invokeConfirmClick(service, event, player);
        }

        verify(menuView).renderTrash(eq(trashInventory), org.mockito.ArgumentMatchers.argThat(List::isEmpty), eq(0));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 3. ゴミ箱
     * 検証契約: 戻り先の表示が取消・失敗した場合、返却済み item を保持しない確認画面だけを閉じる。
     */
    @Test
    void returningFromConfirmClosesConfirmWhenNavigationIsCancelled() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        MenuGuiTransitionService transitionService = mock(MenuGuiTransitionService.class);
        GuiNavigationService navigationService = mock(GuiNavigationService.class);
        TrashService service = new TrashService(plugin, menuView, inventoryService, transitionService);
        Player player = mock(Player.class);
        Location location = mock(Location.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(plugin.getGuiNavigationService()).thenReturn(navigationService);

        Inventory confirmInventory = mock(Inventory.class);
        when(confirmInventory.getSize()).thenReturn(27);
        InventoryView confirmView = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(MenuView.TRASH_CONFIRM_RETURN_SLOT);
        when(event.getView()).thenReturn(confirmView);
        when(confirmView.getTopInventory()).thenReturn(confirmInventory);
        InventoryView openView = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(openView);
        when(openView.getTopInventory()).thenReturn(confirmInventory);
        when(menuView.getMenuScreen(confirmInventory)).thenReturn(MenuScreen.TRASH_CONFIRM);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(inventoryService.returnItemToOwnedInventory(eq(astPlayer), any(ItemStack.class)))
            .thenReturn(InventoryType.BAG);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(transitionService).switchGuiWithInventoryRestore(eq(player), any(Runnable.class));
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        }).when(navigationService).openPrevious(
            eq(player),
            any(Runnable.class),
            any(Runnable.class)
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.open(player, List.of(normalizableItem()), 0);
            invokeConfirmClick(service, event, player);
        }

        verify(player).closeInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 3. ゴミ箱
     * 検証契約: 遷移取消 callback は、元の確認画面とは異なる同種 GUI を閉じない。
     */
    @Test
    void cancelledNavigationDoesNotCloseReplacementTrashConfirmScreen() throws Exception {
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getItemService()).thenReturn(mock(ItemService.class));
        MenuView menuView = mock(MenuView.class);
        TrashService service = new TrashService(
            plugin,
            menuView,
            mock(InventoryService.class),
            mock(MenuGuiTransitionService.class)
        );
        Player player = mock(Player.class);
        Inventory originalConfirmInventory = mock(Inventory.class);
        Inventory replacementConfirmInventory = mock(Inventory.class);
        InventoryView openView = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(openView);
        when(openView.getTopInventory()).thenReturn(replacementConfirmInventory);
        when(menuView.getMenuScreen(replacementConfirmInventory)).thenReturn(MenuScreen.TRASH_CONFIRM);

        invokeCloseReturnedTrashConfirmScreen(service, player, originalConfirmInventory);

        verify(player, never()).closeInventory();
    }

    private void invokeOpenConfirm(TrashService service, Player player, List<ItemStack> items, int pageIndex) throws Exception {
        Method method = TrashService.class.getDeclaredMethod("openTrashConfirm", Player.class, List.class, int.class);
        method.setAccessible(true);
        method.invoke(service, player, items, pageIndex);
    }

    private void invokeConfirmClick(
        TrashService service,
        InventoryClickEvent event,
        Player player
    ) throws Exception {
        Method method = TrashService.class.getDeclaredMethod(
            "handleTrashConfirmClick",
            InventoryClickEvent.class,
            Player.class
        );
        method.setAccessible(true);
        method.invoke(service, event, player);
    }

    private void invokeCloseReturnedTrashConfirmScreen(
        TrashService service,
        Player player,
        Inventory returnedConfirmInventory
    ) throws Exception {
        Method method = TrashService.class.getDeclaredMethod(
            "closeReturnedTrashConfirmScreen",
            Player.class,
            Inventory.class
        );
        method.setAccessible(true);
        method.invoke(service, player, returnedConfirmInventory);
    }

    private ItemStack normalizableItem() {
        ItemStack source = mock(ItemStack.class);
        ItemStack cleaned = mock(ItemStack.class);
        when(source.getType()).thenReturn(Material.DIAMOND);
        when(source.clone()).thenReturn(cleaned);
        when(cleaned.getType()).thenReturn(Material.DIAMOND);
        when(cleaned.clone()).thenReturn(cleaned);
        when(cleaned.getItemMeta()).thenReturn(null);
        when(cleaned.getMaxStackSize()).thenReturn(1);
        return source;
    }
}

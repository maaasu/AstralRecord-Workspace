package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.gui.ItemAdminGuiView;
import io.github.maaasu.astralRecord.feature.item.model.ItemAdminViewOptions;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemAdminGuiEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 8. 管理者item GUI操作
     * 検証契約: 次ページクリックはページを進め、同じInventoryを再描画した後にクライアント表示を同期する。
     */
    @Test
    void nextPageClickRerendersAndSynchronizesInventory() {
        ItemAdminGuiView view = mock(ItemAdminGuiView.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemAdminGuiEventHandler handler = new ItemAdminGuiEventHandler(view, itemService, inventoryService);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView inventoryView = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemModel item = mock(ItemModel.class);
        UUID playerId = UUID.randomUUID();
        List<ItemModel> items = Collections.nCopies(46, item);

        stubClick(view, itemService, event, inventoryView, inventory, player, playerId, ItemAdminGuiView.NEXT_SLOT, items);
        when(view.hasNextPage(0, items.size())).thenReturn(true);

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(view).render(eq(inventory), eq(items), eq(ItemAdminViewOptions.defaults()), eq(1));
        verify(player).updateInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 8. 管理者item GUI操作
     * 検証契約: カテゴリフィルタクリックは先頭ページへ戻り、再描画後のクライアント表示を同期する。
     */
    @Test
    void categoryFilterClickRerendersFirstPageAndSynchronizesInventory() {
        ItemAdminGuiView view = mock(ItemAdminGuiView.class);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemAdminGuiEventHandler handler = new ItemAdminGuiEventHandler(view, itemService, inventoryService);
        Player player = mock(Player.class);
        Inventory inventory = mock(Inventory.class);
        InventoryView inventoryView = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemModel item = mock(ItemModel.class);
        UUID playerId = UUID.randomUUID();
        List<ItemModel> items = Collections.nCopies(46, item);

        stubClick(view, itemService, event, inventoryView, inventory, player, playerId,
            ItemAdminGuiView.CATEGORY_FILTER_SLOT, items);
        when(item.getCategory()).thenReturn("weapon");
        when(item.getRarity()).thenReturn("common");

        handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(view).render(
            eq(inventory),
            eq(items),
            eq(new ItemAdminViewOptions("weapon", null)),
            eq(0)
        );
        verify(player).updateInventory();
    }

    private void stubClick(
        ItemAdminGuiView view,
        ItemService itemService,
        InventoryClickEvent event,
        InventoryView inventoryView,
        Inventory inventory,
        Player player,
        UUID playerId,
        int rawSlot,
        List<ItemModel> items
    ) {
        when(event.getView()).thenReturn(inventoryView);
        when(inventoryView.getTopInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(rawSlot);
        when(inventory.getSize()).thenReturn(ItemAdminGuiView.SIZE);
        when(view.isInventory(inventory)).thenReturn(true);
        when(itemService.getLoadedItems()).thenReturn(items);
        when(view.normalizePage(anyInt(), eq(items.size())))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(mock(Location.class));
    }
}

package io.github.maaasu.astralRecord.feature.shop.event;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.model.ShopAccess;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.model.ShopMode;
import io.github.maaasu.astralRecord.feature.shop.model.ShopPurchasePreview;
import io.github.maaasu.astralRecord.feature.shop.model.ShopSpecialPurchaseState;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopGuiEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/20-shop/20_4-統合フロー.md
     * 章・見出し: # 20_4-統合フロー > ## 3. Preview・購入 > ### 処理要点
     * 検証契約: 購入確定時に所持品容量が不足している場合は購入せず、P_5241を表示する。
     */
    @Test
    void fullInventoryPurchaseShowsInventoryCapacityError() {
        ShopGui shopGui = mock(ShopGui.class);
        ShopService shopService = mock(ShopService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ShopGuiEventHandler handler = new ShopGuiEventHandler(shopGui, shopService, inventoryService);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = io.github.maaasu.astralRecord.support.DesignTestFixtures.astPlayer(
            player,
            AccountMode.PLAYER
        );
        Inventory topInventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        ItemModel item = mock(ItemModel.class);
        ShopEntry entry = new ShopEntry(
            "potion",
            "potion",
            "material",
            1,
            1,
            null,
            null,
            null,
            4,
            List.of(),
            null
        );
        ShopDefinition shop = new ShopDefinition(
            "basic_shop",
            "基本ショップ",
            ShopMode.SHOP,
            ShopAccess.PUBLIC,
            List.of(entry)
        );

        when(event.getView()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(topInventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(ShopGui.BUY_SLOT);
        when(shopGui.isConfirmInventory(topInventory)).thenReturn(true);
        when(shopGui.getShopId(topInventory)).thenReturn(shop.id());
        when(shopGui.getEntryId(topInventory)).thenReturn(entry.id());
        when(shopGui.getQuantity(topInventory)).thenReturn(1);
        when(shopGui.getPageIndex(topInventory)).thenReturn(0);
        when(shopService.findById(shop.id())).thenReturn(shop);
        when(shopService.resolveItem(entry)).thenReturn(item);
        when(shopService.preview(astPlayer, entry, 1)).thenReturn(new ShopPurchasePreview(
            1, 4, 10, List.of(), List.of(), true
        ));
        when(inventoryService.canAddItemToNormalInventory(astPlayer, item, 1)).thenReturn(false);

        PlayerMessageService messages = mock(PlayerMessageService.class);
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messageService = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messageService.when(PlayerMessageService::getInstance).thenReturn(messages);

            handler.onInventoryClick(event);
        }

        verify(event).setCancelled(true);
        verify(messages).send(astPlayer, PlayerMsgId.P_5241);
        verify(shopService, never()).purchase(astPlayer, entry, 1);
    }

}

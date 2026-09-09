package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemCommandTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 4. ロード済みアイテム付与
     * 検証契約: カテゴリータグ付きのGET指定は、タグのカテゴリーと表示名の組でロード済みアイテムを解決する。
     */
    @Test
    void resolvesGetByCategoryTaggedName() {
        Player sender = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(sender);
        ItemModel item = DesignTestFixtures.item(
            "20a00001",
            "&f同名アイテム",
            ItemCategory.EQUIPMENT,
            1
        );

        ItemService itemService = mock(ItemService.class);
        when(itemService.findLoadedByCategoryAndName(
            eq(ItemCategory.EQUIPMENT.getApiValue()),
            eq("同名アイテム")
        )).thenReturn(item);
        InventoryService inventoryService = mock(InventoryService.class);
        when(inventoryService.addItemToNormalInventory(astPlayer, item, 1)).thenReturn(1);
        when(inventoryService.resolveInventoryType(item)).thenReturn(InventoryType.BAG);
        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.getInventoryService()).thenReturn(inventoryService);
        PlayerMessageService messages = mock(PlayerMessageService.class);

        try (MockedStatic<AstralRecord> pluginInstance = mockStatic(AstralRecord.class);
             MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messageInstance = mockStatic(PlayerMessageService.class)) {
            pluginInstance.when(AstralRecord::getInstance).thenReturn(plugin);
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(astPlayer);
            messageInstance.when(PlayerMessageService::getInstance).thenReturn(messages);

            new ItemCommand(itemService).executeCommand(
                sender,
                new String[] {"get", "[装備]同名アイテム"}
            );
        }

        verify(itemService).findLoadedByCategoryAndName(
            ItemCategory.EQUIPMENT.getApiValue(),
            "同名アイテム"
        );
        verify(itemService, never()).findLoadedByIdOrName(anyString());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 4. ロード済みアイテム付与
     * 検証契約: 未知のカテゴリータグはカテゴリー検索へ誤変換せず、入力全体を通常のID・表示名検索へ渡す。
     */
    @Test
    void keepsUnknownCategoryTagInFallbackLookup() {
        Player sender = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        ItemService itemService = mock(ItemService.class);
        PlayerMessageService messages = mock(PlayerMessageService.class);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messageInstance = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(sender)).thenReturn(astPlayer);
            messageInstance.when(PlayerMessageService::getInstance).thenReturn(messages);

            new ItemCommand(itemService).executeCommand(
                sender,
                new String[] {"get", "[未分類]同名アイテム"}
            );
        }

        verify(itemService).findLoadedByIdOrName("[未分類]同名アイテム");
        verify(itemService, never()).findLoadedByCategoryAndName(anyString(), anyString());
    }
}

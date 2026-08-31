package io.github.maaasu.astralRecord.feature.storage.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.menu.model.MenuScreen;
import io.github.maaasu.astralRecord.feature.menu.service.MenuGuiTransitionService;
import io.github.maaasu.astralRecord.feature.menu.view.MenuView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewEntry;
import io.github.maaasu.astralRecord.feature.storage.model.StorageViewOptions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageServiceDesignTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 6. ストレージ収納・取り出し
     * 検証契約: ストレージから実際に取得できた数量を「(+数量)」形式のP_5253で通知する。
     */
    @Test
    void storageWithdrawalNotifiesTheActuallyMovedAmount() {
        MenuView menuView = mock(MenuView.class);
        InventoryService inventoryService = mock(InventoryService.class);
        PlayerMessageService playerMessageService = mock(PlayerMessageService.class);
        StorageService service = new StorageService(
            menuView,
            inventoryService,
            mock(InventorySaveCoordinator.class),
            mock(MenuGuiTransitionService.class),
            playerMessageService
        );
        var bukkitPlayer = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        UUID accountId = astPlayer.getAccount().getUuid();
        UUID storageEntryId = UUID.randomUUID();
        ItemModel itemModel = DesignTestFixtures.item(
            "storage_notification_material", ItemCategory.MATERIAL, 64);
        ItemStack displayedItem = new ItemStack(Material.IRON_INGOT, 3);
        LocalDateTime now = LocalDateTime.now();
        StorageViewEntry sourceEntry = new StorageViewEntry(
            new InventoryEntryModel(
                storageEntryId,
                UUID.randomUUID(),
                1,
                ItemCategory.MATERIAL.getApiValue(),
                itemModel.getId(),
                null,
                null,
                3L,
                null,
                now,
                now,
                accountId,
                accountId,
                false
            ),
            displayedItem,
            itemModel,
            now
        );
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        Inventory topInventory = mock(Inventory.class);
        when(event.getWhoClicked()).thenReturn(bukkitPlayer);
        when(event.getView()).thenReturn(view);
        when(event.getRawSlot()).thenReturn(0);
        when(event.getClick()).thenReturn(ClickType.RIGHT);
        when(view.getTopInventory()).thenReturn(topInventory);
        when(topInventory.getSize()).thenReturn(MenuView.SIZE);
        when(topInventory.getItem(0)).thenReturn(displayedItem);
        when(menuView.getMenuScreen(topInventory)).thenReturn(MenuScreen.STORAGE);
        when(menuView.getStorageEntryId(displayedItem)).thenReturn(storageEntryId);
        when(inventoryService.getStorageViewEntries(any(UUID.class), any(StorageViewOptions.class)))
            .thenReturn(List.of(sourceEntry))
            .thenReturn(List.of());
        when(inventoryService.withdrawStorageEntry(astPlayer, storageEntryId, 2)).thenReturn(2);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(bukkitPlayer)).thenReturn(astPlayer);
            service.handleClick(event);
        }

        verify(playerMessageService).send(
            bukkitPlayer,
            PlayerMsgId.P_5253,
            itemModel.getName(),
            2
        );
        assertTrue(PlayerMsgResource.format(
            PlayerMsgId.P_5253.getId(), itemModel.getName(), 2).contains("(+2)"));
    }
}

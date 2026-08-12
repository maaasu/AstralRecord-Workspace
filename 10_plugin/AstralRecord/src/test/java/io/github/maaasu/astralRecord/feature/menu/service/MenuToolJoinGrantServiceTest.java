package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MenuToolJoinGrantServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 参加時メニュー導線付与
     * 検証契約: 通行証を所持しているアカウントへはメニューアイテムを追加しない。
     */
    @Test
    void existingPassSkipsAllInitialGrants() {
        UUID accountId = UUID.randomUUID();
        AstPlayer astPlayer = astPlayer(accountId);
        PlayerInventoryState state = inventoryState(accountId, true);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);

        MenuToolJoinGrantService service = new MenuToolJoinGrantService(itemService, inventoryService);

        assertNull(service.prepareIfMissing(state));

        verify(itemService, never()).findLoadedById(anyString());
        verify(itemService, never()).createEquipmentInstance(anyString(), anyString(), anyString(), anyString());
        verify(inventoryService, never()).addPreparedRewardsToNormalInventory(any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 参加時メニュー導線付与
     * 検証契約: 通行証未所持時はメニューアイテムを先に並べ、通行証を後に同一報酬更新へ渡す。
     */
    @Test
    void missingPassAddsMenuToolBeforePass() {
        UUID accountId = UUID.randomUUID();
        String instanceId = UUID.randomUUID().toString();
        AstPlayer astPlayer = astPlayer(accountId);
        PlayerInventoryState state = inventoryState(accountId, false);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemModel menuItem = item("nox_menu_tool", "equipment");
        ItemModel passItem = item("nox_city_pass", "currency");
        EquipmentInstance instance = mock(EquipmentInstance.class);
        AtomicReference<List<InventoryService.PreparedInventoryReward>> capturedRewards = new AtomicReference<>();

        when(inventoryService.getCurrencyAmount(accountId, MenuToolJoinGrantService.PASS_ITEM_ID)).thenReturn(0L);
        when(itemService.findLoadedById(MenuToolJoinGrantService.MENU_ITEM_ID)).thenReturn(menuItem);
        when(itemService.findLoadedById(MenuToolJoinGrantService.PASS_ITEM_ID)).thenReturn(passItem);
        when(itemService.createEquipmentInstance(
            eq(MenuToolJoinGrantService.MENU_ITEM_ID),
            eq(accountId.toString()),
            anyString(),
            eq(accountId.toString())
        )).thenReturn(instance);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId);
        MenuToolJoinGrantService service = new MenuToolJoinGrantService(itemService, inventoryService);
        MenuToolJoinGrantService.PreparedGrant preparedGrant = service.prepareIfMissing(state);
        when(inventoryService.getCurrencyAmount(accountId, MenuToolJoinGrantService.PASS_ITEM_ID)).thenReturn(0L);
        when(inventoryService.addPreparedRewardsToNormalInventory(eq(astPlayer), any())).thenAnswer(invocation -> {
            capturedRewards.set(invocation.getArgument(1));
            return new InventoryService.InventoryGrantReceipt(accountId, List.of());
        });

        service.grantPreparedIfMissing(astPlayer, preparedGrant);

        verify(inventoryService).addPreparedRewardsToNormalInventory(eq(astPlayer), any());
        List<InventoryService.PreparedInventoryReward> rewards = capturedRewards.get();
        assertEquals(2, rewards.size());
        assertEquals(menuItem, rewards.get(0).model());
        assertEquals(passItem, rewards.get(1).model());
        verify(itemService, never()).deleteEquipmentInstance(instanceId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/03-player/3-メソッド仕様/03_3-サービス.md
     * 章・見出し: # 03_3-サービス > ## 1. service メソッド仕様 > ### 参加時メニュー導線付与
     * 検証契約: 報酬更新に失敗した場合、呼び出し側が準備済み装備インスタンスを削除して未参照個体を残さない。
     */
    @Test
    void failedRewardUpdateDeletesPreparedEquipment() {
        UUID accountId = UUID.randomUUID();
        String instanceId = UUID.randomUUID().toString();
        AstPlayer astPlayer = astPlayer(accountId);
        PlayerInventoryState state = inventoryState(accountId, false);
        ItemService itemService = mock(ItemService.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemModel menuItem = item("nox_menu_tool", "equipment");
        ItemModel passItem = item("nox_city_pass", "currency");
        EquipmentInstance instance = mock(EquipmentInstance.class);

        when(inventoryService.getCurrencyAmount(accountId, MenuToolJoinGrantService.PASS_ITEM_ID)).thenReturn(0L);
        when(itemService.findLoadedById(MenuToolJoinGrantService.MENU_ITEM_ID)).thenReturn(menuItem);
        when(itemService.findLoadedById(MenuToolJoinGrantService.PASS_ITEM_ID)).thenReturn(passItem);
        when(itemService.createEquipmentInstance(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(instance);
        when(instance.getEquipmentInstanceId()).thenReturn(instanceId);
        MenuToolJoinGrantService service = new MenuToolJoinGrantService(itemService, inventoryService);
        MenuToolJoinGrantService.PreparedGrant preparedGrant = service.prepareIfMissing(state);
        when(inventoryService.getCurrencyAmount(accountId, MenuToolJoinGrantService.PASS_ITEM_ID)).thenReturn(0L);
        when(inventoryService.addPreparedRewardsToNormalInventory(eq(astPlayer), any())).thenReturn(null);

        assertThrows(
            IllegalStateException.class,
            () -> service.grantPreparedIfMissing(astPlayer, preparedGrant)
        );

        service.cleanupPreparedGrant(preparedGrant);
        verify(itemService).deleteEquipmentInstance(instanceId);
    }

    private PlayerInventoryState inventoryState(UUID accountId, boolean hasPass) {
        PlayerInventoryState state = mock(PlayerInventoryState.class);
        InventoryModel currencyInventory = mock(InventoryModel.class);
        UUID inventoryId = UUID.randomUUID();
        when(state.getAccountId()).thenReturn(accountId);
        when(state.findInventory(InventoryProfile.GAME, InventoryType.CURRENCY)).thenReturn(currencyInventory);
        when(currencyInventory.getInventoryId()).thenReturn(inventoryId);
        when(currencyInventory.isEnabled()).thenReturn(true);
        when(currencyInventory.isDeleted()).thenReturn(false);
        if (!hasPass) {
            when(state.snapshotEntries(inventoryId)).thenReturn(List.of());
            return state;
        }
        InventoryEntryModel passEntry = mock(InventoryEntryModel.class);
        when(passEntry.isDeleted()).thenReturn(false);
        when(passEntry.getQuantity()).thenReturn(1L);
        when(passEntry.getItemId()).thenReturn(MenuToolJoinGrantService.PASS_ITEM_ID);
        when(state.snapshotEntries(inventoryId)).thenReturn(List.of(passEntry));
        return state;
    }

    private AstPlayer astPlayer(UUID accountId) {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(account);
        return astPlayer;
    }

    private ItemModel item(String id, String category) {
        ItemModel item = mock(ItemModel.class);
        when(item.getId()).thenReturn(id);
        when(item.getCategory()).thenReturn(category);
        return item;
    }
}

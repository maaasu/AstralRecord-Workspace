package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.entity.Player;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

class InventoryServiceHandModeBoundaryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 1. state 参照 > ### 手持ちアイテム参照のモード境界
     * 検証契約: PLAYERからADMINへ切り替えると両手の参照と消費を拒否し、PLAYERへ戻すと保持された同じentryを参照できる。
     */
    @Test
    void adminHandsCannotResolveOrConsumeRetainedPlayerHotbar() {
        UUID accountId = UUID.randomUUID();
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        Player bukkit = mock(Player.class);
        PlayerInventory displayed = mock(PlayerInventory.class);
        when(bukkit.getInventory()).thenReturn(displayed);
        when(displayed.getHeldItemSlot()).thenReturn(0);
        AstPlayer player = mock(AstPlayer.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkit);
        PlayerInventoryState state = new PlayerInventoryState(accountId);
        InventoryModel hotbar = DesignTestFixtures.inventory(accountId, InventoryType.HOTBAR);
        state.putInventory(hotbar);
        InventoryEntryModel mainHand = entry(accountId, hotbar.getInventoryId(), 1);
        InventoryEntryModel offHand = entry(accountId, hotbar.getInventoryId(), HotbarLayout.DB_SLOT_OFFHAND);
        state.replaceEntriesFromLoad(hotbar.getInventoryId(), List.of(mainHand, offHand));
        PlayerInventoryStateRegistry registry = new PlayerInventoryStateRegistry();
        registry.put(state);
        ItemService items = mock(ItemService.class);
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class), mock(EquipmentLoadoutRepository.class), items,
            mock(ItemStackFactory.class), registry, mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );

        assertSame(mainHand, service.getHotbarEntryInHand(player, EquipmentSlot.HAND));
        assertSame(offHand, service.getHotbarEntryInHand(player, EquipmentSlot.OFF_HAND));
        when(account.getMode()).thenReturn(AccountMode.ADMIN);
        for (EquipmentSlot hand : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
            assertNull(service.getHotbarEntryInHand(player, hand));
            assertNull(service.getItemReferenceInHand(player, hand));
            assertNull(service.getItemModelInHand(player, hand));
            assertFalse(service.consumeHotbarItemInHand(player, hand, "retained_item", 1));
        }
        assertEquals(List.of(mainHand, offHand), state.snapshotEntries(hotbar.getInventoryId()));
        verifyNoInteractions(items);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        assertSame(mainHand, service.getHotbarEntryInHand(player, EquipmentSlot.HAND));
        assertSame(offHand, service.getHotbarEntryInHand(player, EquipmentSlot.OFF_HAND));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 4. 表示インベントリ GUI 反映
     * 検証契約: PLAYERで予約した次tickの再描画も、実行前にADMINへ切り替えた場合はBukkitインベントリへアクセスしない。
     */
    @Test
    void queuedPlayerRefreshCannotWriteAfterSwitchingToAdmin() {
        AccountModel account = mock(AccountModel.class);
        when(account.getUuid()).thenReturn(UUID.randomUUID());
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        Player bukkit = mock(Player.class);
        when(bukkit.isOnline()).thenReturn(true);
        AstPlayer player = mock(AstPlayer.class);
        when(player.getAccount()).thenReturn(account);
        when(player.getBukkit()).thenReturn(bukkit);
        InventoryService service = new InventoryService(
            mock(InventoryRepository.class), mock(EquipmentLoadoutRepository.class), mock(ItemService.class),
            mock(ItemStackFactory.class), new PlayerInventoryStateRegistry(), mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class)
        );
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        AtomicReference<Runnable> queuedRefresh = new AtomicReference<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            queuedRefresh.set(invocation.getArgument(1));
            return null;
        });

        try (MockedStatic<AstralRecord> plugins = mockStatic(AstralRecord.class)) {
            plugins.when(AstralRecord::getInstance).thenReturn(plugin);
            service.refreshManagedInventoryUi(player);
        }
        verify(bukkit).updateInventory();
        clearInvocations(bukkit);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);
        queuedRefresh.get().run();
        verifyNoInteractions(bukkit);
    }

    private static InventoryEntryModel entry(UUID accountId, UUID inventoryId, int slot) {
        LocalDateTime now = LocalDateTime.now();
        return new InventoryEntryModel(
            UUID.randomUUID(), inventoryId, slot, "ITEM", "retained_item", null, null, 1L,
            null, now, now, accountId, accountId, false
        );
    }
}

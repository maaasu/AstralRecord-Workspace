package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.repository.EquipmentLoadoutRepository;
import io.github.maaasu.astralRecord.feature.inventory.repository.InventoryRepository;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryServiceTradeReservationTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信画面の開始・終了
     * 検証契約: 終了時の予約解除は表示障害に影響されず、別の予約残量を維持する。
     */
    @Test
    void releaseAfterCancellationDoesNotDependOnRenderingOrClearOtherReservations() throws Exception {
        Context context = new Context();
        UUID otherEntry = UUID.randomUUID();
        context.reservations.put(context.accountId,
            new ConcurrentHashMap<>(Map.of(context.entryId, 5, otherEntry, 4)));
        context.service.releaseHiddenEntryQuantity(context.accountId, context.entryId, 3);
        assertEquals(Map.of(context.entryId, 2, otherEntry, 4), context.reservations.get(context.accountId));
    }
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 2. 提示
     * 検証契約: 予約追加後のBukkit表示更新が失敗した場合、既存予約数量を維持する。
     */
    @Test
    void failedDisplayRefreshRollsBackOnlyNewReservation() throws Exception {
        Context context = new Context();
        context.reservations.put(context.accountId, new ConcurrentHashMap<>(Map.of(context.entryId, 2)));
        assertThrows(IllegalStateException.class, () ->
            context.service.hideOwnedEntryQuantityFromGui(context.astPlayer, context.entryId, 3));
        assertEquals(2, context.reservations.get(context.accountId).get(context.entryId));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_4-統合フロー.md
     * 章・見出し: # 22_4-統合フロー > ## 2. 提示
     * 検証契約: 全量取り下げ後のBukkit表示更新が失敗した場合、削除済み予約を元の数量で復元する。
     */
    @Test
    void failedDisplayRefreshRestoresFullyWithdrawnReservation() throws Exception {
        Context context = new Context();
        context.reservations.put(context.accountId, new ConcurrentHashMap<>(Map.of(context.entryId, 2)));
        assertThrows(IllegalStateException.class, () ->
            context.service.restoreHiddenEntryQuantityToGui(context.astPlayer, context.entryId, 2));
        assertEquals(2, context.reservations.get(context.accountId).get(context.entryId));
    }

    private static final class Context {
        private final UUID accountId = UUID.randomUUID();
        private final UUID entryId = UUID.randomUUID();
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final InventoryService service = new InventoryService(
            mock(InventoryRepository.class), mock(EquipmentLoadoutRepository.class), mock(ItemService.class),
            mock(ItemStackFactory.class), new PlayerInventoryStateRegistry(), mock(InventoryPersistence.class),
            mock(InventorySaveCoordinator.class));
        private final Map<UUID, Map<UUID, Integer>> reservations;

        @SuppressWarnings("unchecked")
        private Context() throws Exception {
            AccountModel account = mock(AccountModel.class);
            Player player = mock(Player.class);
            when(astPlayer.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(astPlayer.getBukkit()).thenReturn(player);
            doThrow(new IllegalStateException("display unavailable")).when(player).updateInventory();
            Field field = InventoryService.class.getDeclaredField("temporarilyHiddenEntryQuantitiesByAccount");
            field.setAccessible(true);
            reservations = (Map<UUID, Map<UUID, Integer>>) field.get(service);
        }
    }
}

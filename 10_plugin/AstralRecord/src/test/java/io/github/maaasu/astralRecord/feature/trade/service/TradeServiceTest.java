package io.github.maaasu.astralRecord.feature.trade.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemReferenceResolver;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Item 提示・取り下げ
     * 検証契約: 表示itemを正本にせず所有slotから実itemを取り出して解決した後だけescrowへ追加する。
     */
    @Test
    void offerTakesAuthoritativeOwnedItemBeforeAddingEscrow() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = itemStack(4);
        ItemStack moved = itemStack(4);
        when(context.inventoryService.takeOwnedItemAmount(context.astPlayer, 9, 4)).thenReturn(moved);
        when(context.itemReferenceResolver.resolveItemModel(moved)).thenReturn(context.itemModel);

        boolean offered;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);

            offered = context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_LEFT, displayed);
        }

        assertTrue(offered);
        assertEquals(1, context.session.getItems(context.playerId).size());
        assertEquals(4, context.session.getItems(context.playerId).getFirst().getAmount());
        verify(context.inventoryService).getOwnedItemModelAtBukkitSlot(context.astPlayer, 9);
        verify(context.inventoryService).takeOwnedItemAmount(context.astPlayer, 9, 4);
        verify(context.inventoryService, never()).restoreState(context.snapshot);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## Item 提示・取り下げ
     * 検証契約: 取り出したitemのmodelを解決できなければescrowを変更せずinventory snapshotを復元する。
     */
    @Test
    void unresolvedTakenItemFailsClosedAndRestoresInventorySnapshot() throws Exception {
        TestContext context = new TestContext();
        ItemStack displayed = itemStack(4);
        ItemStack moved = itemStack(4);
        when(context.inventoryService.takeOwnedItemAmount(context.astPlayer, 9, 4)).thenReturn(moved);
        when(context.itemReferenceResolver.resolveItemModel(moved)).thenReturn(null);

        boolean offered;
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);

            offered = context.service.offerOwnedItem(context.player, 9, ClickType.SHIFT_LEFT, displayed);
        }

        assertFalse(offered);
        assertTrue(context.session.getItems(context.playerId).isEmpty());
        verify(context.inventoryService).restoreState(context.snapshot);
    }

    private static final class TestContext {
        private final UUID playerId = UUID.randomUUID();
        private final UUID partnerId = UUID.randomUUID();
        private final UUID accountId = UUID.randomUUID();
        private final UUID partnerAccountId = UUID.randomUUID();
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final TradeCancelConfirmGui cancelConfirmGui = mock(TradeCancelConfirmGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final CurrencyService currencyService = mock(CurrencyService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final ItemReferenceResolver itemReferenceResolver = mock(ItemReferenceResolver.class);
        private final Player player = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final AccountModel account = mock(AccountModel.class);
        private final ItemModel itemModel = mock(ItemModel.class);
        private final InventoryService.InventoryStateSnapshot snapshot =
            new InventoryService.InventoryStateSnapshot(accountId, Map.of(), InventoryType.BAG, false);
        private final TradeSession session = new TradeSession(
            UUID.randomUUID(),
            playerId,
            accountId,
            "tester",
            partnerId,
            partnerAccountId,
            "partner",
            Instant.now()
        );
        private final TradeService service = new TradeService(
            plugin,
            tradeGui,
            cancelConfirmGui,
            goldAmountSettingGui,
            inventoryService,
            currencyService,
            messageService,
            itemReferenceResolver
        );

        private TestContext() throws Exception {
            when(player.getUniqueId()).thenReturn(playerId);
            when(astPlayer.getAccount()).thenReturn(account);
            when(account.getUuid()).thenReturn(accountId);
            when(itemModel.getUnTradeable()).thenReturn(false);
            when(inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, 9)).thenReturn(itemModel);
            when(inventoryService.snapshotState(accountId)).thenReturn(snapshot);
            registerSession(service, session);
        }

        @SuppressWarnings("unchecked")
        private static void registerSession(TradeService service, TradeSession session) throws Exception {
            Field sessionsField = TradeService.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            ((Map<UUID, TradeSession>) sessionsField.get(service)).put(session.getSessionId(), session);
            Field activeField = TradeService.class.getDeclaredField("activeSessionByPlayer");
            activeField.setAccessible(true);
            ((Map<UUID, UUID>) activeField.get(service)).put(
                session.getPlayerAUuid(),
                session.getSessionId()
            );
        }
    }

    private static ItemStack itemStack(int amount) {
        ItemStack itemStack = mock(ItemStack.class);
        when(itemStack.getType()).thenReturn(Material.STONE);
        when(itemStack.getAmount()).thenReturn(amount);
        when(itemStack.getMaxStackSize()).thenReturn(64);
        when(itemStack.clone()).thenReturn(itemStack);
        return itemStack;
    }
}

package io.github.maaasu.astralRecord.feature.market.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventorySaveCoordinator;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.market.gui.MarketScreen;
import io.github.maaasu.astralRecord.feature.market.gui.MarketGui;
import io.github.maaasu.astralRecord.feature.market.model.MarketListing;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingDraft;
import io.github.maaasu.astralRecord.feature.market.model.MarketListingSource;
import io.github.maaasu.astralRecord.feature.market.service.MarketService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketGuiEventHandlerTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: MY_LISTINGSの下部所持品クリックは出品候補を選択し、同種の出品があっても取り下げ確認へ遷移しない。
     */
    @Test
    void myListingsPlayerInventoryClickStartsSellConfigInsteadOfCancelConfirmation() {
        InventoryService inventoryService = mock(InventoryService.class);
        MarketService marketService = mock(MarketService.class);
        MarketGuiEventHandler handler = handler(inventoryService, marketService);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        InventoryEntryModel entry = stackEntry(astPlayer.getAccount().getUuid());
        ItemModel item = item();
        when(inventoryService.getOwnedEntryAtBukkitSlot(astPlayer, 0)).thenReturn(entry);
        when(inventoryService.getOwnedItemModelAtBukkitSlot(astPlayer, 0)).thenReturn(item);
        when(inventoryService.getOwnedStackEntries(astPlayer, entry.getItemCategory(), entry.getItemId()))
            .thenReturn(List.of(entry));

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getClickedInventory()).thenReturn(player.getInventory());
        when(event.getSlot()).thenReturn(0);
        when(event.getRawSlot()).thenReturn(54);

        Object session = newMarketSession();
        setSessionField(session, "ownListings", true);
        setSessionField(session, "screen", MarketScreen.MY_LISTINGS);

        invoke(handler, "handleListingsClick",
            new Class<?>[] { InventoryClickEvent.class, Player.class, session.getClass() },
            event, player, session);

        assertEquals(MarketScreen.SELL_CONFIG, getSessionField(session, "screen"));
        verify(marketService, never()).cancel(any(), any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/23-market/23_4-統合フロー.md
     * 章・見出し: # 23_4-統合フロー > ## 5. サーバー内 GUI の出品・購入
     * 検証契約: 出品・購入・取り下げ・売上受取の4確定callbackは成功時だけBukkit所持品表示を一度更新し、失敗時は更新しない。
     */
    @Test
    void successfulMarketMutationCallbacksRefreshInventoryUiOnlyOnSuccess() {
        InventoryService inventoryService = mock(InventoryService.class);
        InventorySaveCoordinator coordinator = mock(InventorySaveCoordinator.class);
        MarketGuiEventHandler handler = handler(inventoryService, mock(MarketService.class), coordinator);
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        AstPlayerCache.put(astPlayer);
        UUID accountId = astPlayer.getAccount().getUuid();
        MarketListing listing = listing(accountId, "ACTIVE", 0L);

        completeMarketMutation(coordinator, CompletableFuture.completedFuture(null));
        invokeMarketMutationCallbacks(handler, player, accountId, listing);
        server().getScheduler().performOneTick();

        verify(inventoryService, times(4)).refreshManagedInventoryUi(astPlayer);

        completeMarketMutation(coordinator, CompletableFuture.failedFuture(new IllegalStateException("failure")));
        invokeMarketMutationCallbacks(handler, player, accountId, listing);
        server().getScheduler().performOneTick();

        // 失敗 callback では API 正本との同期が完了していないため、表示更新しない。
        verify(inventoryService, times(4)).refreshManagedInventoryUi(astPlayer);
    }

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    private static MarketGuiEventHandler handler(
        InventoryService inventoryService,
        MarketService marketService
    ) {
        return handler(inventoryService, marketService, mock(InventorySaveCoordinator.class));
    }

    private static MarketGuiEventHandler handler(
        InventoryService inventoryService,
        MarketService marketService,
        InventorySaveCoordinator inventorySaveCoordinator
    ) {
        return new MarketGuiEventHandler(
            mock(AstralRecord.class),
            mock(ItemService.class),
            mock(MarketGui.class),
            marketService,
            inventoryService,
            inventorySaveCoordinator,
            mock(CurrencyService.class),
            mock(PlayerMessageService.class),
            mock(GoldAmountSettingGui.class)
        );
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void completeMarketMutation(
        InventorySaveCoordinator coordinator,
        CompletableFuture<?> result
    ) {
        doReturn(result).when(coordinator).executeExclusiveAfterSave(
            any(UUID.class),
            any(Function.class)
        );
    }

    private static void invokeMarketMutationCallbacks(
        MarketGuiEventHandler handler,
        Player player,
        UUID accountId,
        MarketListing listing
    ) {
        invoke(handler, "submitListing",
            new Class<?>[] { Player.class, newMarketSession().getClass(), MarketListingDraft.class },
            player, newMarketSession(), draft(accountId));
        invoke(handler, "purchaseListing",
            new Class<?>[] { Player.class, newMarketSession().getClass(), MarketListing.class, long.class },
            player, newMarketSession(), listing, 1L);
        invoke(handler, "cancelListing",
            new Class<?>[] { Player.class, newMarketSession().getClass(), MarketListing.class },
            player, newMarketSession(), listing);
        invoke(handler, "claimProceeds",
            new Class<?>[] { Player.class, newMarketSession().getClass(), MarketListing.class },
            player, newMarketSession(), listing(accountId, "SOLD", 1L));
    }

    private static MarketListingDraft draft(UUID accountId) {
        return new MarketListingDraft(
            UUID.randomUUID(),
            List.of(new MarketListingSource(UUID.randomUUID(), 1L)),
            ItemCategory.MATERIAL.getApiValue(),
            "market_test_material",
            null,
            null,
            1L,
            1L
        );
    }

    private static MarketListing listing(UUID accountId, String status, long pendingProceeds) {
        Instant now = Instant.parse("2026-08-17T00:00:00Z");
        return new MarketListing(
            UUID.randomUUID(), accountId, "market-test", null, UUID.randomUUID(),
            ItemCategory.MATERIAL.getApiValue(), "market_test_material", null, null,
            1L, 1L, "gold", 1L, 1L, 1L, null, null, "HIGH", null, null,
            status, null, now, now.plusSeconds(86_400L), null, null, 1, now, now,
            pendingProceeds, List.of()
        );
    }

    private static InventoryEntryModel stackEntry(UUID accountId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 17, 0, 0);
        return new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            ItemCategory.MATERIAL.getApiValue(),
            "market_test_material",
            null,
            null,
            1L,
            null,
            now,
            now,
            accountId,
            accountId,
            false
        );
    }

    private static ItemModel item() {
        return new ItemModel(
            1,
            "market_test_material",
            ItemCategory.MATERIAL.getApiValue(),
            "検証素材",
            "STONE",
            "COMMON",
            64,
            1,
            null,
            null,
            List.of(),
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    private static Object newMarketSession() {
        try {
            var constructor = Class.forName(MarketGuiEventHandler.class.getName() + "$MarketSession")
                .getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void setSessionField(Object session, String name, Object value) {
        try {
            Field field = session.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(session, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Object getSessionField(Object session, String name) {
        try {
            Field field = session.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(session);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            fail(cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}

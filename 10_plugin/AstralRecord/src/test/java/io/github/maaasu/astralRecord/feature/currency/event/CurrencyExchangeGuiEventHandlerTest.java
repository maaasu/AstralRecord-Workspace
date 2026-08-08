package io.github.maaasu.astralRecord.feature.currency.event;

import io.github.maaasu.astralRecord.feature.currency.model.GoldDenomination;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.currency.view.CurrencyExchangeGuiView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CurrencyExchangeGuiEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 5. 共通 GUI セッション遷移
     * 検証契約: 両替 GUI の close button は inventory close だけを要求し、CLOSE 音の判定・再生を共有 GUI lifecycle と二重化しない。
     */
    @Test
    void closeButtonDelegatesCloseSoundToTheSharedLifecycle() throws Exception {
        CurrencyExchangeGuiEventHandler handler = new CurrencyExchangeGuiEventHandler(mock(CurrencyService.class));
        Player player = mock(Player.class);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getRawSlot()).thenReturn(CurrencyExchangeGuiView.CLOSE_SLOT);

        Method method = CurrencyExchangeGuiEventHandler.class.getDeclaredMethod(
            "handleClick",
            InventoryClickEvent.class,
            Player.class
        );
        method.setAccessible(true);
        method.invoke(handler, event, player);

        verify(player).closeInventory();
        verifyNoMoreInteractions(player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/16-currency/16_3-メソッド仕様.md
     * 章・見出し: # 16_3-メソッド仕様 > ## 両替 GUI クリック
     * 検証契約: 両替GUIの既取消済みclickも受付け、eventをcancelしたまま左clickを1口の上位額面交換へ委譲する。
     */
    @Test
    void handlesExchangeClicksEvenWhenVanillaMovementWasAlreadyCancelled() throws Exception {
        Method method = CurrencyExchangeGuiEventHandler.class.getMethod(
            "onInventoryClick",
            InventoryClickEvent.class
        );
        assertFalse(method.getAnnotation(EventHandler.class).ignoreCancelled());

        CurrencyService currencyService = mock(CurrencyService.class);
        CurrencyExchangeGuiEventHandler handler = new CurrencyExchangeGuiEventHandler(currencyService);
        Player player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class, RETURNS_DEEP_STUBS);
        UUID accountId = UUID.randomUUID();
        when(astPlayer.getAccount().getUuid()).thenReturn(accountId);
        when(currencyService.exchangeUp(accountId, GoldDenomination.GOLD, false)).thenReturn(true);

        Inventory top = Bukkit.createInventory(new CurrencyExchangeGuiView.Holder(), 27);
        InventoryView inventoryView = player.openInventory(top);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(inventoryView);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(10);
        when(event.isLeftClick()).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.onInventoryClick(event);
        }

        verify(event).setCancelled(true);
        verify(currencyService).exchangeUp(accountId, GoldDenomination.GOLD, false);
    }
}

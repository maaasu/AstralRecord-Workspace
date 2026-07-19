package io.github.maaasu.astralRecord.feature.trade.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.currency.service.CurrencyService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeCancelConfirmGui;
import io.github.maaasu.astralRecord.feature.trade.gui.TradeGui;
import io.github.maaasu.astralRecord.feature.trade.service.TradeService;
import io.github.maaasu.astralRecord.shared.gui.gold.GoldAmountSettingGui;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradeGuiEventHandlerTest {

    @Test
    void cancelsVanillaMovementAndDelegatesBottomSlotOfferToTradeService() {
        TestContext context = new TestContext();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        ItemStack displayed = mock(ItemStack.class);
        when(displayed.getType()).thenReturn(Material.STONE);
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(context.player);
        when(event.getClickedInventory()).thenReturn(playerInventory);
        when(event.getRawSlot()).thenReturn(54);
        when(event.getSlot()).thenReturn(9);
        when(event.getClick()).thenReturn(ClickType.LEFT);
        when(event.getCurrentItem()).thenReturn(displayed);
        when(context.tradeService.isTradeable(displayed)).thenReturn(true);
        when(context.tradeService.offerOwnedItem(context.player, 9, ClickType.LEFT, displayed)).thenReturn(true);

        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);

            context.handler.onInventoryClick(event);
        }

        verify(event).setCancelled(true);
        verify(context.tradeService).offerOwnedItem(context.player, 9, ClickType.LEFT, displayed);
    }

    @Test
    void cancelsEveryDragOverTradeInventory() {
        TestContext context = new TestContext();
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(context.player);

        try (MockedStatic<AccountModeGuard> guard = mockStatic(AccountModeGuard.class)) {
            guard.when(() -> AccountModeGuard.isGameplayPlayer(context.player)).thenReturn(true);

            context.handler.onInventoryDrag(event);
        }

        verify(event).setCancelled(true);
    }

    private static final class TestContext {
        private final AstralRecord plugin = mock(AstralRecord.class);
        private final TradeGui tradeGui = mock(TradeGui.class);
        private final TradeCancelConfirmGui cancelConfirmGui = mock(TradeCancelConfirmGui.class);
        private final GoldAmountSettingGui goldAmountSettingGui = mock(GoldAmountSettingGui.class);
        private final TradeService tradeService = mock(TradeService.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final PlayerMessageService messageService = mock(PlayerMessageService.class);
        private final Player player = mock(Player.class);
        private final Inventory top = mock(Inventory.class);
        private final InventoryView view = mock(InventoryView.class);
        private final TradeGuiEventHandler handler;

        private TestContext() {
            when(view.getTopInventory()).thenReturn(top);
            when(top.getSize()).thenReturn(54);
            when(tradeGui.isTradeInventory(top)).thenReturn(true);
            when(player.getName()).thenReturn("tester");
            when(player.getLocation()).thenReturn(mock(Location.class));
            handler = new TradeGuiEventHandler(
                plugin,
                tradeGui,
                cancelConfirmGui,
                goldAmountSettingGui,
                tradeService,
                inventoryService,
                messageService
            );
        }
    }
}

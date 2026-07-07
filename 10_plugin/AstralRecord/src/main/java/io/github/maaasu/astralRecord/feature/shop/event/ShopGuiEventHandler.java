package io.github.maaasu.astralRecord.feature.shop.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.jetbrains.annotations.NotNull;

public final class ShopGuiEventHandler extends AbstractEventHandler {
    private final ShopGui shopGui;
    private final ShopService shopService;
    private final InventoryService inventoryService;

    public ShopGuiEventHandler(
        @NotNull ShopGui shopGui,
        @NotNull ShopService shopService,
        @NotNull InventoryService inventoryService
    ) {
        this.shopGui = shopGui;
        this.shopService = shopService;
        this.inventoryService = inventoryService;
    }

    public void open(@NotNull Player player, @NotNull String shopId) {
        if (!AccountModeGuard.isGameplayPlayer(player)) {
            GuiSound.DENY.play(player);
            return;
        }
        ShopDefinition shop = shopService.findById(shopId);
        if (shop == null) {
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.OPEN.play(player);
        shopGui.openList(player, shop);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (shopGui.isListInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    if (!AccountModeGuard.isGameplayPlayer(player)) {
                        player.closeInventory();
                        return;
                    }
                    handleListClick(event, player);
                }
                return;
            }
            if (shopGui.isConfirmInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    if (!AccountModeGuard.isGameplayPlayer(player)) {
                        player.closeInventory();
                        return;
                    }
                    handleConfirmClick(event, player);
                }
            }
        }, LogId.E_5200, event.getWhoClicked().getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        runSafely(() -> {
            if (shopGui.isListInventory(event.getView().getTopInventory())
                || shopGui.isConfirmInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player
                    && !AccountModeGuard.isGameplayPlayer(player)) {
                    player.closeInventory();
                }
            }
        }, LogId.E_5200, event.getWhoClicked().getName());
    }

    private void handleListClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        String shopId = shopGui.getShopId(event.getView().getTopInventory());
        if (shopId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        ShopDefinition shop = shopService.findById(shopId);
        int pageIndex = shopGui.getPageIndex(event.getView().getTopInventory());
        if (shop == null) {
            GuiSound.DENY.play(player);
            return;
        }
        if (event.getRawSlot() == ShopGui.PREVIOUS_PAGE_SLOT && shopGui.hasPreviousPage(pageIndex)) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openList(player, shop, pageIndex - 1);
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() == ShopGui.NEXT_PAGE_SLOT && shopGui.hasNextPage(shop, pageIndex)) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openList(player, shop, pageIndex + 1);
            GuiSound.SELECT.play(player);
            return;
        }
        String entryId = shopGui.getEntryId(event.getCurrentItem());
        if (entryId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        ShopEntry entry = shop.findEntry(entryId);
        var astPlayer = AstPlayerCache.get(player);
        if (entry == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        MenuOpenEventHandler.suppressNextCloseSound(player);
        shopGui.openConfirm(player, shop, entry, 1, shopService.preview(astPlayer, entry, 1), pageIndex);
        GuiSound.SELECT.play(player);
    }

    private void handleConfirmClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        String shopId = shopGui.getShopId(event.getView().getTopInventory());
        String entryId = shopGui.getEntryId(event.getView().getTopInventory());
        ShopDefinition shop = shopId == null ? null : shopService.findById(shopId);
        ShopEntry entry = shop == null || entryId == null ? null : shop.findEntry(entryId);
        var astPlayer = AstPlayerCache.get(player);
        if (shop == null || entry == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        int quantity = shopGui.getQuantity(event.getView().getTopInventory());
        int pageIndex = shopGui.getPageIndex(event.getView().getTopInventory());
        if (event.getRawSlot() == ShopGui.CONFIRM_BACK_SLOT) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openList(player, shop, pageIndex);
            GuiSound.SELECT.play(player);
            return;
        }
        int nextQuantity = switch (event.getRawSlot()) {
            case ShopGui.QUANTITY_MINUS_10_SLOT -> Math.max(1, quantity - 10);
            case ShopGui.QUANTITY_MINUS_1_SLOT -> Math.max(1, quantity - 1);
            case ShopGui.QUANTITY_PLUS_1_SLOT -> quantity + 1;
            case ShopGui.QUANTITY_PLUS_10_SLOT -> quantity + 10;
            default -> quantity;
        };
        if (nextQuantity != quantity) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openConfirm(player, shop, entry, nextQuantity, shopService.preview(astPlayer, entry, nextQuantity), pageIndex);
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() != ShopGui.BUY_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!shopService.purchase(astPlayer, entry, quantity)) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openConfirm(player, shop, entry, quantity, shopService.preview(astPlayer, entry, quantity), pageIndex);
            GuiSound.DENY.play(player);
            return;
        }
        MenuOpenEventHandler.suppressNextCloseSound(player);
        shopGui.openConfirm(player, shop, entry, quantity, shopService.preview(astPlayer, entry, quantity), pageIndex);
        GuiSound.SELECT.play(player);
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        return HotbarShortcutClickSupport.handle(event, player, inventoryService);
    }
}

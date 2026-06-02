package io.github.maaasu.astralRecord.feature.shop.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.menu.event.MenuOpenEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.shop.model.ShopDefinition;
import io.github.maaasu.astralRecord.feature.shop.model.ShopEntry;
import io.github.maaasu.astralRecord.feature.shop.service.ShopService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.PlayerInventory;
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
        ShopDefinition shop = shopService.findById(shopId);
        if (shop == null) {
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.OPEN.play(player);
        shopGui.openList(player, shop);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (shopGui.isListInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    handleListClick(event, player);
                }
                return;
            }
            if (shopGui.isConfirmInventory(event.getView().getTopInventory())) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
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
            }
        }, LogId.E_5200, event.getWhoClicked().getName());
    }

    private void handleListClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (handleHotbarShortcutClick(event, player)) {
            return;
        }
        String shopId = shopGui.getShopId(event.getView().getTopInventory());
        String entryId = shopGui.getEntryId(event.getCurrentItem());
        if (shopId == null || entryId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        ShopDefinition shop = shopService.findById(shopId);
        ShopEntry entry = shop == null ? null : shop.findEntry(entryId);
        var astPlayer = AstPlayerCache.get(player);
        if (shop == null || entry == null || astPlayer == null) {
            GuiSound.DENY.play(player);
            return;
        }
        MenuOpenEventHandler.suppressNextCloseSound(player);
        shopGui.openConfirm(player, shop, entry, 1, shopService.preview(astPlayer, entry, 1));
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
        if (event.getRawSlot() == ShopGui.CONFIRM_BACK_SLOT) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openList(player, shop);
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
            shopGui.openConfirm(player, shop, entry, nextQuantity, shopService.preview(astPlayer, entry, nextQuantity));
            GuiSound.SELECT.play(player);
            return;
        }
        if (event.getRawSlot() != ShopGui.BUY_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        if (!shopService.purchase(astPlayer, entry, quantity)) {
            MenuOpenEventHandler.suppressNextCloseSound(player);
            shopGui.openConfirm(player, shop, entry, quantity, shopService.preview(astPlayer, entry, quantity));
            GuiSound.DENY.play(player);
            return;
        }
        MenuOpenEventHandler.suppressNextCloseSound(player);
        shopGui.openConfirm(player, shop, entry, quantity, shopService.preview(astPlayer, entry, quantity));
        GuiSound.SELECT.play(player);
    }

    private boolean handleHotbarShortcutClick(@NotNull InventoryClickEvent event, @NotNull Player player) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot < 0 || slot > 8) {
            return false;
        }
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            return false;
        }
        if (!inventoryService.getClickGuard().tryAcquire(
            astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT)) {
            return true;
        }
        boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
        if (handled) {
            if (slot == 4) {
                GuiSound.CLOSE.play(player);
            } else {
                GuiSound.SELECT.play(player);
            }
        } else {
            GuiSound.DENY.play(player);
        }
        return true;
    }
}

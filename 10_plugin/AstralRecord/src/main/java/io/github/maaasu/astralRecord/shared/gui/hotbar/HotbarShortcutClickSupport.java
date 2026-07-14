package io.github.maaasu.astralRecord.shared.gui.hotbar;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin GUI 上でプレイヤーインベントリ側の通常操作と BAG 制御を共通化します。
 */
public final class HotbarShortcutClickSupport {
    private static final int HOTBAR_MIN_SLOT = 0;
    private static final int HOTBAR_MAX_SLOT = 8;
    private static final int SCROLL_UP_SLOT = 17;
    private static final int SCROLL_DOWN_SLOT = 35;
    private HotbarShortcutClickSupport() {
        // utility class
    }

    /**
     * GUI 表示中のプレイヤーインベントリにある上下スクロールを共通処理します。
     *
     * @param event クリックイベント
     * @param player 操作したプレイヤー
     * @param inventoryService インベントリサービス
     * @return スクロール制御スロットを処理した場合は true
     */
    public static boolean handleInventoryControlClick(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull InventoryService inventoryService
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot != SCROLL_UP_SLOT && slot != SCROLL_DOWN_SLOT) {
            return false;
        }
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            return false;
        }

        event.setCancelled(true);
        inventoryService.handleInventoryControlClick(astPlayer, slot);
        GuiSound.SELECT.play(player);
        return true;
    }

    public static boolean handle(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull InventoryService inventoryService
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            return false;
        }

        if (inventoryService.handleInventoryControlClick(astPlayer, slot)) {
            event.setCancelled(true);
            GuiSound.SELECT.play(player);
            return true;
        }

        if (slot >= HOTBAR_MIN_SLOT && slot <= HOTBAR_MAX_SLOT) {
            event.setCancelled(true);
            if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.HOTBAR_SLOT)) {
                return true;
            }
            boolean handled = inventoryService.handleHotbarSlotClick(astPlayer, slot + 1);
            (handled ? GuiSound.SELECT : GuiSound.DENY).play(player);
            return true;
        }

        if (slot >= 9 && slot <= 35
            && inventoryService.getDisplayedEntryAtBukkitSlot(astPlayer, slot) != null) {
            event.setCancelled(true);
            if (!inventoryService.getClickGuard().tryAcquire(
                astPlayer.getAccount().getUuid(), InventoryClickGuard.ClickAction.DISPLAYED_ITEM)) {
                return true;
            }
            boolean handled = inventoryService.equipOrAssignClickedItem(astPlayer, slot);
            (handled ? GuiSound.SELECT : GuiSound.DENY).play(player);
            return true;
        }
        return false;
    }
}
